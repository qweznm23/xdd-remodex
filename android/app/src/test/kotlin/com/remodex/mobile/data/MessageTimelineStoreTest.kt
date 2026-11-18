package com.remodex.mobile.data

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageDeliveryState
import com.remodex.mobile.core.model.CodexImageAttachment
import com.remodex.mobile.core.model.CodexMessageKind
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.ui.agent.TimelineListItem
import com.remodex.mobile.ui.agent.toTimelineListItems
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MessageTimelineStoreTest {
    @Test
    fun lateClosingBracketAfterCompletionDoesNotHideFullAnswer() = runTest {
        val store = MessageTimelineStore()
        val answer = "已修复历史问题回复。\n\n[下载 APK](http://example.test/app.apk)"
        store.appendAssistantDelta("thread-1", "turn-1", "item-1", answer.dropLast(1))
        store.completeAssistantMessage("thread-1", "turn-1", "item-1", answer, assistantPhase = "final_answer")
        store.appendAssistantDelta("thread-1", "turn-1", "item-1", ")")
        store.finishStreamingMessages("thread-1", "turn-1")

        val visible = store.messagesByThread.value.getValue("thread-1").toTimelineListItems()
            .filterIsInstance<TimelineListItem.Single>()
        assertEquals(listOf(answer), visible.map { it.message.text })
        assertEquals(1, store.messagesByThread.value.getValue("thread-1").size)
    }

    @Test
    fun lateDeltaAfterTurnFinishContinuesSameItemUntilFullCompletion() = runTest {
        val store = MessageTimelineStore()
        store.appendAssistantDelta("thread-1", "turn-1", "item-1", "完整回复(")
        val rowId = store.messagesByThread.value.getValue("thread-1").single().id
        store.finishStreamingMessages("thread-1", "turn-1")
        store.appendAssistantDelta("thread-1", "turn-1", "item-1", ")")
        assertEquals("完整回复()", store.messagesByThread.value.getValue("thread-1").single().text)
        store.completeAssistantMessage("thread-1", "turn-1", "item-1", "完整回复()")
        assertEquals(rowId, store.messagesByThread.value.getValue("thread-1").single().id)
    }

    @Test
    fun completedItemStaysCompleteAfterPersistenceAndDoesNotBlockNextItem() = runTest {
        val store = MessageTimelineStore()
        store.completeAssistantMessage("thread-1", "turn-1", "item-1", "完整回复()")
        val persisted = Json.encodeToString(store.messagesByThread.value.getValue("thread-1"))
        val restored = MessageTimelineStore(mapOf("thread-1" to Json.decodeFromString<List<CodexMessage>>(persisted)))
        restored.appendAssistantDelta("thread-1", "turn-1", "item-1", ")")
        restored.appendAssistantDelta("thread-1", "turn-2", "item-2", "下一条回复")
        val rows = restored.messagesByThread.value.getValue("thread-1")
        assertEquals(listOf("完整回复()", "下一条回复"), rows.map { it.text })
        assertEquals(listOf(false, true), rows.map { it.isStreaming })
    }

    @Test
    fun lateDeltaDoesNotAppendToUnrelatedCompletedItemWithoutId() = runTest {
        val store = MessageTimelineStore()
        store.completeAssistantMessage("thread-1", "turn-1", null, "上一条完整回复")
        store.appendAssistantDelta("thread-1", "turn-1", "next-item", "下一条回复")
        assertEquals(listOf("上一条完整回复", "下一条回复"), store.messagesByThread.value.getValue("thread-1").map { it.text })
    }

    @Test
    fun rolloutMirrorDoesNotReplaceNativeIdentityBeforeLateDelta() = runTest {
        val nativeId = "msg-native"
        val mirrorId = "rollout-agent-message:thread-1:turn-1:timestamp:hash"
        for (ids in listOf(listOf(nativeId, mirrorId), listOf(mirrorId, nativeId))) {
            val store = MessageTimelineStore()
            ids.forEach { store.completeAssistantMessage("thread-1", "turn-1", it, "完整回复()") }
            store.appendAssistantDelta("thread-1", "turn-1", nativeId, ")")
            val row = store.messagesByThread.value.getValue("thread-1").single()
            assertEquals("完整回复()", row.text)
            assertEquals(nativeId, row.itemId)
            assertEquals(false, row.isStreaming)
        }
    }

    @Test
    fun init_dedupesPersistedPhotoUserEchoRows() {
        val payload = "data:image/jpeg;base64,abc"
        val firstAttachment =
            CodexImageAttachment(
                id = "persisted-photo-1",
                thumbnailBase64JPEG = "thumb-1",
                payloadDataURL = payload,
                sourceURL = "content://picked/photo",
            )
        val secondAttachment =
            CodexImageAttachment(
                id = "persisted-photo-2",
                thumbnailBase64JPEG = "thumb-2",
                payloadDataURL = payload,
                sourceURL = payload,
            )
        val store =
            MessageTimelineStore(
                initialMessages =
                    mapOf(
                        "thread-1" to
                            listOf(
                                userMessage(
                                    id = "persisted-user-1",
                                    itemId = "user-input",
                                    attachments = listOf(firstAttachment),
                                ),
                                userMessage(
                                    id = "persisted-user-2",
                                    itemId = "user-message",
                                    attachments = listOf(secondAttachment),
                                ),
                            ),
                    ),
            )

        val messages = store.messagesByThread.value["thread-1"].orEmpty()
        assertEquals(1, messages.size)
        assertEquals("persisted-user-1", messages.single().id)
        assertEquals("user-message", messages.single().itemId)
        assertEquals(listOf(firstAttachment), messages.single().attachments)
    }

    @Test
    fun completeAssistantMessage_mergesStreamingRowWhenCompletionAddsItemId() =
        runTest {
            val store = MessageTimelineStore()

            store.appendAssistantDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                delta = "prova",
            )
            store.completeAssistantMessage(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "item-1",
                text = "prova ok",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(1, messages.size)
            assertEquals(CodexMessageRole.assistant, messages.single().role)
            assertEquals("prova ok", messages.single().text)
            assertEquals("item-1", messages.single().itemId)
        }

    @Test
    fun appendAssistantDelta_preservesDeltaSpacing() =
        runTest {
            val store = MessageTimelineStore()

            store.appendAssistantDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "item-1",
                delta = "hello",
            )
            store.appendAssistantDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "item-1",
                delta = " world",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals("hello world", messages.single().text)
            assertEquals(true, messages.single().isStreaming)
        }

    @Test
    fun appendAssistantDelta_withItemIdOnlyAbsorbsEmptyTurnPlaceholder() =
        runTest {
            val store = MessageTimelineStore()

            store.ensureStreamingAssistantPlaceholder(
                threadId = "thread-1",
                turnId = "turn-1",
            )
            store.appendAssistantDelta(
                threadId = "thread-1",
                turnId = null,
                itemId = "assistant-1",
                delta = "live text",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(1, messages.size)
            assertEquals("live text", messages.single().text)
            assertEquals("turn-1", messages.single().turnId)
            assertEquals("assistant-1", messages.single().itemId)
            assertEquals(true, messages.single().isStreaming)
        }

    @Test
    fun appendAssistantDelta_preservesExistingTurnStreamWhenLaterDeltaAddsItemId() =
        runTest {
            val store = MessageTimelineStore()

            store.appendAssistantDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                delta = "live",
            )
            store.appendAssistantDelta(
                threadId = "thread-1",
                turnId = null,
                itemId = "assistant-1",
                delta = " text",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(1, messages.size)
            assertEquals("live text", messages.single().text)
            assertEquals("turn-1", messages.single().turnId)
            assertEquals("assistant-1", messages.single().itemId)
        }

    @Test
    fun appendAssistantDelta_finishesPreviousAssistantWhenLiveItemIdChanges() =
        runTest {
            val store = MessageTimelineStore()

            store.appendAssistantDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "assistant-temp",
                delta = "live",
            )
            store.appendAssistantDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "assistant-real",
                delta = " text",
            )
            store.completeAssistantMessage(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "assistant-real",
                text = "text final",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(2, messages.size)
            assertEquals("live", messages[0].text)
            assertEquals("assistant-temp", messages[0].itemId)
            assertEquals(false, messages[0].isStreaming)
            assertEquals("text final", messages[1].text)
            assertEquals("turn-1", messages[1].turnId)
            assertEquals("assistant-real", messages[1].itemId)
            assertEquals(false, messages[1].isStreaming)
        }

    @Test
    fun finishStreamingMessages_marksTextRowsReadyAndRemovesEmptyAssistantPlaceholder() =
        runTest {
            val store = MessageTimelineStore()

            store.ensureStreamingAssistantPlaceholder(
                threadId = "thread-1",
                turnId = "turn-empty",
            )
            store.appendAssistantDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "assistant-1",
                delta = "partial",
            )

            store.finishStreamingMessages(threadId = "thread-1", turnId = "turn-empty")
            store.finishStreamingMessages(threadId = "thread-1", turnId = "turn-1")

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(1, messages.size)
            assertEquals("partial", messages.single().text)
            assertEquals(false, messages.single().isStreaming)
        }

    @Test
    fun appendMirroredUser_mergesPhotoEchoWithDifferentAttachmentMetadata() =
        runTest {
            val store = MessageTimelineStore()
            val payload = "data:image/jpeg;base64,abc"
            val localAttachment =
                CodexImageAttachment(
                    id = "local-photo",
                    thumbnailBase64JPEG = "local-thumb",
                    payloadDataURL = payload,
                    sourceURL = "content://picked/photo",
                )
            val mirroredAttachment =
                CodexImageAttachment(
                    id = "mirrored-photo",
                    thumbnailBase64JPEG = "mirrored-thumb",
                    payloadDataURL = payload,
                    sourceURL = payload,
                )

            store.appendPendingUserMessage("thread-1", "look", listOf(localAttachment))
            store.appendMirroredUser("thread-1", "turn-1", "look", listOf(mirroredAttachment))

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(1, messages.size)
            assertEquals(CodexMessageDeliveryState.confirmed, messages.single().deliveryState)
            assertEquals("turn-1", messages.single().turnId)
            assertEquals(listOf(localAttachment), messages.single().attachments)
        }

    @Test
    fun completeAssistantMessage_keepsRepeatedNoIdCompletions() =
        runTest {
            val store = MessageTimelineStore()

            store.completeAssistantMessage(
                threadId = "thread-1",
                turnId = null,
                itemId = null,
                text = "same answer",
            )
            store.completeAssistantMessage(
                threadId = "thread-1",
                turnId = null,
                itemId = null,
                text = "same answer",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(2, messages.size)
            assertEquals(listOf("same answer", "same answer"), messages.map { it.text })
        }

    @Test
    fun completeAssistantMessage_keepsDistinctSameTurnNoItemCompletions() =
        runTest {
            val store = MessageTimelineStore()

            store.completeAssistantMessage(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                text = "first answer",
            )
            store.completeAssistantMessage(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                text = "second answer",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(2, messages.size)
            assertEquals(listOf("first answer", "second answer"), messages.map { it.text })
            assertEquals(listOf("turn-1", "turn-1"), messages.map { it.turnId })
        }

    @Test
    fun completeSystemItem_mergesStreamingReasoningRowWhenCompletionAddsItemId() =
        runTest {
            val store = MessageTimelineStore()

            store.appendStreamingSystemItemDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                kind = CodexMessageKind.thinking,
                delta = "Thinking...",
            )
            store.completeSystemItem(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "reasoning-1",
                kind = CodexMessageKind.thinking,
                text = "Thinking final",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(1, messages.size)
            assertEquals(CodexMessageKind.thinking, messages.single().kind)
            assertEquals("Thinking final", messages.single().text)
            assertEquals("reasoning-1", messages.single().itemId)
        }

    @Test
    fun fileChangeSnapshot_mergesSamePathRowsAcrossItemIds() =
        runTest {
            val store = MessageTimelineStore()

            store.upsertStreamingSystemItemSnapshot(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "file-1",
                kind = CodexMessageKind.fileChange,
                snapshot = "Status: running\n\nPath: src/App.kt\nKind: update\nTotals: +1 -0",
            )
            store.upsertStreamingSystemItemSnapshot(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "file-2",
                kind = CodexMessageKind.fileChange,
                snapshot = "Status: completed\n\nPath: ./src/App.kt\nKind: update\nTotals: +2 -1",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(1, messages.size)
            assertEquals("file-2", messages.single().itemId)
            assertEquals(true, messages.single().text.contains("Totals: +2 -1"))
        }

    @Test
    fun fileChangeSnapshot_keepsDistinctPathsInSameTurn() =
        runTest {
            val store = MessageTimelineStore()

            store.upsertStreamingSystemItemSnapshot(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "file-1",
                kind = CodexMessageKind.fileChange,
                snapshot = "Status: running\n\nPath: src/App.kt\nKind: update\nTotals: +1 -0",
            )
            store.upsertStreamingSystemItemSnapshot(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "file-2",
                kind = CodexMessageKind.fileChange,
                snapshot = "Status: completed\n\nPath: src/Other.kt\nKind: update\nTotals: +2 -1",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(2, messages.size)
            assertEquals(listOf("file-1", "file-2"), messages.map { it.itemId })
        }

    @Test
    fun fileChangeCompletion_doesNotOverwriteDifferentPathStreamingRow() =
        runTest {
            val store = MessageTimelineStore()

            store.upsertStreamingSystemItemSnapshot(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "file-1",
                kind = CodexMessageKind.fileChange,
                snapshot = "Status: running\n\nPath: src/App.kt\nKind: update\nTotals: +1 -0",
            )
            store.completeSystemItem(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "file-2",
                kind = CodexMessageKind.fileChange,
                text = "Status: completed\n\nPath: src/Other.kt\nKind: update\nTotals: +2 -1",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(2, messages.size)
            assertEquals(listOf("file-1", "file-2"), messages.map { it.itemId })
        }

    @Test
    fun commandExecutionCompletion_reconcilesFailedChevronRowsAcrossItemIds() =
        runTest {
            val store = MessageTimelineStore()

            store.completeSystemItem(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "cmd-1",
                kind = CodexMessageKind.commandExecution,
                text = "running> Gradle",
            )
            store.completeSystemItem(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "cmd-2",
                kind = CodexMessageKind.commandExecution,
                text = "failed> Gradle",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(1, messages.size)
            assertEquals("failed> Gradle", messages.single().text)
            assertEquals("cmd-2", messages.single().itemId)
        }

    @Test
    fun appendMirroredUser_insertsBeforeExistingTurnActivityWhenUserArrivesLate() =
        runTest {
            val store = MessageTimelineStore()

            store.appendThinkingDelta(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = null,
                delta = "Thinking...",
            )
            store.appendMirroredUser(
                threadId = "thread-1",
                turnId = "turn-1",
                text = "hello from desktop",
            )

            val messages =
                store.messagesByThread.value["thread-1"]
                    .orEmpty()
            assertEquals(2, messages.size)
            assertEquals(CodexMessageRole.user, messages[0].role)
            assertEquals(CodexMessageRole.system, messages[1].role)
        }

    @Test
    fun appendMirroredUser_usesCreatedAtWhenTurnIdIsMissing() =
        runTest {
            val store = MessageTimelineStore()

            store.completeAssistantMessage(
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "assistant-1",
                text = "answer",
            )
            store.appendMirroredUser(
                threadId = "thread-1",
                turnId = null,
                text = "hello from desktop",
                createdAt = Instant.EPOCH,
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(2, messages.size)
            assertEquals(CodexMessageRole.user, messages[0].role)
            assertEquals(CodexMessageRole.assistant, messages[1].role)
        }

    @Test
    fun appendMirroredUser_withoutTurnOrTimestamp_insertsBeforeActiveAssistantTurn() =
        runTest {
            val store = MessageTimelineStore()

            store.ensureStreamingAssistantPlaceholder(
                threadId = "thread-1",
                turnId = "turn-1",
            )
            store.appendMirroredUser(
                threadId = "thread-1",
                turnId = null,
                text = "hello from desktop",
            )

            val messages = store.messagesByThread.value["thread-1"].orEmpty()
            assertEquals(2, messages.size)
            assertEquals(CodexMessageRole.user, messages[0].role)
            assertEquals(CodexMessageRole.assistant, messages[1].role)
        }

    @Test
    fun confirmLatestPendingUserMessage_marksPendingAsConfirmed() =
        runTest {
            val store = MessageTimelineStore()

            store.appendPendingUserMessage(
                threadId = "thread-1",
                text = "hello",
            )
            store.confirmLatestPendingUserMessage(
                threadId = "thread-1",
                turnId = "turn-1",
            )

            val message = store.messagesByThread.value["thread-1"].orEmpty().single()
            assertEquals(CodexMessageDeliveryState.confirmed, message.deliveryState)
            assertEquals("turn-1", message.turnId)
        }

    @Test
    fun appendStructuredInputPromptMarker_addsEphemeralRow() =
        runTest {
            val store = MessageTimelineStore()

            store.appendStructuredInputPromptMarker(
                threadId = "t1",
                turnId = "turn-x",
                messageId = "req-1",
                bodyText = "Choose speed",
            )

            val row = store.messagesByThread.value["t1"].orEmpty().single()
            assertEquals(CodexMessageKind.userInputPrompt, row.kind)
            assertEquals("req-1", row.id)
            assertEquals("Choose speed", row.text)
            assertEquals("turn-x", row.turnId)
        }

    @Test
    fun removeEphemeralPendingServerMarker_removesStructuredInputAndApprovalById() =
        runTest {
            val store = MessageTimelineStore()

            store.appendPendingApprovalMarker(
                threadId = "t-app",
                turnId = null,
                itemId = null,
                messageId = "ap-9",
                bodyText = "approve me",
            )
            store.appendStructuredInputPromptMarker(
                threadId = "t-in",
                turnId = null,
                messageId = "si-9",
                bodyText = "type me",
            )

            store.removeEphemeralPendingServerMarker("si-9")

            assertEquals(0, store.messagesByThread.value["t-in"].orEmpty().size)
            assertEquals(1, store.messagesByThread.value["t-app"].orEmpty().size)

            store.removeEphemeralPendingServerMarker("ap-9")

            assertEquals(0, store.messagesByThread.value["t-app"].orEmpty().size)
        }

    private fun userMessage(
        id: String,
        itemId: String,
        attachments: List<CodexImageAttachment>,
    ): CodexMessage =
        CodexMessage(
            id = id,
            threadId = "thread-1",
            role = CodexMessageRole.user,
            kind = CodexMessageKind.chat,
            text = "look",
            createdAt = Instant.parse("2024-01-01T00:00:01Z"),
            turnId = "turn-phone",
            itemId = itemId,
            deliveryState = CodexMessageDeliveryState.confirmed,
            attachments = attachments,
        )
}

