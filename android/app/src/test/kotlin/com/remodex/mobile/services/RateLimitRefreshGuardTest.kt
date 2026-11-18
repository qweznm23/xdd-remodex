package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RateLimitRefreshGuardTest {
    @Test fun concurrentReadDeduplicatesAndOldCleanupCannotClearNewConnection() {
        val guard = RateLimitRefreshGuard()
        val old = guard.begin(1)!!
        assertNull(guard.begin(1))
        guard.reset()
        val next = guard.begin(2)!!
        assertFalse(guard.isCurrent(old, 2))
        guard.finish(old)
        assertTrue(guard.isCurrent(next, 2))
        assertFalse(guard.hasNewerPush(next))
        guard.pushed()
        assertTrue(guard.hasNewerPush(next))
    }

    @Test fun explicitDetailReadHasOneCompatibilityRetryAndPreservesBothRpcFailures() = runTest {
        val first = CodexServiceError.RpcFailure(RPCError(-32602, "invalid params: unknown field excludeResetCreditDetails"))
        val cause = IOException("connection reset")
        val last = IOException("second request failed", cause)
        var attempts = 0
        val failure = assertFailsWith<IOException> {
            fetchRateLimitsWithCompatRetry { params ->
                attempts++
                if (attempts == 1) {
                    assertEquals(false, params.objectValue!!["excludeResetCreditDetails"]?.boolValue)
                    throw first
                }
                assertEquals(emptyMap(), params.objectValue)
                throw last
            }
        }
        val chain = generateSequence(failure as Throwable?) { it.cause }.toList()
        assertTrue(chain.any { it === cause })
        assertTrue(chain.any { it.suppressed.any { e -> e === first } })
        assertEquals(-32602, first.rpcError.code)
        assertEquals(2, attempts)
    }

    @Test fun businessFailuresAndCancellationAreNotRetried() = runTest {
        for (failure in listOf(CodexServiceError.RpcFailure(RPCError(-32000, "account unavailable")), CancellationException("caller cancelled"))) {
            var calls = 0
            val caught = assertFails { fetchRateLimitsWithCompatRetry { calls++; throw failure } }
            assertTrue(failure.javaClass.isInstance(caught))
            assertEquals(1, calls)
        }
    }
}
