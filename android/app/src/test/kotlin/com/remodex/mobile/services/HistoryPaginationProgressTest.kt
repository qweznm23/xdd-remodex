package com.remodex.mobile.services

import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.ThreadHistoryPaginationState
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class HistoryPaginationProgressTest {
    private fun page(cursor: String?) = ThreadHistoryPage(emptyList(), cursor?.let(JSONValue::Str), itemPage = true)

    @Test fun provisionalEmptyStateDoesNotDiscardFirstRealTurnCursor() {
        val provisional = ThreadHistoryPaginationState(initialTurnsLoaded = true, waitingForFirstMessage = true)
        val actual = historyPaginationAfterPage(provisional, page("older").copy(itemPage = false), "a", "b", true)
        assertTrue(actual.canLoadOlder)
        assertFalse(actual.waitingForFirstMessage)
        assertEquals(JSONValue.Str("older"), actual.olderCursor)
    }

    @Test fun refreshThenFirstLoadMoreUsesTheFurthestCursorNotTheFirstPageAgain() {
        val initial = historyPaginationAfterPage(ThreadHistoryPaginationState(), page("page-2"), "turn-90", "row-90", true, 1)
        val older = historyPaginationAfterPage(initial, page("page-3"), "turn-60", "row-60", false)
        val oldest = historyPaginationAfterPage(older, page("page-4"), "turn-30", "row-30", false)
        val refreshed = historyPaginationAfterPage(oldest, page("page-2"), "turn-90", "row-90", true, 2, "latest-activity")
        assertEquals(JSONValue.Str("page-4"), refreshed.olderCursor)
        assertEquals("row-30", refreshed.olderPageAnchorMessageId)
        assertEquals("turn-30", refreshed.olderPageAnchorTurnId)
        assertEquals(2L, refreshed.initialLoadRevision)
        assertEquals("latest-activity", refreshed.loadedActivityRevision)
    }

    @Test fun refreshDoesNotResurrectLoadMoreAfterHistoryWasExhausted() {
        val initial = historyPaginationAfterPage(ThreadHistoryPaginationState(), page("page-2"), "turn-90", "row-90", true, 1)
        val exhausted = historyPaginationAfterPage(initial, page(null), "start", "start-row", false)
        val refreshed = historyPaginationAfterPage(exhausted, page("page-2"), "turn-90", "row-90", true, 2)
        assertFalse(refreshed.canLoadOlder)
        assertTrue(refreshed.hasAuthoritativeLocalHistoryStart)
        assertEquals("start-row", refreshed.olderPageAnchorMessageId)
    }

    @Test fun protocolChangeOrCompleteServerSnapshotEstablishesANewBoundary() {
        val old = historyPaginationAfterPage(ThreadHistoryPaginationState(), page("old"), "t", "r", true, 1)
        val complete = historyPaginationAfterPage(old, page(null), "start", "start-row", true, 2)
        assertFalse(complete.canLoadOlder)
        assertEquals("start-row", complete.olderPageAnchorMessageId)
        val fallback = historyPaginationAfterPage(old, page("turn-cursor").copy(itemPage = false), "turn-anchor", "row-anchor", true, 3)
        assertEquals(JSONValue.Str("turn-cursor"), fallback.olderCursor)
        assertFalse(fallback.usesItemPagination)
        assertEquals("row-anchor", fallback.olderPageAnchorMessageId)
    }

    @Test fun ordinaryOlderLoadsContinueAdvancingAndNewRowsDoNotResetProgress() {
        val first = historyPaginationAfterPage(ThreadHistoryPaginationState(), page("2"), "a", "a", true, 1)
        val second = historyPaginationAfterPage(first, page("3"), "b", "b", false)
        val refresh = historyPaginationAfterPage(second, page("newest-gap"), "new", "new", true, 2)
        assertEquals(JSONValue.Str("3"), refresh.olderCursor)
        val third = historyPaginationAfterPage(refresh, page("4"), "c", "c", false)
        assertEquals(JSONValue.Str("4"), third.olderCursor)
        assertEquals(2L, third.initialLoadRevision)
    }

    private fun gapPage(cursor: String?, item: String) = page(cursor).copy(turns = listOf(JSONValue.Obj(mapOf(
        "id" to JSONValue.Str("turn"), "items" to JSONValue.Arr(listOf(JSONValue.Obj(mapOf("id" to JSONValue.Str(item))))),
    ))))

    @Test fun reconnectGapWalksItsOwnNewerCursorAndStopsAtCachedTail() = runTest {
        val requested = mutableListOf<JSONValue>()
        val merged = mutableListOf<Set<Pair<String, String>>>()
        walkRecentHistoryGap(JSONValue.Str("gap-1"), "turn" to "cached", {},
            fetch = { cursor ->
                requested += cursor
                if (cursor == JSONValue.Str("gap-1")) gapPage("gap-2", "missing") else gapPage("older-cache", "cached")
            }, merge = { merged += it.itemKeys })
        assertEquals(listOf<JSONValue>(JSONValue.Str("gap-1"), JSONValue.Str("gap-2")), requested)
        assertEquals(listOf(setOf("turn" to "missing"), setOf("turn" to "cached")), merged)
    }

    @Test fun supersededGapResponseCannotMergeAndRpcFailuresAreNotRetried() = runTest {
        var current = true
        var merged = false
        assertFailsWith<CancellationException> {
            walkRecentHistoryGap(JSONValue.Str("gap"), "turn" to "cached",
                requireCurrent = { if (!current) throw CancellationException("superseded") },
                fetch = { current = false; gapPage(null, "late") }, merge = { merged = true })
        }
        assertFalse(merged)
        val failure = java.io.IOException("original RPC transport cause")
        var calls = 0
        val actual = assertFailsWith<java.io.IOException> {
            walkRecentHistoryGap(JSONValue.Str("gap"), "turn" to "cached", {},
                fetch = { calls++; throw failure }, merge = { merged = true })
        }
        assertSame(failure, actual)
        assertEquals(1, calls)
        assertFalse(merged)
    }
}
