package com.remodex.mobile.ui.agent

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.services.fetchThreadHistoryPage
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.format.DateTimeParseException

internal data class TurnActivityTiming(
    val start: Long? = null,
    val end: Long? = null,
    val duration: Long? = null,
) {
    val finalDuration: Long? get() = duration ?: if (start != null && end != null && end >= start) end - start else null

    // Snapshots and lifecycle events can arrive in either order. A partial replay must
    // never clear an already recorded completion.
    fun supplementedBy(other: TurnActivityTiming) =
        TurnActivityTiming(other.start ?: start, other.end ?: end, other.duration ?: duration)

    companion object {
        fun decode(turn: Map<String, JSONValue>): TurnActivityTiming {
            val start = epochMillis(turn["startedAt"])
            if (turn["status"]?.stringValue == "inProgress") return TurnActivityTiming(start = start)
            return TurnActivityTiming(start, epochMillis(turn["completedAt"]),
                turn["durationMs"]?.longValue?.takeIf { it >= 0 })
        }

        private fun epochMillis(value: JSONValue?): Long? {
            val n = value?.longValue
            if (n != null) return when {
                n <= 0 -> null
                n < 100_000_000_000L -> n * 1000
                else -> n
            }
            val text = value?.stringValue ?: return null
            return try { Instant.parse(text).toEpochMilli() } catch (_: DateTimeParseException) { null }
        }
    }
}

private class TimingPageWindow {
    var headLoaded = false
    var cursor: JSONValue? = null
    val seen = mutableSetOf<String>()
}

/** Only read turn headers covering the loaded message window. Never page the transcript for a clock. */
@Composable
internal fun rememberActivityTimings(
    repository: CodexRepository, device: String?, thread: String, ready: Boolean,
    activeTurn: String?, messages: List<CodexMessage>, historyRevision: Long,
): Map<String, TurnActivityTiming> {
    val times = remember(repository, device, thread) { mutableStateMapOf<String, TurnActivityTiming>() }
    fun record(turn: Map<String, JSONValue>) {
        val id = turn["id"]?.stringValue ?: turn["turnId"]?.stringValue ?: return
        val incoming = TurnActivityTiming.decode(turn)
        times[id] = times[id]?.supplementedBy(incoming) ?: incoming
    }
    LaunchedEffect(times, repository, thread, ready) {
        if (ready) repository.conversationEvents.collect { (method, params) ->
            if ((method == "turn/started" || method == "turn/completed") && params["threadId"]?.stringValue == thread) {
                record(params["turn"]?.objectValue ?: params)
            }
        }
    }
    // A reconnect, explicit history refresh or active-turn transition can change the newest header.
    val window = remember(times, ready, historyRevision, activeTurn) { TimingPageWindow() }
    val first = messages.firstNotNullOfOrNull { it.turnId }
    val last = messages.lastOrNull { it.turnId != null }?.turnId
    LaunchedEffect(window, first, last) {
        if (!ready || (first == null && activeTurn == null)) return@LaunchedEffect
        val loadedTurnCount = messages.mapNotNullTo(mutableSetOf()) { it.turnId }.size
        try {
            // Limits also bound reads if a legacy/synthetic turn ID has no native counterpart.
            var remaining = loadedTurnCount.coerceAtLeast(1)
            while (remaining > 0 && (!window.headLoaded || (window.cursor != null && first !in window.seen))) {
                val page = fetchThreadHistoryPage(thread, window.cursor, limit = 16, itemsView = "notLoaded",
                    request = repository::sendRequest)
                for (row in page.turns) {
                    val turn = row.objectValue ?: continue
                    turn["id"]?.stringValue?.let(window.seen::add)
                    record(turn)
                }
                window.headLoaded = true
                window.cursor = page.nextCursor
                if (page.turns.isEmpty()) break
                remaining -= page.turns.size
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            Log.w("RemodexTiming", "thread/turns/list activity headers thread=$thread", failure)
        }
    }
    return times
}
