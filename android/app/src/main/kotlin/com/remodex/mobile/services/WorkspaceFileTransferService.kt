package com.remodex.mobile.services

import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCObject
import com.remodex.mobile.data.CodexRepository
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class WorkspaceFileInfo(
    val path: String,
    val fileName: String,
    val byteLength: Long,
    val revision: String,
    val mimeType: String,
    val previewKind: String,
    val binaryStream: Boolean = false,
)

internal class FileTransferFailure(val stage: String, val code: String, message: String, cause: Throwable? = null) : IOException(message, cause)

internal class WorkspaceFileTransferService(private val repository: CodexRepository) {
    suspend fun info(path: String, cwd: String?): WorkspaceFileInfo {
        val row = request("workspace/fileInfo", params(path, cwd))
        val name = row["fileName"]?.stringValue.orEmpty()
        val resolved = row["path"]?.stringValue.orEmpty()
        val size = row["byteLength"]?.longValue
        val revision = row["revision"]?.stringValue.orEmpty()
        if (name.isBlank() || name in setOf(".", "..") || name.any { it == '/' || it == '\\' || it.code < 32 } || resolved.isBlank() || size == null || size < 0 || !revision.matches(Regex("[0-9a-f]{64}"))) {
            throw FileTransferFailure("metadata", "invalid_metadata", "Invalid file metadata from bridge")
        }
        return WorkspaceFileInfo(resolved, name, size, revision, row["mimeType"]?.stringValue ?: "application/octet-stream", row["previewKind"]?.stringValue ?: "none", row["binaryStream"]?.boolValue == true)
    }

    // The caller owns the stream. Never aggregate the whole download in memory.
    suspend fun copyTo(info: WorkspaceFileInfo, cwd: String?, output: OutputStream, onProgress: (Long) -> Unit) {
        if (info.binaryStream) {
            val baseUrl = route()
            val row = prepare(info.path, cwd, "download", mapOf("revision" to JSONValue.Str(info.revision)))
            val ticket = FileStreamTicket.parse(row)
            if (ticket.revision != info.revision || ticket.byteLength != info.byteLength) throw FileTransferFailure("stream_ticket","file_changed","File changed before download")
            EncryptedFileStream().copy(baseUrl, ticket, output, onProgress)
            return
        }
        var offset = 0L
        do {
            currentCoroutineContext().ensureActive()
            val row = request("workspace/readFileChunk", params(info.path, cwd) + mapOf(
                "revision" to JSONValue.Str(info.revision), "offset" to JSONValue.NumLong(offset),
            ))
            val encoded = row["dataBase64"]?.stringValue ?: throw FileTransferFailure("chunk", "missing_content", "Missing download chunk")
            if (encoded.length > 349_528) throw FileTransferFailure("chunk", "chunk_too_large", "Download chunk exceeds 256 KiB")
            val bytes = withContext(Dispatchers.IO) {
                val data = try { Base64.getDecoder().decode(encoded) }
                catch (error: IllegalArgumentException) { throw FileTransferFailure("chunk", "invalid_base64", "Invalid download encoding", error) }
                val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
                if (hash != row["sha256"]?.stringValue) throw FileTransferFailure("verify", "checksum_mismatch", "Download chunk checksum does not match")
                data
            }
            val next = offset + bytes.size
            val eof = row["eof"]?.boolValue
            if (row["revision"]?.stringValue != info.revision || row["path"]?.stringValue != info.path || row["byteLength"]?.longValue != info.byteLength || row["offset"]?.longValue != offset || row["nextOffset"]?.longValue != next || next > info.byteLength || eof != (next == info.byteLength) || (bytes.isEmpty() && next != info.byteLength)) {
                throw FileTransferFailure("verify", "invalid_chunk", "Download metadata or offset changed")
            }
            withContext(Dispatchers.IO) { output.write(bytes) }
            offset = next
            onProgress(offset)
        } while (offset < info.byteLength)
        withContext(Dispatchers.IO) { output.flush() }
    }

    suspend fun image(info: WorkspaceFileInfo, cwd: String?): ByteArray {
        val row = request("workspace/readImage", params(info.path, cwd))
        val encoded = row["dataBase64"]?.stringValue ?: throw FileTransferFailure("preview", "missing_image", "Image data missing")
        if (encoded.length > 11_184_812) throw FileTransferFailure("preview", "image_too_large", "Image preview exceeds 8 MiB")
        return withContext(Dispatchers.IO) {
            try { Base64.getDecoder().decode(encoded) }
            catch (error: IllegalArgumentException) { throw FileTransferFailure("preview", "invalid_base64", "Invalid image encoding", error) }
        }
    }

    private fun route() = repository.fileTransferBaseUrl ?: throw FileTransferFailure("stream_route","route_unavailable","File connection is unavailable")

    private suspend fun prepare(path: String, cwd: String?, mode: String, extra: RPCObject = emptyMap()): RPCObject =
        request("workspace/prepareTransfer", params(path,cwd) + extra + ("mode" to JSONValue.Str(mode)))

    suspend fun videoStream(info: WorkspaceFileInfo, cwd: String?): Pair<String,FileStreamTicket> {
        val baseUrl = route()
        val row = prepare(info.path,cwd,"download",mapOf("revision" to JSONValue.Str(info.revision)))
        val ticket = FileStreamTicket.parse(row)
        if (row["rangeSupported"]?.boolValue != true) throw FileTransferFailure("video_prepare","range_unsupported","Update the computer bridge to enable video streaming")
        if (ticket.revision != info.revision || ticket.byteLength != info.byteLength) throw FileTransferFailure("video_prepare","file_changed","Video changed before playback")
        return baseUrl to ticket
    }

    suspend fun imagePreview(path: String, cwd: String?, dimension: Long): ByteArray {
        val baseUrl = route()
        val row = try { prepare(path,cwd,"image",mapOf("maxPixelDimension" to JSONValue.NumLong(dimension))) }
        catch (failure: com.remodex.mobile.core.error.CodexServiceError.RpcFailure) {
            val code = failure.rpcError.data?.objectValue?.get("errorCode")?.stringValue
            if (failure.rpcError.code != -32601 && code !in setOf("stream_unavailable","unknown_method")) throw failure
            java.util.logging.Logger.getLogger("RemodexFile").log(java.util.logging.Level.INFO,"Bridge lacks binary previews; using legacy preview RPC",failure)
            try {
                val preview = WorkspaceImageService(repository).readPreviewDataUrl(path,dimension,cwd)
                    ?: throw FileTransferFailure("preview","missing_image","Image preview is empty")
                return Base64.getDecoder().decode(preview.dataUrl.substringAfter(','))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { error.addSuppressed(failure); throw error }
        }
        val ticket = FileStreamTicket.parse(row)
        if (ticket.byteLength > 2 * 1024 * 1024) throw FileTransferFailure("preview","image_too_large","Image thumbnail exceeds 2 MiB")
        val output = java.io.ByteArrayOutputStream()
        EncryptedFileStream().copy(baseUrl,ticket,output) {}
        return output.toByteArray()
    }

    data class TextPage(val text: String, val nextOffset: Long)
    suspend fun textPage(info: WorkspaceFileInfo, cwd: String?, offset: Long): TextPage {
        val baseUrl = route()
        val row = prepare(info.path,cwd,"text",mapOf("revision" to JSONValue.Str(info.revision),"offset" to JSONValue.NumLong(offset)))
        val ticket = FileStreamTicket.parse(row)
        val next = row["nextOffset"]?.longValue
        if (next == null || ticket.revision != info.revision || ticket.byteLength > 65536 || next != offset + ticket.byteLength || next > info.byteLength || (next == offset && offset < info.byteLength)) throw FileTransferFailure("preview","invalid_page","Invalid text preview page")
        val output = java.io.ByteArrayOutputStream()
        EncryptedFileStream().copy(baseUrl,ticket,output) {}
        val text = try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString() }
        catch(cause: java.nio.charset.CharacterCodingException) { throw FileTransferFailure("preview","invalid_utf8","Invalid text encoding",cause) }
        return TextPage(text,next!!)
    }

    private fun params(path: String, cwd: String?): RPCObject = buildMap {
        put("path", JSONValue.Str(path))
        cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", JSONValue.Str(it)) }
    }

    private suspend fun request(method: String, params: RPCObject): RPCObject {
        val response = try { withTimeout(30_000) { repository.sendRequest(method, JSONValue.Obj(params)) } }
        catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw FileTransferFailure(method, "rpc_timeout", "File request timed out", error)
        }
        return response.result?.objectValue ?: throw FileTransferFailure(method, "invalid_response", "Invalid response from bridge")
    }
}
