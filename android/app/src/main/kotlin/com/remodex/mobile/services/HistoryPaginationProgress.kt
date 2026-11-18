package com.remodex.mobile.services

import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.ThreadHistoryPaginationState

internal fun historyPaginationAfterPage(
    previous: ThreadHistoryPaginationState,
    page: ThreadHistoryPage,
    oldestTurnId: String?,
    oldestMessageId: String?,
    initialLoad: Boolean,
    revision: Long = previous.initialLoadRevision,
    activityRevision: String? = null,
): ThreadHistoryPaginationState {
    // Refreshing the newest window does not undo pages already loaded at the older boundary.
    // A complete server snapshot or a pagination protocol change establishes a new boundary.
    val keepOlderBoundary = initialLoad && previous.initialTurnsLoaded &&
        previous.usesItemPagination == page.itemPage && page.nextCursor != null
    return previous.copy(
        olderCursor = if (keepOlderBoundary) previous.olderCursor else page.nextCursor,
        exhaustedOlderCursor = if (keepOlderBoundary) previous.exhaustedOlderCursor else if (page.nextCursor == null) JSONValue.Null else null,
        hasAuthoritativeLocalHistoryStart = if (keepOlderBoundary) previous.hasAuthoritativeLocalHistoryStart else page.nextCursor == null,
        initialTurnsLoaded = initialLoad || previous.initialTurnsLoaded,
        initialLoadRevision = if (initialLoad) revision else previous.initialLoadRevision,
        loadedActivityRevision = if (initialLoad) activityRevision else previous.loadedActivityRevision,
        olderPageAnchorTurnId = if (keepOlderBoundary) previous.olderPageAnchorTurnId else oldestTurnId ?: previous.olderPageAnchorTurnId,
        usesItemPagination = page.itemPage,
        olderPageAnchorMessageId = if (keepOlderBoundary) previous.olderPageAnchorMessageId else oldestMessageId ?: previous.olderPageAnchorMessageId,
        lastPageItemKeys = if (keepOlderBoundary) previous.lastPageItemKeys else page.itemKeys,
    )
}

/** Reconnect catch-up walks from the newest page without borrowing the user's older cursor. */
internal suspend fun walkRecentHistoryGap(
    startCursor: JSONValue?,
    previousTail: Pair<String, String>,
    requireCurrent: () -> Unit,
    fetch: suspend (JSONValue) -> ThreadHistoryPage,
    merge: suspend (ThreadHistoryPage) -> Unit,
) {
    var cursor = startCursor
    val visited = mutableSetOf<JSONValue>()
    while (cursor != null && cursor != JSONValue.Null) {
        requireCurrent()
        if (!visited.add(cursor)) throw com.remodex.mobile.core.error.CodexServiceError.InvalidResponse("Recent history gap repeated a cursor")
        val page = fetch(cursor)
        requireCurrent()
        merge(page)
        if (previousTail in page.itemKeys) return
        cursor = page.nextCursor
    }
}
