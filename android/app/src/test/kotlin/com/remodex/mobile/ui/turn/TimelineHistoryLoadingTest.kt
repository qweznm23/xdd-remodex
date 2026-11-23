package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.ui.agent.TimelineListItem
import com.remodex.mobile.core.model.CodexMessageKind
import java.time.Instant
import kotlin.test.*

class TimelineHistoryLoadingTest {
    private fun row(i: Int) = CodexMessage(id = "$i", threadId = "t", role = CodexMessageRole.user, text = "$i", createdAt = Instant.EPOCH)

    @Test fun autoLoadRequiresAUserHistoryGestureAndNeverConsumesScroll() {
        val gate = TimelineHistoryAutoLoadGate()
        assertFalse(gate.scroll(20f, false, true, true))
        gate.beginGesture()
        assertFalse(gate.scroll(-20f, true, true, true))
        assertFalse(gate.scroll(20f, true, false, true))
        assertTrue(gate.scroll(20f, true, true, true))
        repeat(100) { assertFalse(gate.scroll(20f, true, true, true)) }
        gate.beginGesture()
        assertTrue(gate.scroll(20f, true, true, true))
    }

    @Test fun flingCanReachTopButBusyErrorAndCancelledGesturesDoNotLoad() {
        val gate = TimelineHistoryAutoLoadGate()
        gate.beginGesture()
        assertFalse(gate.scroll(10f, true, false, true))
        assertFalse(gate.scroll(10f, false, true, false))
        assertTrue(gate.scroll(10f, false, true, true))
        gate.beginGesture();gate.cancelGesture()
        assertFalse(gate.scroll(10f, false, true, true))
    }

    @Test fun readerWindowDoesNotShiftWhenOlderHistoryOrNewLiveMessagesArrive() {
        val initial = (80..99).map(::row)
        assertEquals(0, timelineWindowStartIndex(initial, null, 48, true))
        val prepended = (0..99).map(::row)
        assertEquals(80, timelineWindowStartIndex(prepended, "80", 48, false))
        val streaming = (0..130).map(::row)
        assertEquals(80, timelineWindowStartIndex(streaming, "80", 48, false))
        assertEquals(83, timelineWindowStartIndex(streaming, "80", 48, true))
    }

    @Test fun viewportAnchorSurvivesPrependingAndARegroupedToolRow() {
        val rows = (0..4).map(::row)
        val old = TimelineListItem.CommandExecutionGroup(rows.drop(2))
        val anchor = HistoryViewportAnchor(old.stableKey, "2", 53)
        val expanded = listOf(TimelineListItem.Single(row(-1)), TimelineListItem.CommandExecutionGroup(rows))
        assertEquals(1, historyAnchorIndex(expanded, anchor))
        assertNull(historyAnchorIndex(listOf(TimelineListItem.Single(row(9))), anchor))
    }

    @Test fun cachedCommandRowsExtendTheWindowThroughToTheOlderUserMessage() {
        val rows = listOf(row(0).copy(turnId = "turn")) +
            (1..240).map { row(it).copy(turnId = "turn", role = CodexMessageRole.system, kind = CodexMessageKind.commandExecution) } +
            row(241).copy(turnId = "turn", role = CodexMessageRole.assistant, text = "final answer")
        assertEquals(0, olderHistoryRevealStart(rows, boundary = rows.size - 48))
    }

    @Test fun normalDialogueAndActivityOnlyEndKeepABoundedBatch() {
        val rows = (0..200).map(::row)
        assertEquals(120, olderHistoryRevealStart(rows, boundary = 200))
        val onlyActivity = rows.map { it.copy(role = CodexMessageRole.system, kind = CodexMessageKind.fileChange) }
        assertEquals(120, olderHistoryRevealStart(onlyActivity, boundary = 200))
    }
}
