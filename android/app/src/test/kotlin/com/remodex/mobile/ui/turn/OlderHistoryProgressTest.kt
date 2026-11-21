package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.*
import com.remodex.mobile.data.HistoryMessageMerge
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeoutException
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class OlderHistoryProgressTest {
    @Test fun openingToolHeavyTurnFindsAndDisplaysConversationWithoutAGesture() = runTest {
        fun activity(i: Int) = row(i).copy(role = CodexMessageRole.system,
            kind = if (i % 2 == 0) CodexMessageKind.commandExecution else CodexMessageKind.thinking)
        var rows = (91..120).map(::activity)
        var page = state("90")
        val cursors = mutableListOf<Int>()
        loadInitialConversationContent(1, { rows }, { page }) {
            val last = page.olderCursor!!.stringValue!!.toInt()
            cursors += last
            if (last == 0) {
                rows = listOf(row(0)) + rows
                page = state("earlier-turn")
            } else {
                rows = (last - 29..last).map(::activity) + rows
                page = state((last - 30).toString())
            }
        }
        assertEquals(listOf(90, 60, 30, 0), cursors)
        assertEquals("earlier-turn", page.olderCursor?.stringValue)
        assertEquals("row-0", rows[timelineWindowStartIndex(rows, null, 48, true)].id)
        assertEquals("row-0", rows[timelineWindowStartIndex(rows, "row-91", 48, false)].id)
        loadInitialConversationContent(1, { rows }, { page }) { fail("cached conversation should open immediately") }
    }

    @Test fun openingWithoutDialoguePreservesRpcFailureAndHonorsRefreshAndEof() = runTest {
        val cause = IOException("original RPC failure")
        var page = state("next")
        assertSame(cause, assertFailsWith<IOException> {
            loadInitialConversationContent(1, { emptyList() }, { page }) { throw cause }
        })
        var calls = 0
        assertFailsWith<CancellationException> {
            loadInitialConversationContent(1, { emptyList() }, { page }) {
                calls++
                page = page.copy(initialLoadRevision = 2)
            }
        }
        assertEquals(1, calls)
        loadInitialConversationContent(1, { emptyList() }, { state(null) }) { fail("end of history") }
        assertFailsWith<IllegalStateException> {
            loadInitialConversationContent(1, { emptyList() }, { state("repeated") }) { }
        }
    }
    private fun row(i: Int) = CodexMessage(id = "row-$i", threadId = "thread", role = CodexMessageRole.user,
        text = "$i", itemId = "item-$i", turnId = "turn", createdAt = Instant.EPOCH)
    private fun state(cursor: String?) = ThreadHistoryPaginationState(olderCursor = cursor?.let(JSONValue::Str), initialTurnsLoaded = true, initialLoadRevision = 1, usesItemPagination = true)

    @Test fun oneGestureTraversesAlreadyCachedPagesAndRevealsTheFirstNewPrefix() = runTest {
        var rows = (10..39).map(::row)
        var page = state("repeat-30")
        val requested = mutableListOf<String>()
        loadOlderHistoryUntilVisibleProgress(rows.first().id, { rows }, { page }, {
            val cursor = page.olderCursor!!.stringValue!!
            requested += cursor
            val (range, next) = when (cursor) {
                "repeat-30" -> (30..39) to "repeat-20"
                "repeat-20" -> (20..29) to "repeat-10"
                "repeat-10" -> (10..19) to "new-0"
                "new-0" -> (0..9) to "still-older"
                else -> error("Unnecessary extra request")
            }
            rows = HistoryMessageMerge.mergeItemPage(rows, range.map(::row), older = true, beforeMessageId = rows.first().id)
            page = state(next)
        })
        assertEquals(listOf("repeat-30", "repeat-20", "repeat-10", "new-0"), requested)
        assertEquals((0..39).map { "row-$it" }, rows.map { it.id })
        assertEquals(JSONValue.Str("still-older"), page.olderCursor)
    }

    @Test fun invisiblePagesContinueUntilVisibleOlderContentOrAuthoritativeEnd() = runTest {
        var rows = listOf(row(10))
        var page = state("thinking")
        var calls = 0
        loadOlderHistoryUntilVisibleProgress("row-10", { rows }, { page }, {
            calls++
            if (calls == 1) {
                rows = listOf(row(9).copy(role = CodexMessageRole.system, kind = CodexMessageKind.thinking)) + rows
                page = state("end")
            } else page = state(null)
        })
        assertEquals(2, calls)
        assertFalse(page.canLoadOlder)
    }

    @Test fun failedPagePreservesOriginalCauseAndCommittedCursorWithoutAutomaticRetry() = runTest {
        val cause = IOException("connection reset")
        val failure = IOException("older page failed", cause).apply { addSuppressed(IOException("cleanup failed")) }
        var page = state("duplicate")
        var calls = 0
        val actual = assertFailsWith<IOException> {
            loadOlderHistoryUntilVisibleProgress("row-10", { listOf(row(10)) }, { page }, {
                if (++calls == 1) page = state("failing-page") else throw failure
            })
        }
        val chain = generateSequence(actual as Throwable?) { it.cause }.toList()
        assertTrue(chain.any { it === failure })
        assertTrue(chain.any { it === cause })
        assertEquals("cleanup failed", failure.suppressed.single().message)
        assertEquals(JSONValue.Str("failing-page"), page.olderCursor)
        assertEquals(2, calls)
    }

    @Test fun progressDeadlineRetainsCursorAndCancelsOutstandingRequest() = runTest {
        var page = state("duplicate")
        var calls = 0
        var requestReleased = false
        val failure = assertFailsWith<TimeoutException> {
            loadOlderHistoryUntilVisibleProgress("row-10", { listOf(row(10)) }, { page }, {
                if (++calls == 1) page = state("next")
                else try { delay(10_000) } finally { requestReleased = true }
            }, timeoutMillis = 100)
        }
        assertEquals(JSONValue.Str("next"), page.olderCursor)
        assertTrue(requestReleased)
        assertTrue(failure.message!!.contains("stage=older-history-progress pages=1"))
    }

    @Test fun repeatedCursorFailsInsteadOfSpinningOrPretendingContentWasLoaded() = runTest {
        var calls = 0
        val failure = assertFailsWith<IllegalStateException> {
            loadOlderHistoryUntilVisibleProgress("row-10", { listOf(row(10)) }, { state("same") }, { calls++ })
        }
        assertEquals(1, calls)
        assertTrue(failure.message!!.contains("stage=older-history-progress"))
    }

    @Test fun callerCancellationAndRefreshSupersessionStopFurtherPages() = runTest {
        var page = state("next")
        var calls = 0
        assertFailsWith<CancellationException> {
            loadOlderHistoryUntilVisibleProgress("row-10", { listOf(row(10)) }, { page }, {
                calls++
                page = page.copy(initialLoadRevision = 2)
            })
        }
        assertEquals(1, calls)
        assertFailsWith<CancellationException> {
            loadOlderHistoryUntilVisibleProgress("row-10", { listOf(row(10)) }, { page }, { throw CancellationException("left thread") })
        }
    }
}
