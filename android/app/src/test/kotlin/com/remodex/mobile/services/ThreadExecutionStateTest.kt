package com.remodex.mobile.services

import com.remodex.mobile.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ThreadExecutionStateTest {
    private fun service(): CodexService {
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, CodexService::class.java) as CodexService
    }

    @Test fun readCannotReplaceNewerNotificationOrUseOtherThreadsPreference() = runTest {
        val service = service()
        val states = MutableStateFlow(emptyMap<String, ThreadExecutionState>())
        service.setReplayTestField("executionStates", states)
        service.sessionReady = true
        service.observeExecution("other", mapOf("sandbox" to JSONValue.Str("dangerFullAccess"), "approvalPolicy" to JSONValue.Str("never")))
        service.testRpcRequestHandler = { method, params ->
            assertEquals("thread/read", method)
            assertEquals("current", params?.objectValue?.get("threadId")?.stringValue)
            assertEquals(false, params?.objectValue?.get("includeTurns")?.boolValue)
            service.observeExecution("current", mapOf("cwd" to JSONValue.Str("/new"),
                "sandboxPolicy" to JSONValue.Obj(mapOf("type" to JSONValue.Str("readOnly")))))
            RPCMessage.success(JSONValue.Str("1"), JSONValue.Obj(mapOf("thread" to JSONValue.Obj(mapOf(
                "id" to JSONValue.Str("current"), "cwd" to JSONValue.Str("/old"),
            )))))
        }
        service.refreshExecutionFromOwner("current")
        assertEquals("/new", states.value["current"]?.cwd)
        assertEquals("readOnly", states.value["current"]?.sandbox)
        assertNull(states.value["current"]?.preset)
        assertEquals(CodexAccessMode.fullAccess, states.value["other"]?.preset)
    }

    @Test fun accessChangeTargetsRequestedThreadAndRejectedChangeKeepsItsCause() = runTest {
        val service = service()
        service.sessionReady = true
        val states = MutableStateFlow(emptyMap<String, ThreadExecutionState>())
        service.setReplayTestField("executionStates", states)
        service.setReplayTestField("runtimeWriteMutex", kotlinx.coroutines.sync.Mutex())
        service.setReplayTestField("_activeThreadId", MutableStateFlow("B"))
        val refusal = com.remodex.mobile.core.error.CodexServiceError.RpcFailure(RPCError(-32600, "denied by owner"))
        var reject = false
        service.testRpcRequestHandler = { method, params ->
            assertEquals("thread/settings/update", method)
            assertEquals("A", params?.objectValue?.get("threadId")?.stringValue)
            if (reject) throw refusal
            assertEquals("dangerFullAccess", params?.objectValue?.get("sandboxPolicy")?.objectValue?.get("type")?.stringValue)
            // The owner can enforce a narrower policy than requested.
            service.observeExecution("A", mapOf("sandboxPolicy" to JSONValue.Str("readOnly"), "approvalPolicy" to JSONValue.Str("on-request")))
            RPCMessage.success(JSONValue.Str("1"), JSONValue.Obj(emptyMap()))
        }
        service.writeExecutionAccess("A", CodexAccessMode.fullAccess)
        reject = true
        assertSame(refusal, assertFailsWith<com.remodex.mobile.core.error.CodexServiceError.RpcFailure> {
            service.writeExecutionAccess("A", CodexAccessMode.onRequest)
        })
        assertEquals("readOnly", states.value["A"]?.sandbox)
        assertNull(states.value["A"]?.preset)
        assertNull(states.value["B"])
    }

    @Test fun unknownAndExplicitEmptyRemainDistinct() {
        val known = ThreadExecutionState().merge(mapOf("remodexExecution" to JSONValue.Obj(mapOf(
            "sandboxPolicy" to JSONValue.Str("workspaceWrite"), "approvalPolicy" to JSONValue.Str("on-request"),
            "activePermissionProfile" to JSONValue.Obj(mapOf("id" to JSONValue.Str("custom"))),
        ))))
        assertNull(known.preset)
        assertEquals("custom", known.profile)
        val cleared = known.merge(mapOf("environments" to JSONValue.Arr(emptyList()), "activePermissionProfile" to JSONValue.Null))
        assertEquals(emptyList(), cleared.environments)
        assertNull(cleared.merge(mapOf("environments" to JSONValue.Null)).environments)
        assertEquals(CodexAccessMode.onRequest, cleared.preset)
    }
}
