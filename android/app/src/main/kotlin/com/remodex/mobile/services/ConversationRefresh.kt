package com.remodex.mobile.services

import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Owns only the manual refresh deadline; caller cancellation and original failures remain intact. */
internal suspend fun refreshConversationStages(
    loadHistory: suspend () -> Unit,
    resumeSubscription: suspend () -> Unit,
    refreshTurnState: suspend () -> Unit,
    onFailure: (String, Exception) -> Unit,
    timeoutMillis: Long = 45_000L,
) {
    var stage = "history"
    try {
        val completed = withTimeoutOrNull(timeoutMillis) {
            loadHistory()
            stage = "live-subscription"
            resumeSubscription()
            stage = "turn-state"
            refreshTurnState()
            true
        }
        if (completed == null) throw TimeoutException("Conversation refresh timed out stage=$stage timeoutMs=$timeoutMillis")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(stage, failure)
        throw failure
    }
}
