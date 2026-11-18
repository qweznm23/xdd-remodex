package com.remodex.mobile.core.transport

import com.remodex.mobile.core.model.SecureErrorMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json

/**
 * FIFO delivery of secure wire control kinds, matching the buffer/waiter behavior in
 * [CodexService+SecureTransport.swift](CodexMobile/CodexMobile/Services/CodexService+SecureTransport.swift).
 */
internal class SecureControlMultiplexer(
    private val json: Json,
    private val onSecureError: (SecureErrorMessage) -> Unit,
) {
    private val lock = Any()
    private var terminalFailure: Throwable? = null
    private val channels = mutableMapOf<String, Channel<String>>()

    private fun channel(kind: String): Channel<String> =
        synchronized(lock) {
            terminalFailure?.let { throw it }
            channels.getOrPut(kind) { Channel(capacity = 16) }
        }

    suspend fun receive(
        kind: String,
        timeoutMs: Long = 12_000L,
    ): String {
        val ch = channel(kind)
        return awaitConnectionStage(kind, timeoutMs) { ch.receive() }
    }

    fun offer(
        kind: String,
        raw: String,
    ) {
        if (kind == "secureError") {
            val parsed = runCatching { json.decodeFromString<SecureErrorMessage>(raw) }.getOrNull()
            synchronized(lock) {
                val existing = channels.values.toList()
                channels.clear()
                val err =
                    CodexSecureTransportErrorEvent(
                        parsed?.message ?: "Secure relay error",
                        parsed,
                    )
                existing.forEach { ch -> ch.close(err) }
            }
            if (parsed != null) {
                onSecureError(parsed)
            }
            return
        }
        channel(kind).trySend(raw)
    }

    fun fail(error: Throwable) {
        synchronized(lock) {
            terminalFailure = error
            channels.values.forEach { it.close(error) }
        }
    }

    fun reset() {
        synchronized(lock) {
            channels.values.forEach { ch -> ch.close() }
            channels.clear()
        }
    }
}

internal class CodexSecureTransportErrorEvent(
    override val message: String,
    val payload: SecureErrorMessage?,
) : Exception(message)
