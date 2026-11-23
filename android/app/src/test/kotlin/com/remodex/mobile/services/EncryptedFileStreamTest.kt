package com.remodex.mobile.services

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer

class EncryptedFileStreamTest {
    private val data = ByteArray(400_000) { (it % 251).toByte() }
    private val ticket = FileStreamTicket("a".repeat(64),ByteArray(32) { it.toByte() },"b".repeat(64),data.size.toLong())
    private fun record(bytes: ByteArray, offset: Long): ByteArray {
        val nonce = ByteBuffer.allocate(12).putLong(offset).putInt(bytes.size).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(ticket.key,"AES"),GCMParameterSpec(128,nonce))
        cipher.updateAAD("${ticket.token}:${ticket.revision}:$offset".toByteArray())
        return ByteBuffer.allocate(4).putInt(bytes.size).array()+nonce+cipher.doFinal(bytes)
    }
    private fun wire(offset: Int = 0): ByteArray {
        val output = ByteArrayOutputStream()
        var position = offset
        while(position < data.size) { val end = minOf(position + 200_000,data.size); output.write(record(data.copyOfRange(position,end),position.toLong())); position=end }
        output.write(record(byteArrayOf(),position.toLong()))
        return output.toByteArray()
    }
    @Test fun boundedRangeUsesAbsoluteOffsetsAndAuthenticatesItsEnd() = runBlocking {
        MockWebServer().use { server ->
            val start=1234;val end=61234
            val body=record(data.copyOfRange(start,end),start.toLong())+record(byteArrayOf(),end.toLong())
            server.enqueue(MockResponse().setBody(Buffer().write(body)))
            val result=EncryptedFileStream().range(server.url("/").toString(),ticket,start.toLong(),(end-start).toLong())
            assertContentEquals(data.copyOfRange(start,end),result)
            assertEquals("/files/stream?offset=1234&length=60000",server.takeRequest().path)
        }
    }
    @Test fun independentBinaryConnectionPreservesBytesAndDoesNotUseRpc() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(wire())))
            val out = ByteArrayOutputStream();val progress=mutableListOf<Long>()
            EncryptedFileStream().copy(server.url("/").toString(),ticket,out) {progress+=it}
            assertContentEquals(data,out.toByteArray());assertEquals(data.size.toLong(),progress.last())
            assertEquals("/files/stream?offset=0",server.takeRequest().path)
        }
    }
    @Test fun interruptedResponseResumesOnlyAfterVerifiedRecords() = runBlocking {
        MockWebServer().use { server ->
            val first = record(data.copyOfRange(0,200_000),0)
            // A complete first record and an incomplete second one: the unverified tail is never written.
            server.enqueue(MockResponse().setBody(Buffer().write(first + byteArrayOf(0,1))))
            server.enqueue(MockResponse().setBody(Buffer().write(wire(200_000))))
            val out=ByteArrayOutputStream()
            EncryptedFileStream().copy(server.url("/").toString(),ticket,out) {}
            assertContentEquals(data,out.toByteArray());server.takeRequest()
            assertEquals("/files/stream?offset=200000",server.takeRequest().path)
        }
    }
    @Test fun closedConnectionBeforeResponseHeadersRetriesFromTheSameOffset() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody(Buffer().write(wire())))

            val output = ByteArrayOutputStream()
            EncryptedFileStream().copy(server.url("/").toString(), ticket, output) {}

            assertContentEquals(data, output.toByteArray())
            repeat(2) {
                assertEquals("/files/stream?offset=0", server.takeRequest().path)
            }
        }
    }
    @Test fun tamperingIsNotRetriedAndKeepsCryptoCause() = runBlocking {
        MockWebServer().use { server ->
            val bytes=wire();bytes[25]=(bytes[25].toInt() xor 1).toByte();server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val out=ByteArrayOutputStream()
            val failure=assertFailsWith<FileTransferFailure> {EncryptedFileStream().copy(server.url("/").toString(),ticket,out) {}}
            assertEquals("authentication_failed",failure.code);assertEquals("stream_verify",failure.stage)
            assertTrue(generateSequence<Throwable>(failure) {it.cause}.any {it is javax.crypto.AEADBadTagException})
            assertEquals(0,out.size());assertEquals(1,server.requestCount)
        }
    }
    @Test fun storageFailureIsNotRetriedAndKeepsCause() = runBlocking {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setBody(Buffer().write(wire())))
            val cause=IOException("ENOSPC",IllegalStateException("disk detail"))
            val output=object:OutputStream() {override fun write(value:Int) {throw cause}}
            val failure=assertFailsWith<FileTransferFailure> {EncryptedFileStream().copy(server.url("/").toString(),ticket,output) {}}
            assertEquals("storage_io",failure.code);assertEquals("stream_save",failure.stage)
            assertTrue(generateSequence<Throwable>(failure) {it.cause}.any {it === cause});assertEquals(1,server.requestCount)
        }
    }
    @Test fun expiredTicketKeepsHttpCodeAndServerDetail() = runBlocking {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"code\":\"transfer_expired\",\"stage\":\"stream\"}"))
            val failure=assertFailsWith<FileTransferFailure> {EncryptedFileStream().copy(server.url("/").toString(),ticket,ByteArrayOutputStream()) {}}
            assertEquals("http_401",failure.code);assertTrue(failure.message!!.contains("transfer_expired"));assertEquals(1,server.requestCount)
        }
    }
    @Test fun cancellingHungConnectionIsPromptAndDoesNotRetry() = runBlocking {
        MockWebServer().use {server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job=launch {EncryptedFileStream().copy(server.url("/").toString(),ticket,ByteArrayOutputStream()) {}}
            withContext(Dispatchers.IO) {assertNotNull(server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS))}
            withTimeout(3000) {job.cancelAndJoin()};assertTrue(job.isCancelled);assertEquals(1,server.requestCount)
        }
    }
}
