package com.remodex.mobile.pairing

import com.remodex.mobile.core.model.CodexTrustedSessionResolveError
import com.remodex.mobile.core.transport.RelaySocketClosed
import com.remodex.mobile.core.transport.isBridgeUnavailableClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.ConnectException
import java.net.SocketTimeoutException

private val bridgeRecoveryDelays = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L)

private fun temporaryBridgeFailure(error: Exception): Boolean = when (error) {
    is CodexTrustedSessionResolveError.MacOffline -> true
    is RelaySocketClosed -> isBridgeUnavailableClose(error.code)
    is ConnectException, is SocketTimeoutException -> true
    is CodexTrustedSessionResolveError.Network -> error.cause is ConnectException || error.cause is SocketTimeoutException
    else -> false
}

// Retry discovery + connect together: the bridge can return in a different relay room.
// No chat request or account switch is replayed.
internal suspend fun retryTemporaryBridgeAbsence(connect: suspend (Int) -> Unit) {
    val failures = mutableListOf<Exception>()
    for (index in 0..bridgeRecoveryDelays.size) {
        try {
            connect(index)
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!temporaryBridgeFailure(error) || index == bridgeRecoveryDelays.size) {
                failures.filter { it !== error }.forEach(error::addSuppressed)
                throw error
            }
            failures.add(error)
        }
        delay(bridgeRecoveryDelays[index])
    }
}
