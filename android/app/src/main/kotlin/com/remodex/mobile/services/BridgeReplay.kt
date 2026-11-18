package com.remodex.mobile.services

import android.util.Log
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal sealed interface BridgeReplayEvent {
    data class Reset(val epoch: String, val sequence: Int) : BridgeReplayEvent
    data class Gap(val through: Int) : BridgeReplayEvent
    data object HistoricalTimeline : BridgeReplayEvent
    data object Completed : BridgeReplayEvent
}

// The caller decrypts and authenticates the envelope before interpreting these controls.
internal fun bridgeReplayEvent(rpc: RPCMessage): BridgeReplayEvent? {
    if (rpc.id != null) return null
    val method = rpc.method ?: return null
    val params = rpc.params?.objectValue ?: return null
    fun sequence(key: String): Int {
        val value = params[key]?.longValue
        require(value != null && value in 0..Int.MAX_VALUE.toLong()) { "Invalid replay sequence in $method/$key" }
        return value.toInt()
    }
    return when (method) {
        "remodex/bufferedReplay/reset" -> {
            val epoch = params["bridgeReplayEpoch"]?.stringValue
            require(!epoch.isNullOrBlank()) { "Missing bridgeReplayEpoch in replay reset" }
            BridgeReplayEvent.Reset(epoch, sequence("resetBridgeOutboundSeqTo"))
        }
        "remodex/bufferedReplay/gap" -> BridgeReplayEvent.Gap(sequence("lastDiscardedBridgeOutboundSeq"))
        "remodex/bufferedReplay/completed" -> BridgeReplayEvent.Completed
        else -> if (params["remodexReplayedEvent"] == JSONValue.Bool(true) &&
            (method.startsWith("item/") || method.startsWith("turn/") || method.startsWith("codex/event") ||
                method == "thread/status/changed")) BridgeReplayEvent.HistoricalTimeline else null
    }
}

internal fun CodexService.handleBridgeReplay(event: BridgeReplayEvent?): Boolean {
    if (event == null) return false
    // A partial stream cannot reconstruct a reliable item; read bounded native
    // history once per burst. Live events keep flowing through the normal router.
    if (event != BridgeReplayEvent.Completed) replayHistoryInvalidated = true
    if (event is BridgeReplayEvent.Reset || event == BridgeReplayEvent.Completed) {
        if (!replayHistoryInvalidated) return true
        replayHistoryInvalidated = false
        loadingHistory.clear()
        hydratedThreadIds.clear()
        _threadHistoryPaginationByThread.value = emptyMap()
        // connectImpl performs this sync itself after initialize if replay came first.
        if (sessionReady) {
            val generation = connectionGeneration
            val tid = activeThreadId.value
            scope.launch {
                if (generation != connectionGeneration || !sessionReady) return@launch
                if (tid != null && activeThreadId.value == tid) catchUpThreadAfterSelectionOrReconnect(tid)
                if (generation != connectionGeneration || !sessionReady) return@launch
                try {
                    refreshThreadsInternal()
                    refreshInactiveRunningThreadStatesInternal()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("RemodexConnection", "Replay reconciliation failed stage=thread-list generation=$generation", error)
                }
            }
        }
    }
    return true
}
