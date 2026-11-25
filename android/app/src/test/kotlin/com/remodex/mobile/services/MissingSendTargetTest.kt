package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MissingSendTargetTest {
    private val unloaded = CodexServiceError.RpcFailure(RPCError(-32600, "thread not found: A"))

    @Test fun rejectedStartResumesSameThreadAndMakesOneFurtherAttempt() = runTest {
        val calls = mutableListOf<String>()
        val accepted = RPCMessage.success(null, JSONValue.Obj(emptyMap()))
        val result = startWithThreadRecovery("A", { calls += "resume" }) {
            calls += "start"
            if (calls.size == 1) throw unloaded
            accepted
        }
        assertSame(accepted, result)
        assertEquals(listOf("start", "resume", "start"), calls)
    }

    @Test fun unknownDeliveryAndUnrelatedErrorsAreNeverReplayed() = runTest {
        for (error in listOf(java.net.SocketTimeoutException("ack lost"), CodexServiceError.Disconnected,
            CodexServiceError.RpcFailure(RPCError(-32600, "thread not found: B")),
            CodexServiceError.RpcFailure(RPCError(-32001, "thread not found: A")),
            kotlinx.coroutines.CancellationException("cancelled"))) {
            var calls = 0
            val thrown = assertFails {
                startWithThreadRecovery("A", { fail("unexpected resume") }) { calls++; throw error }
            }
            assertSame(error, thrown)
            assertEquals(1, calls)
        }
    }

    @Test fun failureToResumePreservesBothReasonsAndOnlyConfirmedMissingEnablesDraftRecovery() = runTest {
        for (failure in listOf(CodexServiceError.RpcFailure(RPCError(-32600, "no rollout found for thread id A")),
            java.net.SocketTimeoutException("resume timeout"))) {
            var starts = 0
            val thrown = assertFails {
                startWithThreadRecovery("A", { throw failure }) { starts++; throw unloaded }
            }
            assertEquals(1, starts)
            assertSame(failure, thrown.cause)
            assertSame(unloaded, thrown.suppressed.single())
            assertEquals(failure is CodexServiceError.RpcFailure, thrown is CodexServiceError.MissingSendTargetAfterResume)
        }
    }

    @Test fun repeatedRejectionStopsAndReportsSecondAttempt() = runTest {
        var calls = 0
        val failure = assertFails {
            startWithThreadRecovery("A", {}) { calls++; throw unloaded }
        }
        assertEquals(2, calls)
        assertSame(unloaded, failure.cause)
        assertContains(failure.message.orEmpty(), "turn/start after thread/resume")
    }

    @Test fun missingOriginalNeverCreatesAnotherThreadOrChangesSelection() = runTest {
        // Use the repository's RPC override without starting Android Keystore or a real Codex.
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val service = unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, CodexService::class.java) as CodexService
        service.setReplayTestField("executionStates", MutableStateFlow(emptyMap<String, ThreadExecutionState>()))
        service.sessionReady = true
        service.setReplayTestField("_threads", MutableStateFlow(emptyList<CodexThread>()))
        service.setReplayTestField("resumedThreadIds", mutableSetOf<String>())
        service.setReplayTestField("_selectedAccessMode", MutableStateFlow(CodexAccessMode.onRequest))
        val selection = MutableStateFlow("A")
        service.setReplayTestField("_activeThreadId", selection)
        val calls = mutableListOf<String>()
        val failure = CodexServiceError.RpcFailure(RPCError(-32600, "thread not found"))
        service.testRpcRequestHandler = { method, params ->
            assertTrue(params!!.objectValue!!.keys.none { it in setOf("sandbox", "sandboxPolicy", "approvalPolicy", "cwd") })
            calls += method
            assertEquals("A", (params as JSONValue.Obj).map["threadId"]?.stringValue)
            selection.value = "B"
            throw failure
        }
        assertSame(failure, assertFailsWith<CodexServiceError.RpcFailure> { service.startTurnInternal("A", "unsent") })
        assertSame(failure, assertFailsWith<CodexServiceError.RpcFailure> { service.startReviewInternal("A", CodexReviewTarget.uncommittedChanges) })
        assertEquals(listOf("thread/resume", "thread/resume"), calls)
        assertEquals("B", selection.value)
    }
}
