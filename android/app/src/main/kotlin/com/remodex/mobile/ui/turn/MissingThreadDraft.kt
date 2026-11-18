package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.error.CodexServiceError
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
    this is CodexServiceError.RpcFailure && rpcError.isExplicitServerThreadMissing()

/** File uploads are already represented by their host paths in the prepared turn text. */
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
)

internal class MissingThreadDraftStore(private val directory: File, private val key: () -> ByteArray) {
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

    fun save(device: String, thread: String, draft: MissingThreadDraft) {
        Files.createDirectories(directory.toPath())
        val temp = Files.createTempFile(directory.toPath(), "draft", ".tmp")
        try {
            val payload = Json.encodeToString(MissingThreadDraft.serializer(), draft).encodeToByteArray()
            temp.toFile().writeBytes(MessageHistoryAesGcm.encrypt(payload, key()))
            Files.move(temp, file(device, thread).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            try {
                Files.deleteIfExists(temp)
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun clear(device: String, thread: String) {
        Files.deleteIfExists(file(device, thread).toPath())
    }
}
