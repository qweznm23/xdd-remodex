package com.remodex.mobile.ui.agent

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageRole
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TimelinePreparationFlowTest {
    @Test
    fun slowerPreparationStillPublishesDuringStreamAndEventuallyShowsExactLatestSnapshot() = runTest {
        val source = MutableStateFlow(request(0))
        val delivered = mutableListOf<Int>()
        backgroundScope.launch {
            source.prepareTimeline { next ->
                delay(100) // Simulate a slower phone; incoming snapshots arrive every 10 ms.
                TimelinePresentation(messages = next.messages)
            }.collect { delivered += it.messages.single().text.toInt() }
        }
        runCurrent()
        repeat(100) { index ->
            source.value = request(index + 1)
            advanceTimeBy(10)
        }
        runCurrent()
        assertTrue(delivered.size >= 9, "Preparation must not restart forever while input continues")
        advanceTimeBy(201)
        runCurrent()
        assertEquals(100, delivered.last())
        assertEquals(delivered.sorted().distinct(), delivered)
        assertTrue(delivered.size < 20, "Superseded waiting snapshots should be coalesced")
    }

    @Test
    fun leavingConversationCancelsPendingPreparationWithoutPublishingToNextConversation() = runTest {
        val source = MutableStateFlow(request(1))
        var published = 0
        var cleanedUp = false
        val collector = backgroundScope.launch {
            source.prepareTimeline { next ->
                try {
                    delay(100)
                    TimelinePresentation(messages = next.messages)
                } finally {
                    cleanedUp = true
                }
            }.collect { published++ }
        }
        runCurrent()
        collector.cancel()
        advanceTimeBy(201)
        runCurrent()
        assertTrue(cleanedUp)
        assertEquals(0, published)
    }

    private fun request(sequence: Int) = TimelineBuildRequest(
        messages = listOf(CodexMessage(id = "stream", threadId = "thread", role = CodexMessageRole.assistant,
            text = sequence.toString(), createdAt = Instant.EPOCH)),
        running = true,
        activeTurnId = null,
    )
}
