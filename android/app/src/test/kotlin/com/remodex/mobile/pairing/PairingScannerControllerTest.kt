package com.remodex.mobile.pairing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingScannerControllerTest {
    private class Backend : PairingScannerBackend {
        var prepares = 0
        var scans = 0
        var onProgress: ((ScannerPhase, Float?) -> Unit)? = null
        var prepareAction: suspend () -> Unit = {}
        var scanAction: suspend () -> String? = { "{\"test\":true}" }
        override suspend fun prepare(onProgress: (ScannerPhase, Float?) -> Unit) {
            prepares++
            this.onProgress = onProgress
            prepareAction()
        }
        override suspend fun scan(): String? { scans++; return scanAction() }
    }

    @Test fun doesNotOpenScannerUntilPreparationCompletes() = runTest {
        val ready = CompletableDeferred<Unit>()
        val backend = Backend().apply { prepareAction = { ready.await() } }
        val controller = PairingScannerController(backend)
        var payload: String? = null
        val job = launch { payload = controller.scan() }
        runCurrent()
        assertEquals(0, backend.scans)
        backend.onProgress!!(ScannerPhase.DOWNLOADING, 0.4f)
        assertEquals(0.4f, controller.state.value.progress)
        backend.onProgress!!(ScannerPhase.PAUSED, 0.4f)
        assertEquals(ScannerPhase.PAUSED, controller.state.value.phase)
        ready.complete(Unit)
        job.join()
        assertEquals("{\"test\":true}", payload)
        assertEquals(1, backend.scans)
    }

    @Test fun preparationFailurePreservesOriginalCauseSuppressedCodeAndStage() = runTest {
        val root = IllegalStateException("module code=17")
        val failure = IllegalArgumentException("install rejected", root)
        val cleanup = IllegalStateException("listener cleanup")
        failure.addSuppressed(cleanup)
        val backend = Backend().apply { prepareAction = { throw failure } }
        val controller = PairingScannerController(backend)
        assertNull(controller.scan())
        val diagnostic = controller.state.value.failure!!
        // Coroutine stack-trace recovery may add a copy whose cause is the original error.
        val chain = generateSequence<Throwable>(diagnostic.cause) { it.cause }.toList()
        assertEquals(failure.javaClass, diagnostic.cause.javaClass)
        assertEquals(failure.message, diagnostic.cause.message)
        assertTrue(chain.any { it === failure })
        assertTrue(chain.any { it === root })
        assertTrue(chain.flatMap { it.suppressed.toList() }.any { it === cleanup })
        assertEquals("module_prepare", diagnostic.stage)
        assertTrue(diagnostic.diagnostic().contains("module code=17"))
        assertTrue(diagnostic.diagnostic().contains("Suppressed:"))
        assertEquals(0, backend.scans)
    }

    @Test fun scanFailureIsNotReportedAsModuleFailureOrDismissal() = runTest {
        val failure = IllegalStateException("scanner code=205")
        val backend = Backend().apply { scanAction = { throw failure } }
        val controller = PairingScannerController(backend)
        assertNull(controller.scan())
        assertEquals("scan", controller.state.value.failure!!.stage)
        assertSame(failure, controller.state.value.failure!!.cause)
        assertEquals(ScannerPhase.FAILED, controller.state.value.phase)
    }

    @Test fun dismissDoesNotFailOrAutomaticallyReopen() = runTest {
        val backend = Backend().apply { scanAction = { null } }
        val controller = PairingScannerController(backend)
        assertNull(controller.scan())
        assertEquals(ScannerPhase.CANCELED, controller.state.value.phase)
        assertNull(controller.state.value.failure)
        advanceTimeBy(180_001)
        assertEquals(1, backend.scans)
    }

    @Test fun cancellationPropagatesAndLateProgressCannotOverwriteRetry() = runTest {
        val backend = Backend().apply { prepareAction = { awaitCancellation() } }
        val controller = PairingScannerController(backend)
        var propagated = false
        val job = launch {
            try { controller.scan() } catch (cancel: CancellationException) { propagated = true; throw cancel }
        }
        runCurrent()
        val oldProgress = backend.onProgress!!
        job.cancel()
        job.join()
        assertTrue(propagated)
        oldProgress(ScannerPhase.INSTALLING, 1f)
        assertEquals(ScannerPhase.CANCELED, controller.state.value.phase)
        val ready = CompletableDeferred<Unit>()
        backend.prepareAction = { ready.await() }
        val retry = launch { controller.scan() }
        runCurrent()
        oldProgress(ScannerPhase.PAUSED, 0.5f)
        assertEquals(ScannerPhase.PREPARING, controller.state.value.phase)
        ready.complete(Unit)
        retry.join()
        assertEquals(1, backend.scans)
    }

    @Test fun ownedPreparationTimeoutDoesNotOpenScanner() = runTest {
        val backend = Backend().apply { prepareAction = { awaitCancellation() } }
        val controller = PairingScannerController(backend)
        launch { controller.scan() }
        runCurrent()
        advanceTimeBy(180_001)
        runCurrent()
        assertTrue(controller.state.value.failure!!.cause is ScannerPreparationTimeout)
        assertTrue(controller.state.value.failure!!.cause.cause is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(0, backend.scans)
    }

    @Test fun parentTimeoutRemainsCancellation() = runTest {
        val backend = Backend().apply { prepareAction = { awaitCancellation() } }
        val controller = PairingScannerController(backend)
        val result = kotlinx.coroutines.withTimeoutOrNull(100) { controller.scan(); true }
        assertNull(result)
        assertNull(controller.state.value.failure)
        assertEquals(ScannerPhase.CANCELED, controller.state.value.phase)
    }

    @Test fun duplicateStartDoesNotOpenTwoScanners() = runTest {
        val ready = CompletableDeferred<Unit>()
        val backend = Backend().apply { prepareAction = { ready.await() } }
        val controller = PairingScannerController(backend)
        val job = launch { controller.scan() }
        runCurrent()
        assertNull(controller.scan())
        assertEquals(1, backend.prepares)
        ready.complete(Unit)
        job.join()
        assertEquals(1, backend.scans)
    }

    @Test fun emptyBarcodeIsFailureNotDismissal() = runTest {
        val backend = Backend().apply { scanAction = { " " } }
        val controller = PairingScannerController(backend)
        assertNull(controller.scan())
        assertEquals(ScannerPhase.FAILED, controller.state.value.phase)
        assertTrue(controller.state.value.failure!!.diagnostic().contains("empty barcode"))
    }

    @Test fun lateNativeScanResultCannotConnectAfterLeavingTheScreen() = runTest {
        val nativeResult = CompletableDeferred<String>()
        val backend = Backend().apply {
            scanAction = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { nativeResult.await() } }
        }
        val controller = PairingScannerController(backend)
        var delivered: String? = null
        val job = launch { delivered = controller.scan() }
        runCurrent()
        assertEquals(ScannerPhase.SCANNING, controller.state.value.phase)
        job.cancel()
        nativeResult.complete("late pairing payload")
        job.join()
        assertNull(delivered)
        assertEquals(ScannerPhase.CANCELED, controller.state.value.phase)
        assertNull(controller.state.value.failure)
    }
}
