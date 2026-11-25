package com.remodex.mobile.ui.turn

import android.content.Context
import com.remodex.mobile.AppContainer
import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.CodexFileAttachment
import com.remodex.mobile.core.model.CodexImageAttachment
import com.remodex.mobile.core.model.CodexReviewTarget
import com.remodex.mobile.core.model.CodexTurnMention
import com.remodex.mobile.core.model.CodexTurnSkillMention
import com.remodex.mobile.core.model.isExplicitServerThreadMissing
import com.remodex.mobile.core.persistence.MessageHistoryAesGcm
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun Throwable.isMissingSendTarget(): Boolean =
    this is CodexServiceError.MissingSendTargetAfterResume ||
        (this is CodexServiceError.RpcFailure && rpcError.isExplicitServerThreadMissing())

/** Keep the prepared turn and, when available, the editable draft and file chips. */
@Serializable
internal data class MissingThreadDraft(
    val text: String,
    val images: List<CodexImageAttachment> = emptyList(),
    val skills: List<CodexTurnSkillMention> = emptyList(),
    val mentions: List<CodexTurnMention> = emptyList(),
    val plan: Boolean = false,
    val review: CodexReviewTarget? = null,
    val branch: String? = null,
    val cwd: String? = null,
    val model: String? = null,
    val transferred: Boolean = false,
    val composerText: String? = null,
    val files: List<CodexFileAttachment> = emptyList(),
    val retentionId: String? = null,
) {
    val editableText: String
        get() = composerText ?: stripMergedMentionPrefix(text, skills, mentions)

    fun composerAttachments(): List<TurnComposerAttachment> =
        images.map { TurnComposerAttachment(state = TurnComposerAttachmentState.ReadyImage(it)) } +
            files.map { TurnComposerAttachment(state = TurnComposerAttachmentState.ReadyFile(it)) }
}

internal class MissingThreadDraftStore(private val directory: File, private val key: () -> ByteArray) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "unsent-turns"),
        AppContainer.messagePersistence::messageHistoryKey,
    )

    private companion object {
        val writeLock = Any()
    }

    private fun file(device: String, thread: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest((device + "\u0000" + thread).toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) })
    }

    fun read(device: String, thread: String): MissingThreadDraft? {
        val path = file(device, thread)
        val encrypted = try {
            Files.readAllBytes(path.toPath())
        } catch (_: java.nio.file.NoSuchFileException) {
            return null
        }
        val bytes = MessageHistoryAesGcm.decryptOrThrow(encrypted, key())
        return Json.decodeFromString<MissingThreadDraft>(bytes.decodeToString())
    }

    fun save(device: String, thread: String, draft: MissingThreadDraft) = synchronized(writeLock) {
        Files.createDirectories(directory.toPath())
        val temp = Files.createTempFile(directory.toPath(), "draft", ".tmp")
        try {
            val payload = Json.encodeToString(MissingThreadDraft.serializer(), draft).encodeToByteArray()
            temp.toFile().writeBytes(MessageHistoryAesGcm.encrypt(payload, key()))
            Files.move(temp, file(device, thread).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } catch (failure: Throwable) {
            try {
                Files.deleteIfExists(temp)
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun saveIfAbsent(device: String, thread: String, draft: MissingThreadDraft) = synchronized(writeLock) {
        if (read(device, thread) == null) save(device, thread, draft)
    }

    fun clear(device: String, thread: String) = synchronized(writeLock) {
        Files.deleteIfExists(file(device, thread).toPath())
    }

    fun clearIfUnchanged(device: String, thread: String, draft: MissingThreadDraft) = synchronized(writeLock) {
        if (read(device, thread) == draft) clear(device, thread)
    }
}
