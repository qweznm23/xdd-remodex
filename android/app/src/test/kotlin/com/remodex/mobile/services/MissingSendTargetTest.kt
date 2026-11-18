package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MissingSendTargetTest {
    @Test fun missingOriginalNeverCreatesAnotherThreadOrChangesSelection() = runTest {
        // Use the repository's RPC override without starting Android Keystore or a real Codex.
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val service = unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, CodexService::class.java) as CodexService
        service.sessionReady = true
        service.setReplayTestField("_threads", MutableStateFlow(emptyList<CodexThread>()))
        service.setReplayTestField("resumedThreadIds", mutableSetOf<String>())
        service.setReplayTestField("_selectedAccessMode", MutableStateFlow(CodexAccessMode.onRequest))
        val selection = MutableStateFlow("A")
        service.setReplayTestField("_activeThreadId", selection)
        val calls = mutableListOf<String>()
        val failure = CodexServiceError.RpcFailure(RPCError(-32600, "thread not found"))
        service.testRpcRequestHandler = { method, params ->
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
