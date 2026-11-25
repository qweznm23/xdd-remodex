package com.remodex.mobile.ui.agent

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.core.model.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnActivityTimingTest {
    @Test fun recoversStartAcrossWireFormatsAndKeepsNativeDurationAfterLateStart() {
        val start = TurnActivityTiming.decode(mapOf("startedAt" to JSONValue.NumLong(1795519603)))
        val end = TurnActivityTiming.decode(mapOf("completedAt" to JSONValue.NumLong(1795519907000)))
        assertEquals(304000L, start.supplementedBy(end).finalDuration)
        val finished = end.supplementedBy(TurnActivityTiming.decode(mapOf("durationMs" to JSONValue.NumLong(303705))))
        assertEquals(303705L, finished.supplementedBy(start).finalDuration)
        assertNull(start.finalDuration)
        assertNull(TurnActivityTiming(start = 23, end = 12).finalDuration)
        assertNull(TurnActivityTiming.decode(mapOf("durationMs" to JSONValue.NumLong(-1))).finalDuration)
        assertEquals(0L, TurnActivityTiming(duration = 0).finalDuration)
    }

    @Test fun plainTurnGetsOneDurationAfterFinalChunkAndGroupedTurnUsesActivityRow() {
        val reply = CodexMessage(id = "a", threadId = "thread", turnId = "turn", role = CodexMessageRole.assistant, text = "reply", createdAt = java.time.Instant.EPOCH)
        val chunks = listOf(TimelineListItem.MessageChunk(reply, 0, "first", true, false),
            TimelineListItem.MessageChunk(reply, 1, "last", false, true))
        assertEquals(mapOf(chunks.last().stableKey to "turn"), completedTimingFooters(chunks))
        val group = TimelineListItem.AssistantWorkGroup("work", listOf(TimelineListItem.Single(reply.copy(id = "detail"))))
        assertEquals(emptyMap(), completedTimingFooters(listOf(group) + chunks))
    }
}
