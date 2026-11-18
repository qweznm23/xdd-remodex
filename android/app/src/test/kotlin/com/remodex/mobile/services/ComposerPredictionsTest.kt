package com.remodex.mobile.services

import org.junit.Assert.*
import org.junit.Test

class ComposerPredictionsTest {
    private val store = ComposerPredictions()

    @Test fun missedCompletionRequestsOnlyLatestAndDedupesLiveCompletion() {
        val check = store.beginCheck("a")
        val request = store.checked("a", check, "2")!!
        assertNull(store.completed("a", "2"))
        store.finish(request, "continue")
        assertEquals("continue", store.suggestions.value["a"]?.text)
        assertNull(store.checked("a", store.beginCheck("a"), "2"))
        store.dismiss("a", "2")
        assertNull(store.completed("a", "2"))
        assertTrue(store.suggestions.value.isEmpty())
    }

    @Test fun lateSnapshotOrNotificationCannotReplaceANewerTurn() {
        val old = store.completed("a", "old")!!
        val check = store.beginCheck("a")
        store.invalidate("a")
        val current = store.completed("a", "new")!!
        assertNull(store.checked("a", check, "old"))
        store.finish(old, "old")
        store.finish(current, "new")
        assertEquals("new", store.suggestions.value["a"]?.text)
    }

    @Test fun recheckHidesUnverifiedResultAndLeavesOtherConversationsAlone() {
        val a = store.completed("a", "1")!!
        store.finish(store.completed("b", "2")!!, "B")
        val check = store.beginCheck("a")
        store.finish(a, "A")
        assertEquals(setOf("b"), store.suggestions.value.keys)
        store.checked("a", check, "1")
        assertEquals(setOf("a", "b"), store.suggestions.value.keys)
        store.checked("a", store.beginCheck("a"), null)
        assertEquals(setOf("b"), store.suggestions.value.keys)
    }

    @Test fun typingWhilePredictionIsPendingKeepsLateResultCollapsedForManualReview() {
        val a = store.completed("a", "1")!!
        val b = store.completed("b", "1")!!
        store.typing("a")
        store.finish(a, "too late")
        store.finish(b, "B")
        assertEquals("too late", store.suggestions.value["a"]?.text)
        assertTrue(store.suggestions.value.getValue("a").collapsedByTyping)
        assertFalse(store.suggestions.value.getValue("b").collapsedByTyping)
        assertNull(store.completed("a", "1"))
    }

    @Test fun typingWhileCheckingHistoryCollapsesOnlyThatTurn() {
        val check = store.beginCheck("a")
        store.typing("a")
        store.finish(store.checked("a", check, "one")!!, "A")
        assertTrue(store.suggestions.value.getValue("a").collapsedByTyping)
        store.finish(store.completed("a", "two")!!, "B")
        assertFalse(store.suggestions.value.getValue("a").collapsedByTyping)
    }

    @Test fun oldCompletionAfterNewStartDoesNotTriggerSampling() {
        store.started("a", "new")
        assertNull(store.completed("a", "old"))
        assertNotNull(store.completed("a", "new"))
    }

    @Test fun reconnectReadsTheServerCacheEvenWhenTheHostStayedTheSame() {
        store.finish(store.completed("a", "1")!!, "old account")
        val unfinished = store.completed("b", "2")!!
        store.disconnected()
        assertTrue(store.suggestions.value.isEmpty())
        assertNotNull(store.checked("a", store.beginCheck("a"), "1"))
        assertNotNull(store.completed("b", "2"))
        store.finish(unfinished, "stale")
        assertTrue(store.suggestions.value.isEmpty())
    }

    @Test fun reopeningRetriesFailedReadButNotAnExplicitSkip() {
        store.fail(store.completed("a", "1")!!)
        val retry = store.checked("a", store.beginCheck("a"), "1")!!
        store.finish(retry, null)
        assertNull(store.checked("a", store.beginCheck("a"), "1"))
    }
}
