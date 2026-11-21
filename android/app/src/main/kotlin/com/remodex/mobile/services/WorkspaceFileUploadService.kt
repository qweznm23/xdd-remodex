package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCObject
import com.remodex.mobile.data.CodexRepository
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val MAX_FILE_UPLOAD_BYTES = 100L * 1024 * 1024
internal data class UploadedFile(val path: String, val byteLength: Long, val sha256: String)

internal class WorkspaceFileUploadService(private val repository: CodexRepository) {
    // The caller owns input. At most one bounded chunk is held while waiting for its acknowledgement.
    suspend fun upload(fileName: String, size: Long?, input: InputStream, onProgress: suspend (Long) -> Unit): UploadedFile = withContext(Dispatchers.IO) {
        if (size != null && (size < 0 || size > MAX_FILE_UPLOAD_BYTES)) {
            throw FileTransferFailure("upload_begin", "upload_too_large", "File exceeds 100 MB")
        }
        var uploadId: String? = null
        try {
            val start = request("workspace/uploadBegin", mapOf(
                "fileName" to JSONValue.Str(fileName), "byteLength" to (size?.let(JSONValue::NumLong) ?: JSONValue.Null),
            ))
            uploadId = start["uploadId"]?.stringValue
                ?: throw FileTransferFailure("upload_begin", "invalid_response", "Upload ID is missing")
            val chunkSize = start["chunkBytes"]?.longValue
            val serverLimit = start["maxBytes"]?.longValue
            if (chunkSize == null || chunkSize !in 1..262144 || serverLimit == null || serverLimit <= 0) {
                throw FileTransferFailure("upload_begin", "invalid_response", "Invalid upload limits")
            }
            val limit = minOf(MAX_FILE_UPLOAD_BYTES, serverLimit)
            val buffer = ByteArray(chunkSize.toInt())
            val wholeHash = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = try {
                    input.read(buffer)
                } catch (failure: IOException) {
                    throw FileTransferFailure("upload_read", "source_read_failed", failure.message ?: "Could not read attachment", failure)
                }
                if (count < 0) break
                if (count == 0) throw FileTransferFailure("upload_read", "read_stalled", "File input made no progress")
                if (offset + count > limit || (size != null && offset + count > size)) {
                    throw FileTransferFailure("upload_read", "upload_too_large", "File exceeds its declared size or the 100 MB limit")
                }
                val data = buffer.copyOf(count)
                val row = request("workspace/uploadChunk", mapOf(
                    "uploadId" to JSONValue.Str(uploadId), "offset" to JSONValue.NumLong(offset),
                    "dataBase64" to JSONValue.Str(Base64.getEncoder().encodeToString(data)),
                    "sha256" to JSONValue.Str(sha256(data)),
                ))
                if (row["nextOffset"]?.longValue != offset + count) throw FileTransferFailure("upload_verify", "invalid_offset", "Upload acknowledgement offset does not match")
                wholeHash.update(data)
                offset += count
                onProgress(offset)
            }
            if (size != null && size != offset) throw FileTransferFailure("upload_verify", "size_mismatch", "File changed or ended before its declared size")
            val digest = wholeHash.digest().hex()
            val done = request("workspace/uploadFinish", mapOf(
                "uploadId" to JSONValue.Str(uploadId), "byteLength" to JSONValue.NumLong(offset), "sha256" to JSONValue.Str(digest),
            ))
            val path = done["path"]?.stringValue
            if (path.isNullOrBlank() || done["byteLength"]?.longValue != offset || done["sha256"]?.stringValue != digest) {
                throw FileTransferFailure("upload_finish", "checksum_mismatch", "Uploaded file confirmation does not match")
            }
            UploadedFile(path, offset, digest)
        } catch (failure: Throwable) {
            uploadId?.let { id ->
                try {
                    withContext(NonCancellable) {
                        request("workspace/uploadAbort", mapOf("uploadId" to JSONValue.Str(id)), 5_000)
                    }
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                    if (failure is kotlinx.coroutines.CancellationException) {
                        android.util.Log.w("RemodexUpload", "Cancelled upload cleanup failed stage=upload_abort", failure)
                    }
                }
            }
            // A domain exception also prevents coroutine stack-trace recovery from
            // replacing a plain IOException and dropping its cleanup diagnostics.
            if (failure is kotlinx.coroutines.CancellationException || failure is FileTransferFailure) throw failure
            throw FileTransferFailure("upload", "upload_failed", failure.message ?: "Upload failed", failure)
        }
    }

    private suspend fun request(method: String, params: RPCObject, timeout: Long = 30_000): RPCObject {
        val response = try {
            val outcome = withTimeoutOrNull(timeout) { repository.sendRequest(method, JSONValue.Obj(params)) }
                ?: throw FileTransferFailure(method, "rpc_timeout", "Upload request timed out stage=$method timeoutMs=$timeout")
            outcome.error?.let { throw CodexServiceError.RpcFailure(it) }
            outcome
        } catch (failure: CodexServiceError.RpcFailure) {
            val rpc = failure.rpcError
            val code = rpc.data?.objectValue?.get("errorCode")?.stringValue ?: "rpc_${rpc.code}"
            throw FileTransferFailure(method, code, rpc.message, failure)
        }
        return response.result?.objectValue
            ?: throw FileTransferFailure(method, "invalid_response", "Invalid upload response stage=$method")
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
