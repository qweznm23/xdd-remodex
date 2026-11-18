package com.remodex.mobile.services

import java.io.IOException
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import com.remodex.mobile.data.MessageTimelineStore
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageRole
import java.time.Instant

class ConversationRefreshTest {
    @Test
    fun loadingIndicatorBelongsToTheCurrentRequestAndSurvivesStaleCleanup() {
        val requests = ThreadHistoryRequests()
        val old = requests.begin("thread", false)!!
        assertEquals(setOf("thread"), requests.activeThreadIds.value)
        val current = requests.begin("thread", true)!!
        requests.finish("thread", old)
        assertEquals(setOf("thread"), requests.activeThreadIds.value)
        requests.finish("thread", current)
        assertEquals(emptySet(), requests.activeThreadIds.value)
        requests.begin("a", false)
        requests.begin("b", false)
        requests.remove("a")
        assertEquals(setOf("b"), requests.activeThreadIds.value)
        requests.clear()
        assertEquals(emptySet(), requests.activeThreadIds.value)
    }

    @Test
    fun supersededHistoryCannotChangeTheVisibleTimeline() = runTest {
        val requests = ThreadHistoryRequests()
        val store = MessageTimelineStore()
        val stale = requests.begin("thread", false)!!
        val current = requests.begin("thread", true)!!
        val message = CodexMessage(id = "message", threadId = "thread", role = CodexMessageRole.assistant,
            text = "current", createdAt = Instant.EPOCH)
        store.mergeThreadHistory("thread", listOf(message), requireCurrentRequest = { requests.requireCurrent("thread", current) })
        val before = store.messagesByThread.value
        assertFailsWith<CancellationException> {
            store.mergeThreadHistory("thread", listOf(message.copy(text = "stale")),
                requireCurrentRequest = { requests.requireCurrent("thread", stale) })
        }
        assertSame(before, store.messagesByThread.value)
    }

    @Test
    fun manualRefreshSupersedesStalledHistoryAndRejectsLateResponseAndCleanup() {
        val requests = ThreadHistoryRequests()
        val old = requests.begin("thread", false)!!
        assertNull(requests.begin("thread", false))
        val refresh = requests.begin("thread", true)!!
        assertFailsWith<CancellationException> { requests.requireCurrent("thread", old) }
        requests.finish("thread", old)
        requests.requireCurrent("thread", refresh)
        assertNull(requests.begin("thread", false))
        requests.finish("thread", refresh)
        assertTrue(requests.begin("thread", false) != null)
    }

    @Test
    fun disconnectOrThreadRemovalInvalidatesOutstandingRequests() {
        val requests = ThreadHistoryRequests()
        val a = requests.begin("a", false)!!
        val b = requests.begin("b", false)!!
        requests.remove("a")
        assertFalse(requests.isCurrent("a", a))
        requests.requireCurrent("b", b)
        requests.clear()
        assertFalse(requests.isCurrent("b", b))
    }

    @Test
    fun refreshCompletesHistorySubscriptionAndTurnStateInOrder() = runTest {
        val stages = mutableListOf<String>()
        refreshConversationStages(
            loadHistory = { stages += "history" },
            resumeSubscription = { stages += "resume" },
            refreshTurnState = { stages += "state" },
            onFailure = { _, error -> throw AssertionError(error) },
        )
        assertEquals(listOf("history", "resume", "state"), stages)
    }

    @Test
    fun refreshDeadlineReportsStageAndReleasesRequestSlot() = runTest {
        val requests = ThreadHistoryRequests()
        var recordedStage: String? = null
        var recorded: Exception? = null
        var resumed = false
        val failure = assertFailsWith<TimeoutException> {
            refreshConversationStages(
                loadHistory = {
                    val ticket = requests.begin("a", true)!!
                    try { delay(10_000) } finally { requests.finish("a", ticket) }
                },
                resumeSubscription = { resumed = true },
                refreshTurnState = {},
                onFailure = { stage, error -> recordedStage = stage; recorded = error },
                timeoutMillis = 100,
            )
        }
        assertEquals("history", recordedStage)
        assertSame(failure, recorded)
        assertTrue(failure.message!!.contains("timeoutMs=100"))
        assertFalse(resumed)
        assertTrue(requests.begin("a", false) != null)
    }

    @Test
    fun subscriptionFailurePreservesCauseSuppressedAndStage() = runTest {
        val cause = IOException("socket reset")
        val failure = IOException("subscription failed", cause).apply { addSuppressed(IOException("close failed")) }
        var recorded: Exception? = null
        val actual = assertFailsWith<IOException> {
            refreshConversationStages({}, { throw failure }, { error("must not continue") },
                { stage, error -> assertEquals("live-subscription", stage); recorded = error })
        }
        // Coroutine stack recovery may copy the top exception, retaining the original as its cause.
        val chain = generateSequence(actual as Throwable?) { it.cause }.toList()
        assertTrue(chain.any { it === failure })
        assertTrue(chain.any { it === cause })
        assertSame(actual, recorded)
        assertEquals("close failed", chain.first { it === failure }.suppressed.single().message)
        assertTrue(failure.stackTrace.isNotEmpty())
    }

    @Test
    fun callerCancellationIsNotTimeoutOrFailure() = runTest {
        var recorded = false
        val cancelled = CancellationException("leaving conversation")
        val actual = assertFailsWith<CancellationException> {
            refreshConversationStages({}, { throw cancelled }, {}, { _, _ -> recorded = true })
        }
        assertEquals(cancelled.message, actual.message)
        assertTrue(generateSequence(actual as Throwable?) { it.cause }.any { it === cancelled })
        assertFalse(recorded)
    }
}
