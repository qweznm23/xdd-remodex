package com.remodex.mobile.ui.agent

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageKind
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.ui.turn.buildChatAnchorsFromItems
import com.remodex.mobile.ui.turn.timelineBottomOverflow
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimelinePresentationTest {
    @Test
    fun streamingUpdatesReuseEveryUnchangedHistoryRow() {
        val builder = TimelinePresentationBuilder()
        val history = (0 until 47).map { message("history-$it", "正文段落\n\n".repeat(700)) }
        var stream = message("stream", "begin").copy(isStreaming = true)
        val initial = builder.build(history + stream, true, "stream")
        val historyItems = initial.items.dropLast(1)
        repeat(100) { frame ->
            stream = stream.copy(text = stream.text + "增量$frame\n")
            val next = builder.build(history + stream, true, "stream")
            historyItems.forEachIndexed { index, row -> assertSame(row, next.items[index]) }
            assertEquals(stream.text, (next.items.last() as TimelineListItem.Single).message.text)
            assertTrue(next.streamingAssistant)
        }
        assertEquals("begin", (initial.items.last() as TimelineListItem.Single).message.text)
    }

    @Test
    fun navigationAndBottomUseActualGroupedAndChunkedRows() {
        val messages = listOf(
            message("first-user", "question").copy(role = CodexMessageRole.user),
            message("thinking", "hidden").copy(role = CodexMessageRole.system, kind = CodexMessageKind.thinking),
            message("long", "长段落内容\n\n".repeat(1_000)),
            message("last-user", "next question").copy(role = CodexMessageRole.user),
            message("stream", "answer").copy(isStreaming = true),
        )
        val presentation = TimelinePresentationBuilder().build(messages, true, "stream")
        val anchors = buildChatAnchorsFromItems(presentation.items, 1)
        val lastUserIndex = presentation.items.indexOfFirst { it.stableKey == "last-user" } + 1
        assertEquals(lastUserIndex, anchors.last().index)
        assertNotEquals(messages.indexOfFirst { it.id == "last-user" } + 1, lastUserIndex)
        assertEquals(presentation.items.size + 1, presentation.lastListIndex(1))
        assertTrue(presentation.hasActivityRow)
    }

    @Test
    fun runningStateChangesRebuildGroupingEvenWithoutMessageChanges() {
        val messages = listOf(
            message("comment", "working").copy(turnId = "turn", assistantPhase = "commentary"),
            message("tool", "Completed > cat Test.kt").copy(turnId = "turn", role = CodexMessageRole.system, kind = CodexMessageKind.commandExecution),
            message("final", "result").copy(turnId = "turn", assistantPhase = "final_answer"),
        )
        val builder = TimelinePresentationBuilder()
        val running = builder.build(messages, true, "turn")
        val finished = builder.build(messages, false, null)
        assertTrue(running.items.none { it is TimelineListItem.AssistantWorkGroup })
        assertTrue(finished.items.any { it is TimelineListItem.AssistantWorkGroup })
        assertFalse(finished.hasActivityRow)
        assertEquals(finished.items.lastIndex, finished.lastListIndex(0))
    }

    @Test
    fun changedMessageWithSameIdCannotReuseStaleText() {
        val builder = TimelinePresentationBuilder()
        val old = message("same-id", "old")
        builder.build(listOf(old), false, null)
        builder.build(emptyList(), false, null)
        val changed = old.copy(text = "new")
        val actual = builder.build(listOf(changed), false, null)
        assertSame(changed, (actual.items.single() as TimelineListItem.Single).message)
        assertEquals(-1, builder.build(emptyList(), false, null).lastListIndex(0))
    }

    @Test
    fun tallLastRowIsNotMistakenForBeingAtBottom() {
        assertEquals(1_100, timelineBottomOverflow(1, 0, 0, 1_500, 600, 200))
        assertEquals(0, timelineBottomOverflow(1, 0, -1_100, 1_500, 600, 200))
        assertNull(timelineBottomOverflow(5, 3, 0, 100, 600, 200))
    }

    @Test
    fun shortContentAndEmptyContentDoNotRequestExtraScroll() {
        assertEquals(-300, timelineBottomOverflow(1, 0, 0, 100, 600, 200))
        assertEquals(0, timelineBottomOverflow(0, null, 0, 0, 600, 200))
    }

    private fun message(id: String, text: String) = CodexMessage(
        id = id, threadId = "thread", turnId = id, role = CodexMessageRole.assistant,
        text = text, createdAt = Instant.EPOCH,
    )
}
