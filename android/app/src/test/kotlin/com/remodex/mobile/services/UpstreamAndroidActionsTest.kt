package com.remodex.mobile.services

import com.remodex.mobile.core.model.*
import com.remodex.mobile.core.notification.*
import com.remodex.mobile.data.ThreadFollowUps
import com.remodex.mobile.ui.turn.filterMemoryCitation
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class UpstreamAndroidActionsTest {
    private fun s(v: String) = JSONValue.Str(v)
    private fun obj(vararg p: Pair<String, JSONValue>) = JSONValue.Obj(mapOf(*p))

    @Test fun customChatRootsRespectDirectoryBoundariesAndLegacyPaths() {
        val grouping = com.remodex.mobile.ui.sidebar.SidebarThreadGrouping
        val threads = listOf("/custom/threads/abc", "/custom/threads-other", "/normal/project", "/home/u/.codex/threads/child")
            .map { CodexThread(id = it, cwd = it) }
        val groups = grouping.makeGroups(threads, projectlessRoots = listOf("/custom/threads/"))
        assertEquals(setOf(threads[0].id, threads[3].id), groups.single { it.kind ==
            com.remodex.mobile.ui.sidebar.SidebarThreadGroupKind.Chats }.threads.map { it.id }.toSet())
        assertEquals(2, groups.count { it.kind == com.remodex.mobile.ui.sidebar.SidebarThreadGroupKind.Project })
    }

    @Test fun replacementReconcilesOnlyAnIdentifiedDesktopAlias() {
        fun row(turn: String, id: String) = CodexMessage(id = "$turn-$id", threadId = "a", itemId = id, turnId = turn,
            role = CodexMessageRole.user, text = "same words", createdAt = java.time.Instant.EPOCH)
        val old = listOf(row("ipc-turn-a", "matched"), row("ipc-turn-b", "other"), row("existing", "keep"))
        val copy = com.remodex.mobile.data.reconcileDesktopTurnAliases(old, listOf(row("canonical", "matched")))
        assertEquals(listOf("canonical", "ipc-turn-b", "existing"), copy.map { it.turnId })
    }

    @Test fun conflictingSinglePatchIsPreviewedWithoutAnApplyRequest() = runTest {
        val calls = mutableListOf<String>()
        val repo = MinimalSendRepository { method, params ->
            calls += method
            assertEquals("diff", params?.objectValue?.get("forwardPatch")?.stringValue)
            RPCMessage(result = obj("canRevert" to JSONValue.Bool(false), "conflicts" to
                JSONValue.Arr(listOf(obj("path" to s("a.kt"), "message" to s("modified"))))))
        }
        val change = AIChangeSet(threadId = "a", turnId = "b", forwardUnifiedPatch = "diff",
            createdAt = java.time.Instant.EPOCH, source = AIChangeSetSource.turnDiff)
        val preview = AiChangeSetRevertService(repo).previewSinglePatch(change, "/fixture")
        assertFalse(preview.canRevert)
        assertEquals("modified", preview.conflicts.single().message)
        assertEquals(listOf("workspace/revertPatchPreview"), calls)
    }

    @Test fun reopeningAHydratedThreadLoadsMissedHostMessagesEvenWhenListRefreshFails() = runTest {
        val store = com.remodex.mobile.data.MessageTimelineStore()
        val listError = java.io.IOException("disconnected during thread/list")
        val device = kotlinx.coroutines.flow.MutableStateFlow<String?>("host")
        val active = kotlinx.coroutines.flow.MutableStateFlow<String?>("thread")
        val base = MinimalSendRepository { _, _ -> error("unexpected request") }
        var reads = 0
        val repo = object : com.remodex.mobile.data.CodexRepository by base {
            override val currentTrustedMacDeviceId = device
            override val activeThreadId = active
            override suspend fun refreshThreads() { throw listError }
            override suspend fun syncThreadHistory(threadId: String, force: Boolean) {
                if (!force) return // already hydrated before the app went to the background
                val page = fetchRecentThreadHistory(threadId) { method, params ->
                    reads++
                    assertEquals("thread/items/list", method)
                    assertEquals(null, params.map["cursor"])
                    RPCMessage(result = obj("nextCursor" to JSONValue.Null, "data" to JSONValue.Arr(listOf(
                        obj("turnId" to s("new"), "item" to obj("id" to s("answer"), "type" to s("agentMessage"), "text" to s("while away")))))))
                }
                store.mergeThreadHistory(threadId, com.remodex.mobile.data.ThreadHistoryDecoder.decodeFromThreadRead(
                    threadId, page.threadObject(emptyMap())), isItemPage = true)
            }
        }
        val failures = mutableListOf<Exception>()
        com.remodex.mobile.ui.home.refreshAfterBackground(repo) { _, failure -> failures += failure }
        assertSame(listError, failures.single())
        assertEquals(1, reads)
        assertEquals("while away", store.messagesByThread.value.getValue("thread").single().text)
    }

    @Test fun catalogRequiresRepeatedAbsenceInTheSameScope() {
        val verifier = CatalogVerifier()
        val row = CodexThread(id = "t", preview = "materialized")
        assertTrue(verifier.observe("A", listOf(row)).isEmpty())
        assertTrue(verifier.observe("A", emptyList()).isEmpty())
        assertEquals(setOf("t"), verifier.observe("A", emptyList()))
        assertTrue(verifier.observe("A", listOf(row)).isEmpty())
        assertTrue(verifier.observe("B", emptyList()).isEmpty())
        assertTrue(verifier.observe(null, emptyList()).isEmpty())
    }

    @Test fun completionNeedsPositiveEvidenceAndLiveTimestamp() {
        val logic = TurnCompletionNotificationLogic
        fun attention(status: JSONValue) = logic.attentionKindFromTerminalState(
            logic.parseTurnTerminalState(mapOf("turn" to obj("status" to status)), null))
        assertNull(attention(s("inProgress")))
        assertNull(attention(obj("type" to s("futureStatus"))))
        assertNull(attention(JSONValue.Null))
        assertEquals(RunCompletionAttentionKind.Failed, attention(s("failed")))
        assertEquals(RunCompletionAttentionKind.Completed, attention(s("completed")))
        for (key in listOf("remodexRolloutTerminalCatchUp","remodexReplayedEvent","remodexRolloutBootstrapReplay"))
            assertFalse(logic.freshLiveEvent(mapOf(key to JSONValue.Bool(true))))
        assertFalse(logic.freshLiveEvent(mapOf("turn" to obj("completedAtMs" to JSONValue.NumLong(300))), 350_000))
        assertTrue(logic.freshLiveEvent(mapOf("turn" to obj("completedAtMs" to JSONValue.NumLong(300))), 400))
    }

    @Test fun citationsDoNotLeakOrEatMarkdownExamples() {
        val block = "<oai-mem-citation>\nprivate attribution\n</oai-mem-citation>"
        assertEquals("Answer", filterMemoryCitation("Answer\n$block", false))
        assertEquals("Answer", filterMemoryCitation("Answer\n<oai-me", true))
        assertEquals("Answer", filterMemoryCitation("Answer\n<oai-mem-citation>\nprivate", false))
        val code = "```xml\n$block\n```"
        assertEquals(code, filterMemoryCitation(code, true))
        val inline = "`start\n$block\nend`"
        assertEquals(inline, filterMemoryCitation(inline, false))
        assertEquals("Before\nafter", filterMemoryCitation("Before\n" + block + "after", false))
    }

    @Test fun settingsRejectStaleSnapshotsAndRemainThreadScoped() {
        val defaults = ThreadModelSelection("base", "medium")
        val state = ThreadRuntimeSelection(defaults)
        fun row(epoch: String, revision: Long, time: Long, model: String) = mapOf(
            "epoch" to s(epoch), "revision" to JSONValue.NumLong(revision),"updatedAt" to JSONValue.NumLong(time),
            "model" to s(model), "reasoningEffort" to s("high"), "serviceTier" to s("priority"))
        assertTrue(state.confirm("A", defaults, row("1",2,100,"a")))
        assertFalse(state.confirm("A", defaults, row("1",1,91,"stale")))
        assertTrue(state.confirm("A", defaults, row("2",1,102,"b")))
        assertFalse(state.confirm("A", defaults, row("1",2,100,"late")))
        assertEquals("b", state.resolve("A",emptyList(),emptyList()).modelId)
        assertEquals("base", state.resolve("B",emptyList(),emptyList()).modelId)
        assertTrue(state.fastTier("A"))
        assertFalse(state.fastTier("B"))
    }

    @Test fun failedTurnCanBeContinuedOnlyOnceAndForTheTrackedTurn() {
        val store = ThreadFollowUps()
        val info = obj("responseStreamDisconnected" to obj("httpStatusCode" to JSONValue.NumLong(503)))
        fun params(error: JSONValue) = mapOf("threadId" to s("A"),"turn" to obj("id" to s("t"),
            "status" to s("failed"), "error" to obj("message" to s("failure"),"codexErrorInfo" to error)))
        store.observe("turn/completed", params(info), "other")
        store.observe("turn/completed", params(obj("responseStreamDisconnected" to obj("httpStatusCode" to JSONValue.NumLong(401)))), "t")
        assertTrue(store.state.value.isEmpty())
        store.observe("turn/completed",params(info),"t")
        val row = store.state.value.getValue("A").single()
        store.consume("A",row)
        store.observe("turn/completed",params(info),"t")
        assertFailsWith<IllegalStateException> { store.consume("A",row) }
        store.observe("turn/started",mapOf("threadId" to s("A"),"turnId" to s("new")),"new")
        assertTrue(store.state.value.isEmpty())
    }

    @Test fun guardianRetryIsExplicitScopedAndExpires() {
        var now = 2_000_000L
        val store = ThreadFollowUps { now }
        val p = mapOf("threadId" to s("A"),"turnId" to s("turn"),"reviewId" to s("review"),
            "review" to obj("status" to s("denied"), "rationale" to s("needs review")),
            "action" to obj("type" to s("command"), "source" to s("shell"), "cwd" to s("/fixture"), "command" to s("pwd")),
            "startedAtMs" to JSONValue.NumLong(now-4), "completedAtMs" to JSONValue.NumLong(now))
        fun observe(fields: Map<String, JSONValue>) = store.observe("item/autoApprovalReview/completed", fields, "turn")
        observe(p + ("remodexReplayedEvent" to JSONValue.Bool(true)))
        assertTrue(store.state.value.isEmpty())
        observe(p + ("remodexDesktopIpcMirror" to JSONValue.Bool(true)))
        assertFalse(store.state.value.getValue("A").single().canAct)
        store.clear(); observe(p)
        val row = store.state.value.getValue("A").single()
        assertFailsWith<IllegalStateException> { store.consume("B",row) }
        val event = store.consume("A",row)?.objectValue
        assertEquals("pwd",event?.get("action")?.objectValue?.get("command")?.stringValue)
        assertFailsWith<IllegalStateException> { store.consume("A",row) }
        store.clear(); observe(p); now += 900_001
        assertFailsWith<IllegalStateException> { store.consume("A",row) }
    }

    @Test fun deniedPermissionReviewPreservesPathsAndRejectsUnknownRights() {
        fun review(profile: JSONValue): Pair<ThreadFollowUps, com.remodex.mobile.data.ThreadFollowUp> {
            val store = ThreadFollowUps()
            store.observe("item/autoApprovalReview/completed", mapOf(
                "threadId" to s("A"), "turnId" to s("t"), "reviewId" to s("r"),
                "startedAtMs" to JSONValue.NumLong(System.currentTimeMillis()),
                "review" to obj("status" to s("denied")),
                "action" to obj("type" to s("requestPermissions"), "permissions" to profile),
            ), "t")
            return store to store.state.value.getValue("A").single()
        }
        val fs = obj("read" to JSONValue.Arr(listOf(s("/fixture"))), "globScanMaxDepth" to JSONValue.NumLong(2))
        val (store, row) = review(obj("fileSystem" to fs))
        val permissions = store.consume("A", row)?.objectValue?.get("action")?.objectValue?.get("permissions")
        val actual = permissions?.objectValue?.get("file_system")?.objectValue
        assertEquals(2L, actual?.get("glob_scan_max_depth")?.longValue)
        val entry = actual?.get("entries")?.arrayValue?.single()?.objectValue
        assertEquals("read", entry?.get("access")?.stringValue)
        assertEquals("/fixture", entry?.get("path")?.objectValue?.get("path")?.stringValue)
        assertFalse(review(obj("futureRight" to JSONValue.Bool(true))).second.canAct)
    }

    @Test fun patchBatchIsNewestFirstAndConflictNeverApplies() = runTest {
        val capture = TurnPatchCapture()
        val p = mapOf("threadId" to s("A"), "turnId" to s("t"))
        capture.observe("turn/started",p,"/fixture")
        val patch = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-old\n+new\n"
        for (id in listOf("1","1","2")) {
            capture.observe("item/completed",p + ("item" to obj("id" to s(id),"type" to s("fileChange"),"status" to s("completed"),
                "changes" to JSONValue.Arr(listOf(obj("diff" to s(patch)))))), "/fixture")
        }
        val row = assertNotNull(capture.observe("turn/completed",p + ("turn" to obj("status" to s("completed"))),"/fixture"))
        assertEquals(listOf("1","2"),row.fallbackPatchBatches.map { it.id })
        val methods = mutableListOf<String>()
        val service = AiChangeSetRevertService(MinimalSendRepository { method, params ->
            methods += method
            assertEquals(listOf("2","1"),params?.objectValue?.get("patches")?.arrayValue?.map { it.objectValue?.get("id")?.stringValue })
            RPCMessage.success(s("1"),obj("canRevert" to JSONValue.Bool(false)))
        })
        assertFalse(service.apply(row,"/fixture").success)
        assertEquals(listOf("workspace/revertPatchBatchPreview"),methods)
    }
}
