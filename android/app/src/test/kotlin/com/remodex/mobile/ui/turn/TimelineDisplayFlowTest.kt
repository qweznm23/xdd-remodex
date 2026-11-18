package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.data.MessageTimelineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineDisplayFlowTest {
    @Test
    fun thousandDeltasRenderAtBoundedRateAndIncludeFinalCompletion() = runTest {
        val store = MessageTimelineStore()
        val displayed = mutableListOf<List<CodexMessage>>()
        backgroundScope.launch { store.messagesByThread.threadDisplayFlow("thread").collect { displayed += it } }
        runCurrent()
        assertEquals(emptyList(), displayed.single())
        val expected = buildString { repeat(1_000) { append("中文$it ") } }
        repeat(1_000) {
            store.appendAssistantDelta("thread", "turn", "item", "中文$it ")
            advanceTimeBy(1)
        }
        assertEquals(expected, store.messagesByThread.value["thread"]!!.single().text)
        store.completeAssistantMessage("thread", "turn", "item", expected)
        advanceTimeBy(81)
        runCurrent()
        assertEquals(expected, displayed.last().single().text)
        assertFalse(displayed.last().single().isStreaming)
        assertEquals(store.messagesByThread.value["thread"], displayed.last())
        assertTrue(displayed.size in 20..23, "Expected bounded refreshes, got ${displayed.size}")
    }

    @Test
    fun otherThreadsDoNotRefreshCurrentConversation() = runTest {
        val store = MessageTimelineStore()
        store.appendAssistantDelta("thread", "turn", "item", "existing")
        val displayed = mutableListOf<List<CodexMessage>>()
        backgroundScope.launch { store.messagesByThread.threadDisplayFlow("thread").collect { displayed += it } }
        runCurrent()
        assertEquals("existing", displayed.single().single().text)
        advanceTimeBy(81)
        runCurrent()
        val initialCount = displayed.size
        repeat(100) {
            store.appendAssistantDelta("other", "other-turn", "other-item", "delta")
            advanceTimeBy(10)
        }
        advanceTimeBy(81)
        runCurrent()
        assertEquals(initialCount, displayed.size)
    }

    @Test
    fun resumedCollectorImmediatelyShowsLatestCanonicalState() = runTest {
        val store = MessageTimelineStore()
        store.appendAssistantDelta("thread", "turn", "item", "before")
        val first = backgroundScope.launch { store.messagesByThread.threadDisplayFlow("thread").collect {} }
        runCurrent()
        first.cancel()
        store.appendAssistantDelta("thread", "turn", "item", " after")
        val resumed = mutableListOf<List<CodexMessage>>()
        backgroundScope.launch { store.messagesByThread.threadDisplayFlow("thread").collect { resumed += it } }
        runCurrent()
        assertEquals("before after", resumed.single().single().text)
    }

    @Test
    fun hiddenConversationPausesPresentationButReopeningShowsEveryReceivedDelta() = runTest {
        val store = MessageTimelineStore()
        val visible = MutableStateFlow(true)
        val displayed = mutableListOf<List<CodexMessage>>()
        store.appendAssistantDelta("thread", "turn", "item", "before")
        backgroundScope.launch {
            store.messagesByThread.threadDisplayFlow("thread").whileTimelineVisible(visible).collect { displayed += it }
        }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        visible.value = false
        runCurrent()
        val hiddenCount = displayed.size
        repeat(100) {
            store.appendAssistantDelta("thread", "turn", "item", " $it")
            advanceTimeBy(10)
        }
        val expected = store.messagesByThread.value.getValue("thread").single().text
        store.completeAssistantMessage("thread", "turn", "item", expected)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(hiddenCount, displayed.size)
        visible.value = true
        runCurrent()
        assertEquals(expected, displayed.last().single().text)
        assertFalse(displayed.last().single().isStreaming)
    }

    @Test
    fun initiallyHiddenConversationDoesNotPrepareIncomingHistory() = runTest {
        val store = MessageTimelineStore()
        val visible = MutableStateFlow(false)
        val displayed = mutableListOf<List<CodexMessage>>()
        backgroundScope.launch {
            store.messagesByThread.threadDisplayFlow("thread").whileTimelineVisible(visible).collect { displayed += it }
        }
        store.appendAssistantDelta("thread", "turn", "item", "loaded while drawer open")
        advanceTimeBy(100)
        runCurrent()
        assertTrue(displayed.isEmpty())
        visible.value = true
        runCurrent()
        assertEquals("loaded while drawer open", displayed.single().single().text)
    }

    @Test
    fun visibleDeltasKeepExistingFiftyMillisecondCadence() = runTest {
        val store = MessageTimelineStore()
        val visible = MutableStateFlow(true)
        val displayed = mutableListOf<List<CodexMessage>>()
        backgroundScope.launch {
            store.messagesByThread.threadDisplayFlow("thread").whileTimelineVisible(visible).collect { displayed += it }
        }
        runCurrent()
        store.appendAssistantDelta("thread", "turn", "item", "latest")
        advanceTimeBy(50)
        runCurrent()
        assertEquals("latest", displayed.last().single().text)
        store.appendAssistantDelta("thread", "turn", "item", " next")
        advanceTimeBy(50)
        runCurrent()
        assertEquals("latest next", displayed.last().single().text)
    }
}
