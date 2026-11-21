package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageKind
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
            val boundary = if (beforeMessageId == null) rows.size else rows.indexOfFirst { it.id == beforeMessageId }
            if (boundary > 0 && rows.take(boundary).any { it.kind != CodexMessageKind.thinking }) break
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
