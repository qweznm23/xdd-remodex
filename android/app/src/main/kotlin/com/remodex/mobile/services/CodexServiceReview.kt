package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.CodexMessageDeliveryState
import com.remodex.mobile.core.model.CodexReviewTarget
import com.remodex.mobile.core.model.CodexThreadSyncState
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCMessage
import com.remodex.mobile.data.extractTurnIdFromRpcResult
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun CodexService.startReviewInternal(
    threadId: String,
    target: CodexReviewTarget,
    baseBranch: String? = null,
) {
    if (!sessionReady) throw CodexServiceError.Disconnected
    val targetThreadId = threadId.trim()
    if (targetThreadId.isEmpty()) throw CodexServiceError.InvalidInput("Missing thread id")

    val reviewPrompt = reviewPromptText(target, baseBranch)

    val row = _threads.value.find { it.id == targetThreadId }
    ensureThreadResumedInternal(
        threadId = targetThreadId,
        force = false,
        preferredProjectPath = row?.gitWorkingDirectory,
        modelIdentifierOverride = row?.model,
    )

    val pendingId = messageTimelineStore.appendPendingUserMessage(targetThreadId, reviewPrompt, emptyList())
    noteProtectedRunningFallback(targetThreadId, true)

    try {
        val response = sendReviewStart(targetThreadId, target, baseBranch)
        markReviewStartAccepted(targetThreadId, pendingId, response)
    } catch (e: Throwable) {
        noteProtectedRunningFallback(targetThreadId, false)
        try {
            messageTimelineStore.markUserMessageOutcome(
                threadId = targetThreadId,
                messageId = pendingId,
                deliveryState = CodexMessageDeliveryState.failed,
                turnId = null,
            )
        } catch (cleanup: Exception) {
            e.addSuppressed(cleanup)
        }
        throw e
    }
}

suspend fun CodexService.startReviewForRepository(
    threadId: String,
    target: CodexReviewTarget,
    baseBranch: String? = null,
) = withContext(Dispatchers.IO) {
    startReviewInternal(threadId, target, baseBranch)
}

private fun reviewPromptText(
    target: CodexReviewTarget,
    baseBranch: String?,
): String =
    when (target) {
        CodexReviewTarget.uncommittedChanges -> "Review current changes"
        CodexReviewTarget.baseBranch -> {
            val branch = normalizedReviewBaseBranch(baseBranch)
            "Review against base branch $branch"
        }
    }

private suspend fun CodexService.sendReviewStart(
    threadId: String,
    target: CodexReviewTarget,
    baseBranch: String?,
): RPCMessage =
    sendRequestWithSandboxAndApprovalFallback(
        "review/start",
        buildReviewStartRequestParams(threadId, target, baseBranch),
    )

internal fun buildReviewStartRequestParams(
    threadId: String,
    target: CodexReviewTarget,
    baseBranch: String?,
): JSONValue.Obj {
    val targetPayload =
        when (target) {
            CodexReviewTarget.uncommittedChanges ->
                JSONValue.Obj(mapOf("type" to JSONValue.Str("uncommittedChanges")))
            CodexReviewTarget.baseBranch ->
                JSONValue.Obj(
                    mapOf(
                        "type" to JSONValue.Str("baseBranch"),
                        "branch" to JSONValue.Str(normalizedReviewBaseBranch(baseBranch)),
                    ),
                )
        }
    return JSONValue.Obj(
        linkedMapOf(
            "threadId" to JSONValue.Str(threadId),
            "delivery" to JSONValue.Str("inline"),
            "target" to targetPayload,
        ),
    )
}

internal fun normalizedReviewBaseBranch(baseBranch: String?): String =
    baseBranch?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw CodexServiceError.InvalidInput("Missing base branch")

private suspend fun CodexService.markReviewStartAccepted(
    threadId: String,
    pendingMessageId: String,
    response: RPCMessage,
) {
    val turnId = extractTurnIdFromRpcResult(response.result)
    messageTimelineStore.markUserMessageOutcome(
        threadId = threadId,
        messageId = pendingMessageId,
        deliveryState =
            if (turnId != null) {
                CodexMessageDeliveryState.confirmed
            } else {
                CodexMessageDeliveryState.pending
            },
        turnId = turnId,
    )
    if (turnId != null) {
        noteTurnStarted(threadId, turnId)
    }
    bumpThreadActivityAfterReview(threadId)
}

private fun CodexService.bumpThreadActivityAfterReview(threadId: String) {
    val list = _threads.value
    if (list.none { it.id == threadId }) return
    publishThreads(
        sortThreadsForBridge(
            list.map {
                if (it.id == threadId) {
                    it.copy(
                        updatedAt = Instant.now(),
                        syncState = CodexThreadSyncState.live,
                    )
                } else {
                    it
                }
            },
        ),
    )
}
