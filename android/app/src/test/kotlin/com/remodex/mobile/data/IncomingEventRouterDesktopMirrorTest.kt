package com.remodex.mobile.data

import com.remodex.mobile.core.model.CodexMessageKind
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.core.model.CodexPlanStepStatus
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.PendingApprovalDecision
import com.remodex.mobile.core.model.PendingApprovalRequest
import com.remodex.mobile.core.model.PendingStructuredInputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingEventRouterDesktopMirrorTest {
    @Test
    fun desktopUserMessageWithoutTurnStart_marksThreadRunningFallback() =
        runTest {
            val lifecycle = mutableListOf<Pair<String, String?>>()
            val router = newRouter(onTurnLifecycle = { threadId, turnId -> lifecycle += threadId to turnId })

            router.dispatchNotification(
                method = "codex/event/user_message",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "message" to JSONValue.Str("Prompt from PC"),
                        ),
                    ),
            )

            assertEquals(listOf<Pair<String, String?>>("thread-1" to null), lifecycle)
        }

    @Test
    fun desktopUserMessageWithTurnId_marksThreadRunningWithTurn() =
        runTest {
            val lifecycle = mutableListOf<Pair<String, String?>>()
            val router = newRouter(onTurnLifecycle = { threadId, turnId -> lifecycle += threadId to turnId })

            router.dispatchNotification(
                method = "codex/event/user_message",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "turnId" to JSONValue.Str("turn-1"),
                            "message" to JSONValue.Str("Prompt from PC"),
                        ),
                    ),
            )

            assertEquals(listOf<Pair<String, String?>>("thread-1" to "turn-1"), lifecycle)
        }

    @Test
    fun desktopUserMessageWithTimestamp_ordersBeforeLaterAssistantWhenTurnIdMissing() =
        runTest {
            val timeline = MessageTimelineStore()
            val router = newRouter(messageTimeline = timeline)

            timeline.completeAssistantMessage(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "assistant-1",
                text = "Final answer",
            )

            router.dispatchNotification(
                method = "codex/event/user_message",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "message" to JSONValue.Str("Prompt from PC"),
                            "timestamp" to JSONValue.Str("1970-01-01T00:00:00Z"),
                        ),
                    ),
            )
            advanceUntilIdle()

            val messages = timeline.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(listOf(CodexMessageRole.user, CodexMessageRole.assistant), messages.map { it.role })
            assertEquals("Prompt from PC", messages[0].text)
            assertEquals("Final answer", messages[1].text)
        }

    @Test
    fun desktopUserBeforeTurnStart_isAttachedToTurnAndAssistantPlaceholderStreamsUntilFinal() =
        runTest {
            val timeline = MessageTimelineStore()
            val router = newRouter(messageTimeline = timeline)

            router.dispatchNotification(
                method = "codex/event/user_message",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "message" to JSONValue.Str("Prompt from PC"),
                        ),
                    ),
            )

            router.dispatchNotification(
                method = "turn/started",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "turnId" to JSONValue.Str("turn-1"),
                        ),
                    ),
            )

            val streaming = timeline.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(listOf(CodexMessageRole.user, CodexMessageRole.assistant), streaming.map { it.role })
            assertEquals(listOf("turn-1", "turn-1"), streaming.map { it.turnId })
            assertEquals("", streaming[1].text)
            assertEquals(true, streaming[1].isStreaming)

            router.dispatchNotification(
                method = "codex/event/agent_message",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "turnId" to JSONValue.Str("turn-1"),
                            "itemId" to JSONValue.Str("assistant-1"),
                            "message" to JSONValue.Str("Final answer"),
                        ),
                    ),
            )

            val finalMessages = timeline.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(2, finalMessages.size)
            assertEquals("Prompt from PC", finalMessages[0].text)
            assertEquals("Final answer", finalMessages[1].text)
            assertFalse(finalMessages[1].isStreaming)
            assertEquals("assistant-1", finalMessages[1].itemId)
        }

    @Test
    fun desktopAgentDeltaWithoutTurnId_streamsAssistantMessageByItemId() =
        runTest {
            val timeline = MessageTimelineStore()
            val lifecycle = mutableListOf<Pair<String, String?>>()
            val router =
                newRouter(
                    messageTimeline = timeline,
                    onTurnLifecycle = { threadId, turnId -> lifecycle += threadId to turnId },
                )

            router.dispatchNotification(
                method = "turn/started",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "turnId" to JSONValue.Str("turn-1"),
                        ),
                    ),
            )
            router.dispatchNotification(
                method = "codex/event/agent_message_delta",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "itemId" to JSONValue.Str("assistant-1"),
                            "delta" to JSONValue.Str("live"),
                        ),
                    ),
            )
            router.dispatchNotification(
                method = "codex/event/agent_message_delta",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "itemId" to JSONValue.Str("assistant-1"),
                            "delta" to JSONValue.Str(" text"),
                        ),
                    ),
            )

            val messages = timeline.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(1, messages.size)
            assertEquals(CodexMessageRole.assistant, messages.single().role)
            assertEquals("live text", messages.single().text)
            assertEquals("turn-1", messages.single().turnId)
            assertEquals("assistant-1", messages.single().itemId)
            assertEquals(true, messages.single().isStreaming)
            assertEquals("thread-1" to null, lifecycle.last())
        }

    @Test
    fun codexEventAgentEnvelopeAfterCommand_appendsLaterAssistantMessage() =
        runTest {
            val timeline = MessageTimelineStore()
            val finished = mutableListOf<String>()
            val router =
                newRouter(
                    messageTimeline = timeline,
                    onTurnFinished = { threadId -> finished += threadId },
                )

            router.dispatchNotification(
                method = "turn/started",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "turnId" to JSONValue.Str("turn-1"),
                        ),
                    ),
            )
            router.dispatchNotification(
                method = "codex/event/agent_message",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "turnId" to JSONValue.Str("turn-1"),
                            "itemId" to JSONValue.Str("assistant-1"),
                            "message" to JSONValue.Str("First answer"),
                        ),
                    ),
            )
            router.dispatchNotification(
                method = "codex/event",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "msg" to
                                JSONValue.Obj(
                                    mapOf(
                                        "type" to JSONValue.Str("exec_command_begin"),
                                        "threadId" to JSONValue.Str("thread-1"),
                                        "turnId" to JSONValue.Str("turn-1"),
                                        "itemId" to JSONValue.Str("cmd-1"),
                                        "command" to JSONValue.Str("echo hi"),
                                    ),
                                ),
                        ),
                    ),
            )
            router.dispatchNotification(
                method = "codex/event",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "msg" to
                                JSONValue.Obj(
                                    mapOf(
                                        "type" to JSONValue.Str("exec_command_end"),
                                        "threadId" to JSONValue.Str("thread-1"),
                                        "turnId" to JSONValue.Str("turn-1"),
                                        "itemId" to JSONValue.Str("cmd-1"),
                                        "command" to JSONValue.Str("echo hi"),
                                    ),
                                ),
                        ),
                    ),
            )
            router.dispatchNotification(
                method = "codex/event",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "msg" to
                                JSONValue.Obj(
                                    mapOf(
                                        "type" to JSONValue.Str("agent_message_delta"),
                                        "threadId" to JSONValue.Str("thread-1"),
                                        "turnId" to JSONValue.Str("turn-1"),
                                        "itemId" to JSONValue.Str("assistant-2"),
                                        "delta" to JSONValue.Str("Second"),
                                    ),
                                ),
                        ),
                    ),
            )
            router.dispatchNotification(
                method = "codex/event",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "msg" to
                                JSONValue.Obj(
                                    mapOf(
                                        "type" to JSONValue.Str("agent_message_delta"),
                                        "threadId" to JSONValue.Str("thread-1"),
                                        "turnId" to JSONValue.Str("turn-1"),
                                        "itemId" to JSONValue.Str("assistant-2"),
                                        "delta" to JSONValue.Str(" answer"),
                                    ),
                                ),
                        ),
                    ),
            )
            advanceUntilIdle()

            val messages = timeline.messagesByThread.value["thread-1"].orEmpty()
            val assistantMessages = messages.filter { it.role == CodexMessageRole.assistant }
            assertEquals(emptyList(), finished)
            assertEquals(listOf("First answer", "Second answer"), assistantMessages.map { it.text })
            assertEquals(listOf(false, true), assistantMessages.map { it.isStreaming })
            assertEquals(listOf("assistant-1", "assistant-2"), assistantMessages.map { it.itemId })
            assertEquals(
                listOf(CodexMessageKind.chat, CodexMessageKind.commandExecution, CodexMessageKind.chat),
                messages.map { it.kind },
            )
        }

    @Test
    fun itemCompletedUserWithNestedTurnId_insertsBeforeSameTurnAssistant() =
        runTest {
            val timeline = MessageTimelineStore()
            val router = newRouter(messageTimeline = timeline)

            router.dispatchNotification(
                method = "item/completed",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "item" to
                                JSONValue.Obj(
                                    mapOf(
                                        "id" to JSONValue.Str("assistant-1"),
                                        "type" to JSONValue.Str("agent_message"),
                                        "turnId" to JSONValue.Str("turn-1"),
                                        "text" to JSONValue.Str("answer one"),
                                    ),
                                ),
                        ),
                    ),
            )
            router.dispatchNotification(
                method = "item/completed",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "item" to
                                JSONValue.Obj(
                                    mapOf(
                                        "id" to JSONValue.Str("user-1"),
                                        "type" to JSONValue.Str("user_message"),
                                        "turnId" to JSONValue.Str("turn-1"),
                                        "text" to JSONValue.Str("prompt one"),
                                    ),
                                ),
                        ),
                    ),
            )

            val messages = timeline.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(listOf(CodexMessageRole.user, CodexMessageRole.assistant), messages.map { it.role })
            assertEquals(listOf("prompt one", "answer one"), messages.map { it.text })
            assertEquals(listOf("turn-1", "turn-1"), messages.map { it.turnId })
        }

    @Test
    fun desktopBackgroundEvent_addsLiveThinkingWorkRow() =
        runTest {
            val timeline = MessageTimelineStore()
            val lifecycle = mutableListOf<Pair<String, String?>>()
            val router =
                newRouter(
                    messageTimeline = timeline,
                    onTurnLifecycle = { threadId, turnId -> lifecycle += threadId to turnId },
                )

            router.dispatchNotification(
                method = "turn/started",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "turnId" to JSONValue.Str("turn-1"),
                        ),
                    ),
            )
            router.dispatchNotification(
                method = "codex/event/background_event",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "turnId" to JSONValue.Str("turn-1"),
                            "call_id" to JSONValue.Str("patch-1"),
                            "message" to JSONValue.Str("Applying patch"),
                        ),
                    ),
            )

            val thinking = timeline.messagesByThread.value["thread-1"].orEmpty()
                .single { it.kind == CodexMessageKind.thinking }
            assertEquals("Applying patch", thinking.text)
            assertEquals("patch-1", thinking.itemId)
            assertEquals(true, thinking.isStreaming)
            assertEquals("thread-1" to "turn-1", lifecycle.last())
        }

    @Test
    fun codexEventPlanUpdateEnvelope_addsStreamingPlanAndMarksTurnRunning() =
        runTest {
            val timeline = MessageTimelineStore()
            val lifecycle = mutableListOf<Pair<String, String?>>()
            val router =
                newRouter(
                    messageTimeline = timeline,
                    onTurnLifecycle = { threadId, turnId -> lifecycle += threadId to turnId },
                )

            router.dispatchNotification(
                method = "codex/event",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "msg" to
                                JSONValue.Obj(
                                    mapOf(
                                        "type" to JSONValue.Str("plan_update"),
                                        "threadId" to JSONValue.Str("thread-1"),
                                        "turnId" to JSONValue.Str("turn-1"),
                                        "explanation" to JSONValue.Str("Plan first"),
                                        "plan" to
                                            JSONValue.Arr(
                                                listOf(
                                                    JSONValue.Obj(
                                                        mapOf(
                                                            "step" to JSONValue.Str("Inspect"),
                                                            "status" to JSONValue.Str("in_progress"),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )

            val plan = timeline.messagesByThread.value["thread-1"].orEmpty().single()
            assertEquals(CodexMessageKind.plan, plan.kind)
            assertEquals(true, plan.isStreaming)
            assertEquals("Plan first", plan.planState?.explanation)
            assertEquals(CodexPlanStepStatus.inProgress, plan.planState?.steps?.single()?.status)
            assertEquals(listOf<Pair<String, String?>>("thread-1" to "turn-1"), lifecycle)
        }

    @Test
    fun planUpdateObjectShape_rendersThroughPlanMessage() =
        runTest {
            val timeline = MessageTimelineStore()
            val router = newRouter(messageTimeline = timeline)

            router.dispatchNotification(
                method = "turn/plan/updated",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "turnId" to JSONValue.Str("turn-1"),
                            "plan" to
                                JSONValue.Obj(
                                    mapOf(
                                        "explanation" to JSONValue.Str("Use the dedicated plan UI"),
                                        "steps" to
                                            JSONValue.Arr(
                                                listOf(
                                                    JSONValue.Obj(
                                                        mapOf(
                                                            "text" to JSONValue.Str("Render card"),
                                                            "status" to JSONValue.Str("running"),
                                                        ),
                                                    ),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )

            val plan = timeline.messagesByThread.value["thread-1"].orEmpty().single()
            assertEquals(CodexMessageKind.plan, plan.kind)
            assertEquals("Use the dedicated plan UI", plan.planState?.explanation)
            assertEquals("Render card", plan.planState?.steps?.single()?.step)
            assertEquals(CodexPlanStepStatus.inProgress, plan.planState?.steps?.single()?.status)
        }

    @Test
    fun proposedPlanStructuredInput_isAddedAsPlanMessage() =
        runTest {
            val timeline = MessageTimelineStore()
            val pending = kotlinx.coroutines.CompletableDeferred<PendingStructuredInputRequest>()
            val router =
                newRouter(
                    messageTimeline = timeline,
                    onStructuredInputRequest = { request, _ -> pending.complete(request) },
                )

            router.dispatchServerRequest(
                method = "request_user_input",
                requestId = JSONValue.Str("proposed-plan-1"),
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "turnId" to JSONValue.Str("turn-1"),
                            "questions" to
                                JSONValue.Arr(
                                    listOf(
                                        JSONValue.Obj(
                                            mapOf(
                                                "id" to JSONValue.Str("approval"),
                                                "header" to JSONValue.Str("Proposed plan"),
                                                "question" to JSONValue.Str("1. Inspect\n2. Patch\n3. Verify"),
                                            ),
                                        ),
                                    ),
                                ),
                        ),
                ),
                respond = {},
            )
            pending.await()
            advanceUntilIdle()

            val plan = timeline.messagesByThread.value["thread-1"].orEmpty().single()
            assertEquals(CodexMessageKind.plan, plan.kind)
            assertEquals("1. Inspect\n2. Patch\n3. Verify", plan.text)
            assertEquals("\"proposed-plan-1\"", plan.structuredUserInputRequest?.requestID?.stringValue)
        }

    @Test
    fun desktopFinalAgentMessageWithoutTurnCompleted_clearsRunningFallback() =
        runTest {
            val finished = mutableListOf<String>()
            val router = newRouter(onTurnFinished = { threadId -> finished += threadId })

            router.dispatchNotification(
                method = "codex/event/user_message",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "message" to JSONValue.Str("Prompt from PC"),
                        ),
                    ),
            )
            router.dispatchNotification(
                method = "codex/event/agent_message",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "threadId" to JSONValue.Str("thread-1"),
                            "message" to JSONValue.Str("Final answer"),
                        ),
                    ),
            )

            assertEquals(listOf("thread-1"), finished)
        }

    @Test
    fun codexEventTaskCompleteEnvelope_clearsRunningTurn() =
        runTest {
            val finished = mutableListOf<String>()
            val router = newRouter(onTurnFinished = { threadId -> finished += threadId })

            router.dispatchNotification(
                method = "codex/event",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "msg" to
                                JSONValue.Obj(
                                    mapOf(
                                        "type" to JSONValue.Str("task_complete"),
                                        "threadId" to JSONValue.Str("thread-1"),
                                        "turn_id" to JSONValue.Str("turn-1"),
                                    ),
                                ),
                        ),
                    ),
            )

            assertEquals(listOf("thread-1"), finished)
        }

    @Test
    fun completedAndLegacyDesktopTurnsRefreshThreadMetadata() = runTest {
        for (method in listOf("turn/completed", "codex/event", "codex/event/agent_message")) {
            var syncs = 0
            val router = newRouter(onRequestThreadSync = { syncs++ })
            val params = mutableMapOf<String, JSONValue>(
                "threadId" to JSONValue.Str("thread-1"),
                "message" to JSONValue.Str("Final answer"),
            )
            if (method != "codex/event/agent_message") params["turnId"] = JSONValue.Str("turn-1")
            if (method == "codex/event") params["msg"] = JSONValue.Obj(mapOf("type" to JSONValue.Str("task_complete")))
            router.dispatchNotification(method, JSONValue.Obj(params))
            assertEquals(1, syncs, method)
        }
    }

    @Test fun statusNotificationsUpdateUnopenedThreadsWithoutSelectingOrLoadingHistory() = runTest {
        val running = mutableListOf<Pair<String, String?>>()
        val finished = mutableListOf<String>()
        var listRequests = 0
        val router = newRouter(onTurnLifecycle = { id, turn -> running += id to turn },
            onTurnFinished = { finished += it }, onRequestThreadSync = { listRequests++ })
        fun status(type: String) = JSONValue.Obj(mapOf("threadId" to JSONValue.Str("unopened"),
            "status" to JSONValue.Obj(mapOf("type" to JSONValue.Str(type)))))
        router.dispatchNotification("thread/status/changed", status("active"))
        assertEquals(listOf<Pair<String, String?>>("unopened" to null), running)
        router.dispatchNotification("thread/status/changed", status("idle"))
        assertEquals(listOf("unopened"), finished)
        router.dispatchNotification("thread/status/changed", status("systemError"))
        assertEquals(listOf("unopened", "unopened"), finished)
        assertEquals(0, listRequests)
    }

    @Test fun missingUnloadedAndUnknownStatusDoNotInventCompletion() = runTest {
        val changed = mutableListOf<String>()
        val router = newRouter(onTurnLifecycle = { id, _ -> changed += id }, onTurnFinished = { changed += it })
        for (type in listOf("notLoaded", "future-state", "")) {
            router.dispatchNotification("thread/status/changed", JSONValue.Obj(mapOf("threadId" to JSONValue.Str("a"),
                "status" to JSONValue.Obj(mapOf("type" to JSONValue.Str(type))))))
        }
        router.dispatchNotification("thread/status/changed", JSONValue.Obj(mapOf("status" to JSONValue.Obj(mapOf("type" to JSONValue.Str("active"))))))
        router.dispatchNotification("thread/status/changed", JSONValue.Obj(mapOf("threadId" to JSONValue.Str("a"))))
        assertEquals(emptyList(), changed)
    }

    @Test fun actualCompletionProvidesStableUnreadIdentityButIdleStatusDoesNot() = runTest {
        val completed = mutableListOf<Pair<String, String>>()
        val router = newRouter(onTurnCompletedActivity = { thread, turn -> completed += thread to turn })
        router.dispatchNotification("thread/status/changed", JSONValue.Obj(mapOf("threadId" to JSONValue.Str("a"),
            "status" to JSONValue.Obj(mapOf("type" to JSONValue.Str("idle"))))))
        assertEquals(emptyList(), completed)
        router.dispatchNotification("turn/completed", JSONValue.Obj(mapOf("threadId" to JSONValue.Str("a"),
            "turn" to JSONValue.Obj(mapOf("id" to JSONValue.Str("turn-1"))))))
        router.dispatchNotification("codex/event", JSONValue.Obj(mapOf("threadId" to JSONValue.Str("a"),
            "turnId" to JSONValue.Str("turn-2"), "msg" to JSONValue.Obj(mapOf("type" to JSONValue.Str("task_complete"))))))
        assertEquals(listOf("a" to "turn-1", "a" to "turn-2"), completed)
    }

    private fun newRouter(
        messageTimeline: MessageTimelineStore = MessageTimelineStore(),
        onTurnLifecycle: (threadId: String, turnId: String?) -> Unit = { _, _ -> },
        onTurnFinished: (threadId: String) -> Unit = {},
        onRequestThreadSync: () -> Unit = {},
        onTurnCompletedActivity: (String, String) -> Unit = { _, _ -> },
        onStructuredInputRequest: (PendingStructuredInputRequest, (Map<String, List<String>>) -> Unit) -> Unit =
            { _: PendingStructuredInputRequest, _: (Map<String, List<String>>) -> Unit -> },
    ): IncomingEventRouter =
        IncomingEventRouter(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            threads = MutableStateFlow<List<CodexThread>>(emptyList()),
            activeThreadId = MutableStateFlow(null),
            messageTimeline = messageTimeline,
            commandDetailsStore = CommandExecutionDetailsStore(),
            onRequestThreadSync = onRequestThreadSync,
            onHydrateThread = {},
            onTurnLifecycle = onTurnLifecycle,
            onTurnFinished = onTurnFinished,
            onTurnCompletedActivity = onTurnCompletedActivity,
            isTurnStreamingActive = { _, _ -> true },
            shouldAutoApproveRequests = { false },
            onApprovalRequest = { _: PendingApprovalRequest, _: (PendingApprovalDecision) -> Unit -> },
            onStructuredInputRequest = onStructuredInputRequest,
        )
}
