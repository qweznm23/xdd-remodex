package com.remodex.mobile.services

import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCMessage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class WorkspaceFileTransferServiceTest {
    private val revision = "a".repeat(64)
    private val payload = "APK\u0000中文测试data".toByteArray()
    private fun info(size: Long = payload.size.toLong()) = WorkspaceFileInfo("/repo/app.apk", "app.apk", size, revision, "application/vnd.android.package-archive", "none")
    private fun chunk(offset: Long, bytes: ByteArray, eof: Boolean): Map<String, JSONValue> = mapOf(
        "path" to JSONValue.Str("/repo/app.apk"), "revision" to JSONValue.Str(revision),
        "byteLength" to JSONValue.NumLong(payload.size.toLong()), "offset" to JSONValue.NumLong(offset),
        "nextOffset" to JSONValue.NumLong(offset + bytes.size), "eof" to JSONValue.Bool(eof),
        "dataBase64" to JSONValue.Str(Base64.getEncoder().encodeToString(bytes)),
        "sha256" to JSONValue.Str(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }),
    )
    private fun response(row: Map<String, JSONValue>) = RPCMessage.success(JSONValue.NumLong(1), JSONValue.Obj(row))

    @Test fun copiesChunksWithoutTextDecodingAndReportsProgress() = runTest {
        val offsets = mutableListOf<Long>()
        val progress = mutableListOf<Long>()
        val service = WorkspaceFileTransferService(MinimalSendRepository { method, params ->
            assertEquals("workspace/readFileChunk", method)
            assertEquals(revision, params?.objectValue?.get("revision")?.stringValue)
            val offset = params!!.objectValue!!["offset"]!!.longValue!!
            offsets.add(offset)
            val end = (offset.toInt() + 5).coerceAtMost(payload.size)
            response(chunk(offset, payload.copyOfRange(offset.toInt(), end), end == payload.size))
        })
        val output = ByteArrayOutputStream()
        service.copyTo(info(), "/repo", output) { progress.add(it) }
        assertContentEquals(payload, output.toByteArray())
        assertEquals(listOf(0L, 5L, 10L, 15L, 20L).filter { it < payload.size }, offsets)
        assertEquals(payload.size.toLong(), progress.last())
    }

    @Test fun rejectsCorruptionAndWrongOffsetsBeforeWriting() = runTest {
        for ((key, value, expected) in listOf(
            Triple("sha256", JSONValue.Str("0".repeat(64)), "checksum_mismatch"),
            Triple("offset", JSONValue.NumLong(1), "invalid_chunk"),
            Triple("revision", JSONValue.Str("b".repeat(64)), "invalid_chunk"),
            Triple("eof", JSONValue.Bool(false), "invalid_chunk"),
            Triple("dataBase64", JSONValue.Str("!bad!"), "invalid_base64"),
        )) {
            val service = WorkspaceFileTransferService(MinimalSendRepository { _, _ -> response(chunk(0, payload, true) + (key to value)) })
            val output = ByteArrayOutputStream()
            val error = assertFailsWith<FileTransferFailure> { service.copyTo(info(), "/repo", output) {} }
            assertEquals(expected, error.code)
            if (expected == "invalid_base64") assertIs<IllegalArgumentException>(error.cause)
            assertEquals(0, output.size())
        }
    }

    @Test fun storageFailureAndCallerCancellationKeepTheirOriginalCause() = runTest {
        val storageError = IOException("ENOSPC: disk full", IllegalStateException("device detail"))
        val service = WorkspaceFileTransferService(MinimalSendRepository { _, _ -> response(chunk(0, payload, true)) })
        val output = object : OutputStream() { override fun write(value: Int) { throw storageError } }
        val failure = assertFailsWith<IOException> { service.copyTo(info(), "/repo", output) {} }
        assertEquals(storageError.message, failure.message)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === storageError })
        assertEquals("device detail", storageError.cause?.message)
        val cancelled = CancellationException("user cancelled")
        val cancelling = WorkspaceFileTransferService(MinimalSendRepository { _, _ -> throw cancelled })
        val cancellation = assertFailsWith<CancellationException> { cancelling.copyTo(info(), "/repo", ByteArrayOutputStream()) {} }
        assertEquals(cancelled.message, cancellation.message)
        assertTrue(generateSequence<Throwable>(cancellation) { it.cause }.any { it === cancelled })
    }

    @Test fun readsBinaryMetadataAndRejectsUnsafeDestinationNames() = runTest {
        val base = mapOf("path" to JSONValue.Str("/repo/app.apk"), "fileName" to JSONValue.Str("app.apk"), "revision" to JSONValue.Str(revision), "byteLength" to JSONValue.NumLong(10), "mimeType" to JSONValue.Str("application/vnd.android.package-archive"), "previewKind" to JSONValue.Str("none"))
        val service = WorkspaceFileTransferService(MinimalSendRepository { method, _ -> assertEquals("workspace/fileInfo", method); response(base) })
        assertEquals("none", service.info("app.apk", "/repo").previewKind)
        val invalid = WorkspaceFileTransferService(MinimalSendRepository { _, _ -> response(base + ("fileName" to JSONValue.Str("../escape.apk"))) })
        assertEquals("invalid_metadata", assertFailsWith<FileTransferFailure> { invalid.info("app.apk", "/repo") }.code)
    }
}
