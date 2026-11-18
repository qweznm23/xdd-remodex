package com.remodex.mobile.core.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConnectionStageTimeoutTest {
    @Test fun silentHandshakeBecomesRetryableFailureNotCancellation() = runTest {
        val mux = SecureControlMultiplexer(Json) { }
        val error = assertFailsWith<ConnectionStageTimeout> { mux.receive("serverHello", timeoutMs = 100) }
        assertEquals("serverHello", error.stage)
        assertEquals(100, error.timeoutMillis)
    }

    @Test fun callerAndInnerTimeoutsKeepCancellationSemantics() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(10) { awaitConnectionStage("initialize", 100) { awaitCancellation() } }
        }
        assertFailsWith<TimeoutCancellationException> {
            awaitConnectionStage("initialize", 100) { withTimeout(10) { awaitCancellation() } }
        }
        assertFailsWith<CancellationException> {
            awaitConnectionStage("initialize", 100) { throw CancellationException("screen closed") }
        }
    }

    @Test fun initializationHasOwnedDeadlineAndPreservesRealFailure() = runTest {
        val timeout = assertFailsWith<ConnectionStageTimeout> {
            awaitConnectionStage("initialize", 100) { awaitCancellation() }
        }
        assertEquals("initialize", timeout.stage)
        val original = java.io.IOException("connection reset", IllegalStateException("socket cause"))
        original.addSuppressed(IllegalArgumentException("cleanup cause"))
        val error = assertFailsWith<java.io.IOException> {
            awaitConnectionStage("initialize", 100) { throw original }
        }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === original })
        assertEquals("cleanup cause", original.suppressed.single().message)
        assertEquals("socket cause", original.cause?.message)
    }
}
