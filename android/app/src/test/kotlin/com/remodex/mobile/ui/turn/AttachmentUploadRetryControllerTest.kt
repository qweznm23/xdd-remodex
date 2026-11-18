package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexFileAttachment
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentUploadRetryControllerTest {
    private val ready = TurnComposerAttachmentState.ReadyFile(
        CodexFileAttachment(fileName = "report.zip", mimeType = "application/zip", sizeBytes = 12,
            textContent = null, sourceUri = "content://picked/report", remotePath = "/uploaded/report.zip"),
    )

    @Test fun retriesSameSourceAndRetainsOriginalDiagnostics() = runTest {
        val cause = IOException("socket reset")
        val error = IOException("upload_chunk rpc=-32000", cause).apply { addSuppressed(IOException("cleanup failed")) }
        val states = mutableListOf<TurnComposerAttachmentState>()
        val failures = mutableListOf<Pair<Int, Exception>>()
        var calls = 0
        val controller = AttachmentUploadRetryController(this, { id, state ->
            assertEquals("selected", id)
            states += state
        }, { id, attempt, failure ->
            assertEquals("selected", id)
            failures += attempt to failure
        })
        controller.add("selected") { progress ->
            calls++
            progress(6, 12)
            if (calls == 1) throw error
            ready
        }
        runCurrent()
        assertTrue(assertIs<TurnComposerAttachmentState.Failed>(states.last()).canRetry)
        assertSame(error, failures.single().second)
        assertSame(cause, failures.single().second.cause)
        assertEquals("cleanup failed", failures.single().second.suppressed.single().message)
        assertEquals(1, failures.single().first)
        controller.retry("selected")
        runCurrent()
        assertEquals(2, calls)
        assertEquals(ready, states.last())
        controller.retry("selected")
        runCurrent()
        assertEquals(2, calls, "Completed uploads cannot be uploaded again")
    }

    @Test fun rapidRetryClicksStartOnlyOneTransferAndRepeatedFailureRemainsRetryable() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val attempts = mutableListOf<Int>()
        val states = mutableListOf<TurnComposerAttachmentState>()
        val controller = AttachmentUploadRetryController(this, { _, state -> states += state }, { _, attempt, _ -> attempts += attempt })
        controller.add("a") {
            calls++
            if (calls > 1) gate.await()
            throw IOException("temporary failure")
        }
        runCurrent()
        repeat(10) { controller.retry("a") }
        runCurrent()
        repeat(10) { controller.retry("a") }
        assertEquals(2, calls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 2), attempts)
        assertTrue(assertIs<TurnComposerAttachmentState.Failed>(states.last()).canRetry)
        controller.retry("a")
        runCurrent()
        assertEquals(listOf(1, 2, 3), attempts)
    }

    @Test fun removedOrReplacedTransferCannotPublishLateProgressOrCompletion() = runTest {
        val gate = CompletableDeferred<Unit>()
        val states = mutableListOf<TurnComposerAttachmentState>()
        val controller = AttachmentUploadRetryController(this, { _, state -> states += state }, { _, _, _ -> error("unexpected failure") })
        controller.add("a") { progress ->
            withContext(NonCancellable) {
                gate.await()
                progress(999, 999)
            }
            ready
        }
        runCurrent()
        controller.add("a") { ready }
        runCurrent()
        val accepted = states.toList()
        gate.complete(Unit)
        runCurrent()
        assertEquals(accepted, states)
        assertEquals(ready, states.last())
    }

    @Test fun cancellationIsNotFailureAndRemovedSourceCannotRetry() = runTest {
        var cancelled = false
        var failures = 0
        var calls = 0
        val states = mutableListOf<TurnComposerAttachmentState>()
        val controller = AttachmentUploadRetryController(this, { _, state -> states += state }, { _, _, _ -> failures++ })
        controller.add("a") {
            calls++
            try { awaitCancellation() } catch (e: CancellationException) { cancelled = true; throw e }
        }
        runCurrent()
        controller.clear()
        runCurrent()
        controller.retry("a")
        runCurrent()
        assertTrue(cancelled)
        assertEquals(0, failures)
        assertEquals(1, calls)
        assertFalse(states.any { it is TurnComposerAttachmentState.Failed })
    }

    @Test fun retainingComposerIdsReleasesRemovedFailuresOnly() = runTest {
        var a = 0
        var b = 0
        val controller = AttachmentUploadRetryController(this, { _, _ -> }, { _, _, _ -> })
        controller.add("a") { a++; throw IOException("a") }
        controller.add("b") { b++; throw IOException("b") }
        runCurrent()
        controller.retain(setOf("b"))
        controller.retry("a")
        controller.retry("b")
        runCurrent()
        assertEquals(1, a)
        assertEquals(2, b)
    }
    @Test fun knownSizeRejectionDoesNotOfferFutileRetry() = runTest {
        var calls = 0
        val states = mutableListOf<TurnComposerAttachmentState>()
        val failure = com.remodex.mobile.services.FileTransferFailure("upload_metadata", "upload_too_large", "File exceeds 100 MB")
        val controller = AttachmentUploadRetryController(this, { _, state -> states += state }, { _, _, error -> assertSame(failure, error) })
        controller.add("a") { calls++; throw failure }
        runCurrent()
        assertFalse(assertIs<TurnComposerAttachmentState.Failed>(states.last()).canRetry)
        controller.retry("a")
        runCurrent()
        assertEquals(1, calls)
    }

}
