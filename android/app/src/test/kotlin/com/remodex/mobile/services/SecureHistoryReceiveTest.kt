package com.remodex.mobile.services

import com.remodex.mobile.core.crypto.SecureEnvelopeCipher
import com.remodex.mobile.core.model.CodexSecureTransportError
import com.remodex.mobile.core.model.CodexSecureSession
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCMessage
import com.remodex.mobile.core.model.SecureApplicationPayload
import com.remodex.mobile.core.model.SecureEnvelope
import com.remodex.mobile.core.model.codexSecureNonce
import com.remodex.mobile.core.protocol.JsonRpcCodec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import kotlin.test.assertSame
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureHistoryReceiveTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = ByteArray(32) { it.toByte() }

    @Test fun concurrentRequestsEnqueueEncryptedCountersInOrder() {
        val service = receiveService()
        val firstSending = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val counters = java.util.Collections.synchronizedList(mutableListOf<Int>())
        service.webSocket = object : WebSocket by TestWebSocket() {
            override fun send(text: String): Boolean {
                val counter = json.decodeFromString<SecureEnvelope>(text).counter
                if (counter == 0) {
                    firstSending.countDown()
                    check(releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                counters.add(counter)
                return true
            }
        }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { service.sendMessage(RPCMessage.notification("upload", null)) }
            assertTrue(firstSending.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = executor.submit { service.sendMessage(RPCMessage.notification("refresh", null)) }
            kotlin.test.assertFailsWith<java.util.concurrent.TimeoutException> {
                second.get(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            releaseFirst.countDown()
            first.get(5, java.util.concurrent.TimeUnit.SECONDS)
            second.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(listOf(0, 1), counters)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun reconnectReplayWaitsForConsumerInsteadOfClosingAtSixtyFourMessages() = runBlocking {
        val service = receiveService()
        val socket = TestWebSocket()
        service.webSocket = socket
        val inbound = Channel<String>(64)
        service.wireInbound = inbound
        val listener = service.newRelayWebSocketListener(null)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val filled = java.util.concurrent.CountDownLatch(1)
        try {
            val producer = executor.submit {
                repeat(500) { index ->
                    if (index == 64) filled.countDown()
                    listener.onMessage(socket, "replay-$index")
                }
            }
            assertTrue(filled.await(5, java.util.concurrent.TimeUnit.SECONDS))
            kotlin.test.assertFailsWith<java.util.concurrent.TimeoutException> {
                producer.get(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            kotlinx.coroutines.withTimeout(5_000) {
                repeat(500) { assertEquals("replay-$it", inbound.receive()) }
            }
            producer.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(null, socket.closeCode)
        } finally {
            inbound.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun replacingConnectionReleasesBlockedReplayReader() = runBlocking {
        val service = receiveService()
        val oldSocket = TestWebSocket()
        service.webSocket = oldSocket
        val oldInbound = Channel<String>(1)
        service.wireInbound = oldInbound
        val listener = service.newRelayWebSocketListener(null)
        listener.onMessage(oldSocket, "first")
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val producer = executor.submit { listener.onMessage(oldSocket, "second") }
            kotlin.test.assertFailsWith<java.util.concurrent.TimeoutException> {
                producer.get(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            val currentSocket = TestWebSocket()
            service.connectionGeneration++
            service.webSocket = currentSocket
            service.wireInbound = Channel(1)
            oldInbound.cancel(CancellationException("Connection generation replaced"))
            producer.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertSame(currentSocket, service.webSocket)
            assertTrue(service.wireInbound!!.tryReceive().isFailure)
            assertEquals(null, currentSocket.closeCode)
        } finally {
            oldInbound.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun obsoleteSocketCannotInjectMessagesOrReplaceCurrentSocket() = runBlocking {
        val service = receiveService()
        val oldSocket = TestWebSocket()
        val listener = service.newRelayWebSocketListener(null)
        val currentSocket = TestWebSocket()
        service.connectionGeneration++
        service.webSocket = currentSocket
        service.wireInbound = Channel(1)
        listener.onMessage(oldSocket, "stale message")
        listener.onFailure(oldSocket, java.io.IOException("late failure"), null)
        listener.onClosing(oldSocket, 1000, "late close")
        val response = okhttp3.Response.Builder().request(oldSocket.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(101).message("upgrade").build()
        listener.onOpen(oldSocket, response)
        assertSame(currentSocket, service.webSocket)
        assertTrue(service.wireInbound!!.tryReceive().isFailure)
    }

    @Test fun replacedSocketInSameGenerationCannotFeedCurrentInboundQueue() {
        val service = receiveService()
        val listener = service.newRelayWebSocketListener(null)
        service.webSocket = TestWebSocket()
        service.wireInbound = Channel(1)
        listener.onMessage(TestWebSocket(), "stale")
        assertTrue(service.wireInbound!!.tryReceive().isFailure)
    }

    @Test
    fun encryptedHistoryWithTwoPointSixMegabyteWrapperCompletesPendingRequest() = runBlocking<Unit> {
        val service = receiveService()
        val pending = pendingRequest(service)
        val prefix = "{\"id\":\"history\",\"result\":\""
        val suffix = "\"}"
        val emptySize = applicationBytes(prefix + suffix).size
        val content = "x".repeat(2_628_696 - emptySize)
        val plaintext = applicationBytes(prefix + content + suffix)
        assertEquals(2_628_696, plaintext.size)

        service.handleEncryptedEnvelope(envelope(plaintext))

        assertTrue(pending.isCompleted, "Large authenticated history response must settle its RPC")
        assertEquals(content.length, pending.await().result?.stringValue?.length)
        assertEquals(0, service.secureSession?.lastInboundCounter)
        assertTrue(service.pendingRpc.isEmpty())
    }

    @Test
    fun fourMegabyteJsonHistoryWithEscapedTextFitsAllReceiveLayers() = runBlocking<Unit> {
        val service = receiveService()
        val pending = pendingRequest(service)
        val prefix = "{\"id\":\"history\",\"result\":\""
        val suffix = "\"}"
        // Each backslash occupies two bytes in JSON, then four in the secure wrapper.
        val content = "\\\\".repeat((4 * 1024 * 1024 - prefix.length - suffix.length) / 2)
        val rpc = prefix + content + suffix
        val wire = envelope(applicationBytes(rpc))
        assertTrue(wire.encodeToByteArray().size <= MAX_INBOUND_WS_PAYLOAD_BYTES,
            "WebSocket budget must include JSON escaping, base64 and the envelope")

        val socket = TestWebSocket()
        service.webSocket = socket
        service.wireInbound = Channel(1)
        service.newRelayWebSocketListener(null).onMessage(socket, wire)
        val received = service.wireInbound!!.tryReceive().getOrNull()
        assertNotNull(received)
        service.processWireText(received)

        assertTrue(pending.isCompleted)
        assertEquals(content.length / 2, pending.await().result?.stringValue?.length)
    }

    @Test
    fun authenticationAndReplayChecksStillRejectInvalidEnvelopes() {
        val service = receiveService()
        val pending = pendingRequest(service)
        val plaintext = applicationBytes("{\"id\":\"history\",\"result\":true}")
        val valid = json.decodeFromString<SecureEnvelope>(envelope(plaintext))
        listOf(valid.copy(sessionId = "other-session"), valid.copy(keyEpoch = 2), valid.copy(sender = "iphone"), valid.copy(counter = -1)).forEach {
            service.handleEncryptedEnvelope(json.encodeToString(SecureEnvelope.serializer(), it))
            assertFalse(pending.isCompleted)
            assertEquals(-1, service.secureSession?.lastInboundCounter)
        }
        service.handleEncryptedEnvelope(json.encodeToString(SecureEnvelope.serializer(), valid.copy(tag = Base64.getEncoder().encodeToString(ByteArray(16)))))
        assertFalse(pending.isCompleted)
        assertEquals(-1, service.secureSession?.lastInboundCounter)
        service.handleEncryptedEnvelope(envelope(plaintext))
        assertTrue(pending.isCompleted)
        val nextPending = pendingRequest(service)
        service.handleEncryptedEnvelope(envelope(plaintext))
        assertFalse(nextPending.isCompleted)
    }

    @Test
    fun authenticatedPlaintextAtReceiveBoundaryCompletesRpc() = runBlocking<Unit> {
        val service = receiveService()
        val pending = pendingRequest(service)
        service.handleEncryptedEnvelope(envelope(applicationOfSize(8 * 1024 * 1024 + 512)))
        assertTrue(pending.isCompleted)
        assertNotNull(pending.await().result)
    }

    @Test
    fun authenticatedPlaintextOneByteOverBudgetSettlesPendingRpcWithSizeDiagnosis() = runBlocking<Unit> {
        val service = receiveService()
        val pending = pendingRequest(service)
        service.handleEncryptedEnvelope(envelope(applicationOfSize(8 * 1024 * 1024 + 513)))
        assertSizeFailure(service, pending, "secure_plaintext", 8 * 1024 * 1024 + 513)
        assertEquals(-1, service.secureSession?.lastInboundCounter)
    }

    @Test
    fun ciphertextOverBudgetSettlesEveryPendingRpcWithSameOriginalFailure() = runBlocking<Unit> {
        val service = receiveService()
        val pending = pendingRequest(service)
        val other = CompletableDeferred<RPCMessage>()
        service.pendingRpc["s:other"] = other
        val wire = envelope(applicationOfSize(8 * 1024 * 1024 + 515))
        val actualSize = json.decodeFromString<SecureEnvelope>(wire).ciphertext.length
        service.handleEncryptedEnvelope(wire)
        val failure = assertSizeFailure(service, pending, "secure_ciphertext", actualSize)
        assertTrue(other.isCompleted)
        assertSame(failure, runCatching { other.await() }.exceptionOrNull())
    }

    @Test
    fun oversizedWebsocketFrameFailsPendingRpcBeforeCloseCallback() = runBlocking<Unit> {
        val service = receiveService()
        val pending = pendingRequest(service)
        val socket = TestWebSocket()
        service.webSocket = socket
        service.newRelayWebSocketListener(null).onMessage(socket, "x".repeat(MAX_INBOUND_WS_PAYLOAD_BYTES + 1))
        assertEquals(1009, socket.closeCode)
        assertSizeFailure(service, pending, "websocket_frame", MAX_INBOUND_WS_PAYLOAD_BYTES + 1)
        Unit
    }

    @Test
    fun websocketFrameAtReceiveBoundaryStillCompletesRpc() = runBlocking<Unit> {
        val service = receiveService()
        val pending = pendingRequest(service)
        val socket = TestWebSocket()
        service.webSocket = socket
        service.wireInbound = Channel(1)
        val wire = envelope(applicationBytes("{\"id\":\"history\",\"result\":true}"))
            .padEnd(MAX_INBOUND_WS_PAYLOAD_BYTES, ' ')
        service.newRelayWebSocketListener(null).onMessage(socket, wire)
        service.processWireText(assertNotNull(service.wireInbound!!.tryReceive().getOrNull()))
        assertTrue(pending.isCompleted)
        assertEquals(JSONValue.Bool(true), pending.await().result)
    }

    @Test
    fun staleSocketOversizeDoesNotFailRequestsOnCurrentConnection() {
        val service = receiveService()
        val pending = pendingRequest(service)
        val staleSocket = TestWebSocket()
        service.webSocket = TestWebSocket()
        service.newRelayWebSocketListener(null).onMessage(staleSocket, "x".repeat(MAX_INBOUND_WS_PAYLOAD_BYTES + 1))
        assertEquals(null, staleSocket.closeCode)
        assertFalse(pending.isCompleted)
    }

    @Test
    fun sizeFailureDoesNotReplaceCallerCancellation() = runBlocking<Unit> {
        val service = receiveService()
        val pending = pendingRequest(service)
        pending.cancel(CancellationException("caller cancelled history"))
        val socket = TestWebSocket()
        service.webSocket = socket
        service.newRelayWebSocketListener(null).onMessage(socket, "x".repeat(MAX_INBOUND_WS_PAYLOAD_BYTES + 1))
        val failure = assertNotNull(runCatching { pending.await() }.exceptionOrNull())
        assertIs<CancellationException>(failure)
        assertEquals("caller cancelled history", failure.message)
    }

    @Test
    fun oversizedTagReportsItsOwnStageWithoutAdvancingCounter() = runBlocking<Unit> {
        val service = receiveService()
        val pending = pendingRequest(service)
        val valid = json.decodeFromString<SecureEnvelope>(envelope(applicationBytes("{\"id\":\"history\",\"result\":true}")))
        service.handleEncryptedEnvelope(json.encodeToString(SecureEnvelope.serializer(), valid.copy(tag = "x".repeat(65))))
        assertSizeFailure(service, pending, "secure_tag", 65)
        assertEquals(-1, service.secureSession?.lastInboundCounter)
    }

    private suspend fun assertSizeFailure(
        service: CodexService,
        pending: CompletableDeferred<RPCMessage>,
        stage: String,
        actualSize: Int,
    ): Throwable {
        assertTrue(pending.isCompleted, "Rejected payload must not leave RPC waiting")
        val failure = assertNotNull(runCatching { pending.await() }.exceptionOrNull())
        assertIs<CodexSecureTransportError.PayloadTooLarge>(failure)
        assertEquals("inbound_payload_too_large", failure.code)
        assertEquals(stage, failure.stage)
        assertEquals(actualSize, failure.actualSize)
        assertEquals(when (stage) {
            "secure_plaintext" -> 8_389_120
            "secure_ciphertext" -> 11_185_496
            "secure_tag" -> 64
            else -> 11_189_592
        }, failure.limitSize)
        assertEquals(if (stage == "secure_ciphertext" || stage == "secure_tag") "base64_chars" else "bytes", failure.unit)
        assertEquals(if (stage == "websocket_frame") null else 0, failure.counter)
        assertTrue(failure.stackTrace.isNotEmpty())
        assertTrue(service.pendingRpc.isEmpty())
        return failure
    }

    private fun applicationOfSize(size: Int): ByteArray {
        val prefix = "{\"id\":\"history\",\"result\":\""
        val suffix = "\"}"
        return applicationBytes(prefix + "x".repeat(size - applicationBytes(prefix + suffix).size) + suffix)
    }

    private class TestWebSocket : WebSocket {
        var closeCode: Int? = null
        override fun request() = Request.Builder().url("http://localhost/").build()
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?): Boolean { closeCode = code; return true }
        override fun cancel() = Unit
    }

    private fun applicationBytes(rpc: String): ByteArray =
        json.encodeToString(SecureApplicationPayload.serializer(), SecureApplicationPayload(payloadText = rpc)).encodeToByteArray()

    private fun envelope(plaintext: ByteArray): String {
        val (ciphertext, tag) = SecureEnvelopeCipher.seal(key, codexSecureNonce("mac", 0), plaintext)
        return json.encodeToString(SecureEnvelope.serializer(), SecureEnvelope(
            kind = "encryptedEnvelope", v = 1, sessionId = "test-session", keyEpoch = 1,
            sender = "mac", counter = 0, ciphertext = Base64.getEncoder().encodeToString(ciphertext),
            tag = Base64.getEncoder().encodeToString(tag),
        ))
    }

    private fun pendingRequest(service: CodexService): CompletableDeferred<RPCMessage> =
        CompletableDeferred<RPCMessage>().also { service.pendingRpc[idKey(JSONValue.Str("history"))] = it }

    // These tests exercise the real receive path without Android Keystore/UI initialization.
    // Only its dependencies are installed; no production test hooks or private history fixtures.
    private fun receiveService(): CodexService {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").let { it.isAccessible = true; it.get(null) }
        val service = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, CodexService::class.java) as CodexService
        fun field(name: String, value: Any) {
            CodexService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        field("json", json)
        field("jsonRpc", JsonRpcCodec(json))
        field("secureSessionLock", Any())
        field("pendingRpc", ConcurrentHashMap<String, CompletableDeferred<RPCMessage>>())
        service.secureSession = CodexSecureSession("test-session", 1, "mac", "", key, key, 0, -1, 0)
        return service
    }
}
