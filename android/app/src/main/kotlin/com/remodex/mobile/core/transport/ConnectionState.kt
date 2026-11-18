package com.remodex.mobile.core.transport

/** High-level relay + JSON-RPC session lifecycle for UI and coordinators. */
sealed class ConnectionState {
    data object Offline : ConnectionState()

    data object Connecting : ConnectionState()

    /** WebSocket is up, secure handshake finished, and `initialize` completed. */
    data object Connected : ConnectionState()

    data class Error(
        val message: String,
        val relayCloseCode: Int? = null,
    ) : ConnectionState()
}

internal class RelaySocketClosed(val code: Int, reason: String) :
    java.io.IOException("WebSocket closed ($code): $reason")

// Relay close codes for an unavailable room or a mobile send during bridge absence.
internal fun isBridgeUnavailableClose(code: Int?) = code == 4002 || code == 4004
