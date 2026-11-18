package com.remodex.mobile.core.persistence

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.data.MessageTimelineStore
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TimelinePersistenceWriterTest {
    @Test
    fun streamingBurstPersistsCompleteFinalMessageWithBoundedWrites() = runTest {
        val saved = mutableListOf<Map<String, List<CodexMessage>>>()
        val writer = TimelinePersistenceWriter(backgroundScope, { snapshot, _ -> saved += snapshot },
            { _, _, error -> throw AssertionError(error) })
        val store = MessageTimelineStore(emptyMap()) { writer.submit(it, "computer-a") }
        val expected = buildString { repeat(1_000) { append("片段$it\n") } }
        repeat(1_000) {
            store.appendAssistantDelta("thread", "turn", "item", "片段$it\n")
            advanceTimeBy(1)
        }
        assertEquals(expected, store.messagesByThread.value["thread"]!!.single().text)
        store.completeAssistantMessage("thread", "turn", "item", expected)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(expected, store.messagesByThread.value["thread"]!!.single().text)
        assertEquals(store.messagesByThread.value, saved.last())
        assertFalse(saved.last()["thread"]!!.single().isStreaming)
        assertTrue(saved.size in 2..3, "Expected at most 3 writes, got ${saved.size}")
    }

    @Test
    fun deviceSwitchCommitsImmediatelyAndOldPendingWriteCannotOverwriteIt() = runTest {
        val writes = mutableListOf<Pair<String?, String>>()
        val writer = TimelinePersistenceWriter(backgroundScope,
            { messages, device -> writes += device to messages["thread"]!!.single().text },
            { _, _, error -> throw AssertionError(error) })
        writer.submit(snapshot("old-a"), "a")
        writer.submit(snapshot("b"), "b")
        runCurrent()
        writer.saveNow(snapshot("final-a"), "a")
        assertEquals(listOf<Pair<String?, String>>("a" to "final-a"), writes)
        advanceTimeBy(501)
        runCurrent()
        assertEquals(listOf<Pair<String?, String>>("a" to "final-a", "b" to "b"), writes)
    }

    @Test
    fun updateArrivingDuringWriteIsSavedAfterOlderSnapshot() = runTest {
        val writes = mutableListOf<String>()
        lateinit var writer: TimelinePersistenceWriter
        writer = TimelinePersistenceWriter(backgroundScope,
            { messages, device ->
                val text = messages["thread"]!!.single().text
                writes += text
                if (text == "first") writer.submit(snapshot("last"), device)
            }, { _, _, error -> throw AssertionError(error) })
        writer.submit(snapshot("first"), "a")
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("first", "last"), writes)
    }

    @Test
    fun writeFailureRetainsOriginalCauseAndDoesNotRetryWithoutNewData() = runTest {
        val cause = IOException("ENOSPC: cache disk full")
        val failure = IOException("thread cache write", cause).apply {
            addSuppressed(IOException("close failed"))
        }
        val failures = mutableListOf<Exception>()
        var attempts = 0
        val writer = TimelinePersistenceWriter(backgroundScope,
            { _, _ -> attempts++; throw failure },
            { device, threadCount, error ->
                assertEquals("a", device)
                assertEquals(1, threadCount)
                failures += error
            })
        writer.submit(snapshot("text"), "a")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, attempts)
        assertSame(failure, failures.single())
        assertSame(cause, failures.single().cause)
        assertSame(failure.suppressed.single(), failures.single().suppressed.single())
        writer.submit(snapshot("new text"), "a")
        advanceTimeBy(501)
        runCurrent()
        assertEquals(2, attempts)
    }

    @Test
    fun cancellationIsNotReportedAsStorageFailureOrRetried() = runTest {
        var attempts = 0
        var failures = 0
        val writer = TimelinePersistenceWriter(backgroundScope,
            { _, _ -> attempts++; throw CancellationException("service stopped") },
            { _, _, _ -> failures++ })
        writer.submit(snapshot("text"), null)
        advanceTimeBy(501)
        runCurrent()
        writer.submit(snapshot("later"), null)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(0, failures)
    }

    private fun snapshot(text: String) = mapOf("thread" to listOf(
        CodexMessage(id = "message", threadId = "thread", role = CodexMessageRole.assistant,
            text = text, createdAt = Instant.EPOCH),
    ))
}
