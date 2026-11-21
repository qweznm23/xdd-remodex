package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RateLimitRefreshGuardTest {
    private val week = CodexRateLimitBucket("codex", secondary = CodexRateLimitWindow(25, 10080))
    private val short = CodexRateLimitBucket("codex", primary = CodexRateLimitWindow(10, 300))

    @Test fun lateReadKeepsNewerPartialPushAndFillsMissingWeek() {
        val guard = RateLimitRefreshGuard()
        val read = guard.begin(1)!!
        guard.pushed(listOf(short))
        val resolved = RateLimitPayloadCodec.mergeRateLimitBuckets(listOf(week), read.pushedBuckets).single()
        assertEquals(week.secondary, resolved.secondary)
        assertEquals(short.primary, resolved.primary)
        guard.cancel(read)
        guard.finish(read)
        assertTrue(guard.begin(1) != null)
    }

    @Test fun automaticReadsAreThrottledAcrossScreensAndWeeklyPushesExtendFreshness() {
        var now = 0L
        val guard = RateLimitRefreshGuard { now }
        guard.finish(guard.begin(1)!!)
        now = 299_000
        assertNull(guard.begin(1))
        guard.pushed(listOf(week))
        now = 300_000
        assertNull(guard.begin(1))
        val manual = guard.begin(1, force = true)!!
        assertNull(guard.begin(1, force = true))
        guard.finish(manual)
        now += 299_999
        guard.pushed(listOf(short))
        assertNull(guard.begin(1))
        now++
        val old = guard.begin(1)!!
        guard.reset(waitForReconnect = true)
        assertFalse(guard.isCurrent(old, 1))
        assertNull(guard.begin(1, force = true))
        guard.reset()
        assertTrue(guard.begin(2) != null)
    }

    @Test fun concurrentReadDeduplicatesAndOldCleanupCannotClearNewConnection() {
        val guard = RateLimitRefreshGuard()
        val old = guard.begin(1)!!
        assertNull(guard.begin(1))
        guard.reset()
        val next = guard.begin(2)!!
        assertFalse(guard.isCurrent(old, 2))
        guard.finish(old)
        assertTrue(guard.isCurrent(next, 2))
        assertTrue(next.pushedBuckets.isEmpty())
        guard.pushed(listOf(week))
        assertEquals(listOf(week), next.pushedBuckets)
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
