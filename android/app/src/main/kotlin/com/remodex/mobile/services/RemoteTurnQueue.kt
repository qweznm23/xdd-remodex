package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.*
import com.remodex.mobile.core.persistence.MessageHistoryAesGcm
import com.remodex.mobile.data.QueuedTurnDraft
import com.remodex.mobile.data.QueuedTurnDraftPreview
import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class OutgoingQueueEntry(val thread: String, val draft: QueuedTurnDraft,
    val attempted: Boolean = false, val receipt: String? = null, val held: Boolean = false)

internal class QueueOutbox(private val directory: () -> File, private val key: () -> ByteArray) {
    private fun file(device: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(device.toByteArray()).joinToString("") { "%02x".format(it) }
        return directory().resolve(hash)
    }
    fun read(device: String): List<OutgoingQueueEntry> {
        val encrypted = try { Files.readAllBytes(file(device).toPath()) } catch (_: NoSuchFileException) { return emptyList() }
        return Json.decodeFromString(MessageHistoryAesGcm.decryptOrThrow(encrypted, key()).decodeToString())
    }
    fun write(device: String, rows: List<OutgoingQueueEntry>) {
        val target = file(device).toPath()
        if (rows.isEmpty()) { Files.deleteIfExists(target); return }
        val bytes = MessageHistoryAesGcm.encrypt(Json.encodeToString(rows).encodeToByteArray(), key())
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "queue", ".tmp")
        try {
            temp.toFile().outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            try { Files.deleteIfExists(temp) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}

internal class RemoteTurnQueue(
    private val outbox: QueueOutbox,
    private val device: () -> String?,
    private val connected: () -> Boolean,
    private val generation: () -> Long,
    private val activeThread: () -> String?,
    private val request: suspend (String, JSONValue.Obj, String, Long) -> RPCMessage,
    private val diagnose: (String, Exception) -> Unit,
) {
    private val mutex = Mutex()
    val wake = Channel<Unit>(Channel.CONFLATED)
    val previews = MutableStateFlow<Map<String, List<QueuedTurnDraftPreview>>>(emptyMap())
    val depths = MutableStateFlow<Map<String, Int>>(emptyMap())
    val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private var host: String? = null
    private var local = emptyList<OutgoingQueueEntry>()
    private val remote = mutableMapOf<String, List<QueuedTurnDraft>>()

    private fun select(expected: String?) {
        if (device() != expected) throw CancellationException("Queue device changed")
        if (expected == host) return
        host = null
        local = emptyList()
        remote.clear()
        previews.value = emptyMap()
        depths.value = emptyMap()
        errors.value = emptyMap()
        local = expected?.let(outbox::read).orEmpty()
        host = expected
        publish()
    }
    private fun publish() {
        if (host != device()) return
        val rows = (remote.keys + local.map { it.thread }).associateWith { thread ->
            val pending = local.filter { it.thread == thread }
            val listed = remote[thread].orEmpty()
            val ids = listed.mapTo(hashSetOf()) { it.id }
            listed.map { cloud ->
                val draft = editable(thread, cloud.id) ?: cloud
                preview(draft, cloud.id, false)
            } + pending.filter { it.receipt !in ids }.map { preview(it.draft, it.receipt ?: it.draft.id, it.receipt == null, it.held) }
        }.filterValues { it.isNotEmpty() }
        previews.value = rows
        depths.value = rows.mapValues { it.value.size }
    }
    private fun preview(d: QueuedTurnDraft, id: String, waiting: Boolean, held: Boolean = false) = QueuedTurnDraftPreview(id,
        d.composerText ?: d.text, d.files.size + d.attachments.size, d.collaborationMode, waiting, held, d.editable)
    private fun save(rows: List<OutgoingQueueEntry>) {
        outbox.write(requireNotNull(host), rows)
        local = rows
        publish()
    }
    suspend fun enqueue(expected: String, thread: String, draft: QueuedTurnDraft) = mutex.withLock {
        select(expected)
        require(thread.isNotBlank() && (draft.text.isNotBlank() || draft.attachments.isNotEmpty() ||
            draft.skillMentions.isNotEmpty() || draft.fileMentions.isNotEmpty()))
        val existing = local.firstOrNull { it.draft.id == draft.id }
        if (existing == null) save(local + OutgoingQueueEntry(thread, draft))
        else require(existing.thread == thread && existing.draft == draft) { "Queue ID reused for different input" }
        wake.trySend(Unit)
        Unit
    }
    private suspend fun rpc(method: String, fields: Map<String,JSONValue>, epoch: Long): Map<String,JSONValue> {
        fun checkConnection() {
            if (host != device() || !connected() || epoch != generation()) throw CodexServiceError.Disconnected
        }
        checkConnection()
        val reply = withTimeoutOrNull(30_000) { request(method, JSONValue.Obj(fields), requireNotNull(host), epoch) }
            ?: throw SocketTimeoutException("Timed out at $method")
        checkConnection()
        reply.error?.let { throw CodexServiceError.RpcFailure(it) }
        return reply.result?.objectValue ?: throw CodexServiceError.InvalidResponse("$method: missing result")
    }
    private suspend fun submit(entry: OutgoingQueueEntry, epoch: Long): String {
        val draft = entry.draft
        val fields = mapOf("threadId" to JSONValue.Str(entry.thread), "clientUserMessageId" to JSONValue.Str(draft.id),
            "input" to JSONValue.Arr(makeTurnInputPayload(draft.text, draft.attachments, "url", draft.skillMentions, draft.fileMentions)))
        if (!entry.attempted) save(local.map { if (it == entry) it.copy(attempted = true) else it })
        val reply = try { rpc("remodex/queue/add", fields, epoch) } catch (e: CodexServiceError.RpcFailure) {
            if (e.rpcError.code == -32601 || e.rpcError.data?.objectValue?.get("notSubmitted")?.boolValue == true) {
                save(local.map { if (it.draft.id == draft.id) it.copy(attempted = false) else it })
            }
            if (e.rpcError.data?.objectValue?.get("upstream")?.objectValue?.get("errorCode")?.stringValue == "QUEUE_UNCONFIRMED") {
                save(local.map { if (it.draft.id == draft.id) it.copy(attempted = false, held = true) else it })
            }
            throw e
        }
        val id = reply["queuedSubmissionId"]?.stringValue ?: throw CodexServiceError.InvalidResponse("queue/add: missing receipt")
        save(local.map { if (it.draft.id == draft.id) it.copy(receipt = id) else it })
        return id
    }
    private suspend fun refresh(thread: String, epoch: Long) {
        val list = mutableListOf<QueuedTurnDraft>()
        val visited = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val fields = mutableMapOf<String,JSONValue>("threadId" to JSONValue.Str(thread), "limit" to JSONValue.NumLong(8))
            cursor?.let { fields["cursor"] = JSONValue.Str(it) }
            val page = rpc("thread/queue/list", fields, epoch)
            val data = page["data"]?.arrayValue ?: throw CodexServiceError.InvalidResponse("queue/list: missing data")
            list += data.map { decodeQueuedSubmission(requireNotNull(it.objectValue)) }
            cursor = page["nextCursor"]?.stringValue
            check(cursor == null || visited.add(cursor)) { "queue/list: repeated cursor" }
        } while (cursor != null)
        remote[thread] = list
        val ids = list.mapTo(hashSetOf()) { it.id }
        val next = local.filterNot { it.thread == thread && it.receipt != null && it.receipt !in ids }
        if (local != next) save(next) else publish()
    }
    suspend fun sync() = mutex.withLock {
        select(device())
        if (host == null || !connected()) return@withLock
        val epoch = generation()
        for (thread in (local.map { it.thread } + listOfNotNull(activeThread())).distinct()) {
            try {
                for (entry in local.filter { it.thread == thread && it.receipt == null && !it.held }) submit(entry, epoch)
                refresh(thread, epoch)
                errors.value -= thread
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (device() != host || epoch != generation() || !connected()) return@withLock
                if (failure is CodexServiceError.RpcFailure && failure.rpcError.code == -32601 &&
                    local.none { it.thread == thread } && remote[thread].isNullOrEmpty()) continue
                diagnose("sync queue thread=$thread", failure)
                errors.value += thread to (failure.message ?: failure.javaClass.name)
            }
        }
    }

    suspend fun take(expected: String, thread: String, id: String, forEdit: Boolean = false): QueuedTurnDraft? = mutex.withLock {
        select(expected)
        if (forEdit && remote[thread]?.any { it.id == id } == true) {
            refresh(thread, generation())
            val current = editable(thread, id) ?: throw CodexServiceError.InvalidInput("Queue item already started or removed")
            require(current.editable) { "Edit this item on its source client" }
        }
        withdraw(thread, id)
    }

    suspend fun steer(expected: String, thread: String, id: String, turn: String) = mutex.withLock {
        select(expected)
        val epoch = generation()
        if (!connected()) throw CodexServiceError.Disconnected
        refresh(thread, epoch)
        val original = editable(thread, id) ?: throw CodexServiceError.InvalidInput("Queue item no longer present")
        require(original.editable) { "This input must be edited or steered on its source client" }
        // Native steer does not consume queued items. Keep a recovery copy before claiming it.
        // After a lost acknowledgement neither operation is safe to repeat automatically.
        val held = OutgoingQueueEntry(thread, original.copy(id = java.util.UUID.randomUUID().toString()), held = true)
        save(local + held)
        val draft = withdraw(thread, id) ?: throw CodexServiceError.InvalidInput("Queue item no longer present")
        rpc("turn/steer", mapOf("threadId" to JSONValue.Str(thread), "expectedTurnId" to JSONValue.Str(turn),
            "input" to JSONValue.Arr(makeTurnInputPayload(draft.text, draft.attachments, "url", draft.skillMentions, draft.fileMentions))), epoch)
        save(local - held)
        Unit
    }

    private fun editable(thread: String, id: String): QueuedTurnDraft? {
        val original = local.find { it.thread == thread && (it.draft.id == id || it.receipt == id) }?.draft
        val actual = remote[thread]?.find { it.id == id } ?: return original
        return original?.takeIf { actual.editable && sameInput(it, actual) } ?: actual
    }

    private fun sameInput(a: QueuedTurnDraft, b: QueuedTurnDraft) = a.text.trim() == b.text.trim() &&
        a.attachments.map { it.remotePath ?: it.payloadDataURL } == b.attachments.map { it.remotePath ?: it.payloadDataURL } &&
        a.skillMentions.map { it.path } == b.skillMentions.map { it.path } && a.fileMentions == b.fileMentions

    private suspend fun withdraw(thread: String, id: String): QueuedTurnDraft? {
        val entry = local.find { it.thread == thread && (it.draft.id == id || it.receipt == id) }
        if (entry != null && !entry.attempted && entry.receipt == null) {
            save(local - entry)
            errors.value -= thread
            return entry.draft
        }
        val epoch = generation()
        val draft = editable(thread, id) ?: return null
        val serverId = entry?.receipt ?: id
        val reply = if (entry != null && entry.receipt == null) {
            rpc("remodex/queue/cancelPending", mapOf("threadId" to JSONValue.Str(thread),
                "clientUserMessageId" to JSONValue.Str(entry.draft.id)), epoch)
        } else {
            rpc("thread/queue/delete", mapOf("threadId" to JSONValue.Str(thread),
                "queuedSubmissionId" to JSONValue.Str(serverId)), epoch)
        }
        val deleted = reply["deleted"]?.boolValue ?: throw CodexServiceError.InvalidResponse("queue/delete: missing result")
        wake.trySend(Unit)
        remote[thread] = remote[thread].orEmpty().filterNot { it.id == serverId }
        if (entry != null) save(local.filterNot { it.draft.id == entry.draft.id }) else publish()
        if (!deleted) throw CodexServiceError.InvalidResponse("Queue item already started or removed")
        return draft
    }
}

internal fun decodeQueuedSubmission(row: Map<String, JSONValue>): QueuedTurnDraft {
    val text = mutableListOf<String>()
    val images = mutableListOf<CodexImageAttachment>()
    val skills = mutableListOf<CodexTurnSkillMention>()
    val mentions = mutableListOf<CodexTurnMention>()
    val inputs = requireNotNull(row["input"]?.arrayValue) { "queue/list: missing input" }
    var editable = true
    for (item in inputs) {
        val v = requireNotNull(item.objectValue)
        when (v["type"]?.stringValue) {
            "text" -> text += requireNotNull(v["text"]?.stringValue)
            "localImage" -> images += CodexImageAttachment(thumbnailBase64JPEG = "", remotePath = requireNotNull(v["path"]?.stringValue))
            "image" -> {
                val url = v["url"]?.stringValue
                if (url == null) editable = false else images += CodexImageAttachment(thumbnailBase64JPEG = "", payloadDataURL = url)
            }
            "skill" -> skills += CodexTurnSkillMention(v["id"]?.stringValue ?: v["name"]?.stringValue.orEmpty(), v["name"]?.stringValue, v["path"]?.stringValue)
            "mention" -> mentions += CodexTurnMention(requireNotNull(v["name"]?.stringValue), requireNotNull(v["path"]?.stringValue))
            else -> editable = false // Preserve foreign audio and other input types on the host.
        }
    }
    return QueuedTurnDraft(requireNotNull(row["id"]?.stringValue), text.joinToString("\n"), images, skills, mentions, null, editable = editable)
}
