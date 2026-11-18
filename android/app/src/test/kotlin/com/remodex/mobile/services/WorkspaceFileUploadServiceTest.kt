package com.remodex.mobile.services

import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class WorkspaceFileUploadServiceTest {
    private fun response(fields: Map<String, JSONValue>) = RPCMessage.success(JSONValue.Str("r"), JSONValue.Obj(fields))
    private fun begin() = response(mapOf("uploadId" to JSONValue.Str("test-upload"), "chunkBytes" to JSONValue.NumLong(262144), "maxBytes" to JSONValue.NumLong(MAX_FILE_UPLOAD_BYTES)))

    @Test fun binaryFileAboveOldLimitArrivesCompletelyInBoundedVerifiedChunks() = runTest {
        val bytes = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
        var received = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val progress = mutableListOf<Long>()
        val service = WorkspaceFileUploadService(MinimalSendRepository { method, params ->
            val p = params!!.objectValue!!
            when (method) {
                "workspace/uploadBegin" -> begin()
                "workspace/uploadChunk" -> {
                    assertEquals(received, p["offset"]?.longValue)
                    val data = Base64.getDecoder().decode(p["dataBase64"]!!.stringValue)
                    assertTrue(data.size in 1..262144)
                    assertContentEquals(bytes.copyOfRange(received.toInt(), received.toInt() + data.size), data)
                    assertEquals(hash(data), p["sha256"]?.stringValue)
                    received += data.size; digest.update(data)
                    response(mapOf("nextOffset" to JSONValue.NumLong(received)))
                }
                "workspace/uploadFinish" -> {
                    assertEquals(bytes.size.toLong(), received)
                    assertEquals(digest.digest().hex(), p["sha256"]?.stringValue)
                    response(p + ("path" to JSONValue.Str("/computer/upload/archive.zip")))
                }
                else -> error("Unexpected method $method")
            }
        })
        val result = service.upload("archive.zip", bytes.size.toLong(), ByteArrayInputStream(bytes)) { progress.add(it) }
        assertEquals(bytes.size.toLong(), result.byteLength)
        assertEquals(hash(bytes), result.sha256)
        assertEquals(listOf(262144L, 524288L, 786432L, 1048576L, 1048593L), progress)
    }

    @Test fun failedChunkKeepsCauseAndSuppressedCleanupFailure() = runTest {
        val primary = IOException("disk write rejected", IllegalStateException("original cause"))
        primary.addSuppressed(IOException("earlier evidence"))
        val cleanup = IOException("abort failed")
        val methods = mutableListOf<String>()
        val service = WorkspaceFileUploadService(MinimalSendRepository { method, _ ->
            methods.add(method)
            when (method) {
                "workspace/uploadBegin" -> begin()
                "workspace/uploadAbort" -> throw cleanup
                else -> throw primary
            }
        })
        val actual = assertFailsWith<IOException> { service.upload("a", 1, ByteArrayInputStream(byteArrayOf(1))) {} }
        val chain = generateSequence<Throwable>(actual) { it.cause }.toList()
        assertTrue(chain.any { it === primary })
        assertEquals("original cause", primary.cause?.message)
        val suppressed = chain.flatMap { it.suppressed.toList() }.map { it.message }
        assertTrue("earlier evidence" in suppressed)
        assertTrue("abort failed" in suppressed)
        assertEquals(listOf("workspace/uploadBegin", "workspace/uploadChunk", "workspace/uploadAbort"), methods)
    }

    @Test fun cancellationAbortsPartialUploadAndRemainsCancellation() = runTest {
        val cancellation = CancellationException("attachment removed")
        var aborted = false
        val service = WorkspaceFileUploadService(MinimalSendRepository { method, _ ->
            when (method) {
                "workspace/uploadBegin" -> begin()
                "workspace/uploadAbort" -> { aborted = true; response(emptyMap()) }
                else -> throw cancellation
            }
        })
        val actual = assertFailsWith<CancellationException> { service.upload("a", null, ByteArrayInputStream(byteArrayOf(1))) {} }
        assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
        assertTrue(aborted)
    }

    @Test fun oversizeMetadataIsRejectedBeforeStartingUpload() = runTest {
        val service = WorkspaceFileUploadService(MinimalSendRepository { _, _ -> error("No network call expected") })
        val error = assertFailsWith<FileTransferFailure> { service.upload("a", MAX_FILE_UPLOAD_BYTES + 1, ByteArrayInputStream(byteArrayOf())) {} }
        assertEquals("upload_too_large", error.code)
        assertEquals("upload_begin", error.stage)
    }

    @Test fun incorrectAcknowledgementFailsAndAborts() = runTest {
        var aborted = false
        val service = WorkspaceFileUploadService(MinimalSendRepository { method, _ ->
            when (method) {
                "workspace/uploadBegin" -> begin()
                "workspace/uploadAbort" -> { aborted = true; response(emptyMap()) }
                else -> response(mapOf("nextOffset" to JSONValue.NumLong(999)))
            }
        })
        val error = assertFailsWith<FileTransferFailure> { service.upload("a", 1, ByteArrayInputStream(byteArrayOf(1))) {} }
        assertEquals("invalid_offset", error.code); assertEquals("upload_verify", error.stage); assertTrue(aborted)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
