package com.remodex.mobile.services

import android.util.Log
import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.CodexSecureTransportError
import com.remodex.mobile.core.transport.RelaySocketClosed
import com.remodex.mobile.core.transport.validateRelayUrl
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal const val MAX_WS_PAYLOAD_BYTES = 16 * 1024 * 1024
// AES-GCM ciphertext is the plaintext length; its separate tag and envelope JSON
// (session ID, epoch, counter, field names) fit in this additional bounded allowance.
internal const val MAX_INBOUND_WS_PAYLOAD_BYTES = MAX_CIPHERTEXT_BASE64_LENGTH + 4096
private const val CLOSE_CODE_MESSAGE_TOO_LARGE = 1009

/**
 * Relay `x-role` for this app.
 *
 * The open-source relay accepts `android`, but hosted relay deployments may only accept the original
 * `iphone` mobile role. The secure transport already uses the `iphone` sender literal for phone->Mac
 * envelopes, so keep the WebSocket role on that compatibility path.
 */
internal const val RELAY_WS_ROLE_ANDROID = "iphone"
private const val REMODEX_WS_LOG_TAG = "RemodexWs"

/**
 * Mirrors [CodexService+Transport.swift](../../../../../../../../CodexMobile/CodexMobile/Services/CodexService+Transport.swift).
 */
internal suspend fun CodexService.openWebSocketAwaitOpen(
    httpUrl: okhttp3.HttpUrl,
    token: String,
    role: String?,
) {
    suspendCancellableCoroutine { cont ->
        val reqBuilder = Request.Builder().url(httpUrl)
        val resolvedRole = role?.trim().orEmpty().ifEmpty { RELAY_WS_ROLE_ANDROID }
        reqBuilder.header("x-role", resolvedRole)
        val t = token.trim()
        if (t.isNotEmpty()) {
            reqBuilder.header("Authorization", "Bearer $t")
        }
        val request = reqBuilder.build()
        Log.i(
            REMODEX_WS_LOG_TAG,
            "opening relay websocket scheme=${httpUrl.scheme} host=${httpUrl.host} role=$resolvedRole hasToken=${t.isNotEmpty()}",
        )
        val listener = newRelayWebSocketListener(handshakeCont = cont)
        val ws = httpClient.newWebSocket(request, listener)
        cont.invokeOnCancellation { ws.cancel() }
    }
}

internal fun CodexService.newRelayWebSocketListener(
    handshakeCont: CancellableContinuation<Unit>?,
): WebSocketListener {
    val svc = this
    val generation = connectionGeneration
        return object : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            if (svc.connectionGeneration != generation || handshakeCont?.isActive == false) {
                webSocket.cancel()
                return
            }
            Log.i(
                REMODEX_WS_LOG_TAG,
                "websocket open code=${response.code} sessionReady=${svc.sessionReady} queueSize=${webSocket.queueSize()}",
            )
            svc.webSocket = webSocket
            handshakeCont?.takeIf { it.isActive }?.resumeWith(Result.success(Unit))
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            if (svc.connectionGeneration != generation) return
            Log.w(
                REMODEX_WS_LOG_TAG,
                "websocket failure code=${response?.code} sessionReady=${svc.sessionReady} currentSocket=${svc.webSocket === webSocket}: ${t.javaClass.simpleName}: ${t.message}",
            )
            handshakeCont?.takeIf { it.isActive }?.resumeWith(Result.failure(t))
            if (svc.closingByClient) return
            if (svc.webSocket === webSocket) {
                svc.controlMux?.fail(t)
                svc.pendingRpc.values.forEach { it.completeExceptionally(t) }
                val msg = t.message?.trim()?.takeIf { it.isNotEmpty() } ?: t.toString()
                svc.scheduleWireDrop(msg, generation)
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (svc.connectionGeneration != generation || svc.webSocket !== webSocket) return
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_INBOUND_WS_PAYLOAD_BYTES) {
                if (svc.webSocket === webSocket) {
                    svc.rejectOversizedInbound(
                        CodexSecureTransportError.PayloadTooLarge(
                            "websocket_frame", bytes.size, MAX_INBOUND_WS_PAYLOAD_BYTES, "bytes",
                        ),
                    )
                }
                webSocket.close(CLOSE_CODE_MESSAGE_TOO_LARGE, "Message too large")
                return
            }
            Log.d(REMODEX_WS_LOG_TAG, "websocket message bytes=${bytes.size}")
            val inbound = svc.wireInbound ?: return
            // OkHttp calls this on its reader thread. Apply backpressure there so
            // reconnect replay cannot overflow the bounded queue or reorder frames.
            // Session reset closes this channel and releases a blocked reader.
            val result = inbound.trySendBlocking(text)
            if (result.isFailure && svc.connectionGeneration == generation && svc.wireInbound === inbound) {
                val error = java.io.IOException("WebSocket receive failed stage=inbound_queue", result.exceptionOrNull())
                Log.e(REMODEX_WS_LOG_TAG, "Receive failed stage=inbound_queue generation=$generation", error)
                svc.controlMux?.fail(error)
                svc.pendingRpc.values.forEach { it.completeExceptionally(error) }
                webSocket.cancel()
                svc.scheduleWireDrop(error.message!!, generation)
            }
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            if (svc.connectionGeneration != generation) { webSocket.cancel(); return }
            Log.i(
                REMODEX_WS_LOG_TAG,
                "websocket closing code=$code reason=$reason sessionReady=${svc.sessionReady} currentSocket=${svc.webSocket === webSocket}",
            )
            webSocket.close(code, reason)
            if (!svc.closingByClient && svc.webSocket === webSocket) {
                val closed = RelaySocketClosed(code, reason)
                svc.controlMux?.fail(closed)
                svc.pendingRpc.values.forEach { it.completeExceptionally(closed) }
                svc.scheduleWireDrop(closed.message.orEmpty(), generation, code)
            }
        }
    }
}

internal fun CodexService.rejectOversizedInbound(error: CodexSecureTransportError.PayloadTooLarge) {
    // An undecoded frame has no trustworthy RPC ID. Settle the requests already
    // waiting with the actual receive failure, before a socket close can cancel them.
    val waiting = pendingRpc.entries.map { it.key to it.value }
    waiting.forEach { (id, deferred) ->
        if (pendingRpc.remove(id, deferred)) {
            deferred.completeExceptionally(error)
        }
    }
    Log.w(
        REMODEX_WS_LOG_TAG,
        "${error.code} stage=${error.stage} actual=${error.actualSize} limit=${error.limitSize} " +
            "unit=${error.unit} counter=${error.counter} pendingCount=${waiting.size} " +
            "requestIds=${waiting.take(8).map { it.first }} requestIdsTruncated=${waiting.size > 8}",
        error,
    )
}

internal fun CodexService.sendRawText(text: String) {
    val ws = webSocket ?: throw CodexServiceError.Disconnected
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_WS_PAYLOAD_BYTES) {
        throw CodexServiceError.InvalidInput(
            "This payload is too large for the relay connection. Try fewer or smaller images and retry.",
        )
    }
    if (!ws.send(text)) {
        Log.w(
            REMODEX_WS_LOG_TAG,
            "websocket send returned false bytes=${bytes.size} sessionReady=$sessionReady queueSize=${ws.queueSize()}",
        )
        throw CodexServiceError.InvalidInput("WebSocket send failed")
    }
    Log.d(REMODEX_WS_LOG_TAG, "websocket send queued bytes=${bytes.size} queueSize=${ws.queueSize()}")
}

/** OkHttp HttpUrl parses only http/https; relay URLs use ws/wss. */
internal fun parseRelayHttpUrl(serverUrl: String): okhttp3.HttpUrl {
    val t = serverUrl.trim()
    return validateRelayUrl(t)?.httpUrl ?: throw CodexServiceError.InvalidServerURL(t)
}
