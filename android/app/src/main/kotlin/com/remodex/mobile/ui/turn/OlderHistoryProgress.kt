package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageKind
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.ThreadHistoryPaginationState
import com.remodex.mobile.core.model.hasConversationContent
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Reuse the committed older cursor, yielding between pages so live messages can stop the search. */
internal suspend fun loadInitialConversationContent(
    revision: Long,
    messages: () -> List<CodexMessage>,
    pagination: () -> ThreadHistoryPaginationState?,
    loadPage: suspend () -> Unit,
) {
    val visited = mutableSetOf<JSONValue>()
    while (messages().asReversed().none { it.hasConversationContent() }) {
        val state = pagination() ?: return
        if (state.initialLoadRevision != revision) throw CancellationException("Conversation refresh superseded initial history search")
        if (!state.canLoadOlder) return
        check(visited.add(requireNotNull(state.olderCursor))) { "Initial history repeated an older cursor after ${visited.size} pages" }
        loadPage()
        kotlinx.coroutines.yield()
    }
}

/** One gesture can traverse cached/invisible pages, stopping at real older content or server EOF. */
internal suspend fun loadOlderHistoryUntilVisibleProgress(
    beforeMessageId: String?,
    messages: () -> List<CodexMessage>,
    pagination: () -> ThreadHistoryPaginationState?,
    loadPage: suspend () -> Unit,
    timeoutMillis: Long = 45_000,
) {
    val revision = pagination()?.initialLoadRevision
    val visited = mutableSetOf<JSONValue>()
    var pages = 0
    val completed = withTimeoutOrNull(timeoutMillis) {
        while (true) {
            val state = pagination()
            if (state?.initialLoadRevision != revision) throw CancellationException("Older history load superseded by refresh")
            val rows = messages()
            val boundary = olderHistoryBoundary(rows, beforeMessageId)
            if (olderConversationIndex(rows, boundary) != null) break
            if (state?.canLoadOlder != true) break
            val cursor = state.olderCursor!!
            if (!visited.add(cursor)) throw IllegalStateException("History load completed without cursor progress stage=older-history-progress pages=$pages")
            loadPage()
            pages++
        }
        true
    }
    if (completed == null) throw TimeoutException("Older history timed out stage=older-history-progress pages=$pages timeoutMs=$timeoutMillis; successfully loaded cursors are retained")
}

internal fun olderHistoryBoundary(rows: List<CodexMessage>, beforeMessageId: String?): Int =
    beforeMessageId?.let { id ->
        rows.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            ?: throw CancellationException("Older history viewport anchor was replaced")
    } ?: rows.size

private fun olderConversationIndex(rows: List<CodexMessage>, boundary: Int): Int? {
    if (boundary == 0) return null
    val laterAssistantTurns = mutableSetOf<String>()
    for (index in rows.lastIndex downTo 0) {
        val row = rows[index]
        val assistantTurn = row.turnId?.takeIf {
            it.isNotBlank() && row.role == CodexMessageRole.assistant && row.kind == CodexMessageKind.chat
        }
        // The timeline folds earlier assistant commentary into the same turn's work card.
        val folded = assistantTurn != null && !laterAssistantTurns.add(assistantTurn) &&
            row.attachments.isEmpty() && row.structuredUserInputRequest == null && !row.text.contains("![")
        if (index < boundary && !folded && row.hasConversationContent()) return index
    }
    return null
}

internal fun olderHistoryRevealStart(rows: List<CodexMessage>, boundary: Int): Int {
    val batchStart = (boundary - 80).coerceAtLeast(0)
    return olderConversationIndex(rows, boundary)?.let { minOf(batchStart, it) } ?: batchStart
}
