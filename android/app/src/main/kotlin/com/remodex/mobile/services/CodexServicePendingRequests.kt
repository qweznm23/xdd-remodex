package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.PendingApprovalDecision
import com.remodex.mobile.core.model.PendingApprovalRequest
import com.remodex.mobile.core.model.PendingStructuredInputRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

internal fun CodexService.enqueuePendingApprovalRequest(
    request: PendingApprovalRequest,
    responder: (PendingApprovalDecision) -> Unit,
) {
    pendingApprovalResponders[request.id] = responder
    _pendingApprovalRequest.value = request
    notifyPendingApprovalAttention(request)
}

internal fun CodexService.enqueuePendingStructuredInputRequest(
    request: PendingStructuredInputRequest,
    responder: (answersByQuestionId: Map<String, List<String>>) -> Unit,
) {
    synchronized(pendingStructuredInputResponders) {
        pendingStructuredInputResponders[request.id] = responder
        _pendingStructuredInputRequests.value = _pendingStructuredInputRequests.value + (request.id to request)
        _pendingStructuredInputRequest.value = _pendingStructuredInputRequests.value.values.firstOrNull()
    }
    notifyStructuredInputAttention(request)
}

suspend fun CodexService.resolvePendingApprovalForRepository(
    requestId: String,
    decision: PendingApprovalDecision,
) = withContext(Dispatchers.IO) {
    val id = requestId.trim()
    val responder = pendingApprovalResponders.remove(id) ?: throw CodexServiceError.NoPendingApproval
    if (_pendingApprovalRequest.value?.id == id) {
        _pendingApprovalRequest.value = null
    }
    messageTimelineStore.removeEphemeralPendingServerMarker(id)
    responder(decision)
}

suspend fun CodexService.resolvePendingStructuredInputForRepository(
    requestId: String,
    answersByQuestionId: Map<String, List<String>>,
): Unit = withContext(Dispatchers.IO) {
    questionSubmissionMutex.withLock {
        val id = requestId.trim()
        val generation = connectionGeneration
        val (request, responder) = synchronized(pendingStructuredInputResponders) {
            if (!sessionReady) throw CodexServiceError.Disconnected
            val request = _pendingStructuredInputRequests.value[id] ?: throw CodexServiceError.NoPendingUserInput
            val responder = pendingStructuredInputResponders[id] ?: throw CodexServiceError.NoPendingUserInput
            if (answersByQuestionId.isNotEmpty() && (answersByQuestionId.keys != request.questions.map { it.id }.toSet() ||
                    answersByQuestionId.values.any { it.isEmpty() || it.all(String::isBlank) })) {
                throw CodexServiceError.InvalidInput("Incomplete question answers")
            }
            // Retain the responder on enqueue failure; the same form can retry explicitly.
            responder(answersByQuestionId)
            request to responder
        }
        // The reply is already sent. Finish this short local record even if the form closes.
        withContext(kotlinx.coroutines.NonCancellable) {
            try {
                request.threadId?.let { threadId ->
                    val safeAnswers = answersByQuestionId.mapValues { (id, values) ->
                        if (request.questions.any { it.id == id && it.isSecret }) listOf("••••") else values
                    }
                    messageTimelineStore.recordStructuredInputAnswer(threadId, id, safeAnswers, com.remodex.mobile.data.ConversationQuestionCodec.fromPending(request)) {
                        if (connectionGeneration != generation) throw kotlinx.coroutines.CancellationException("Reply record belongs to a replaced connection")
                    }
                }
            } finally {
                synchronized(pendingStructuredInputResponders) {
                    if (pendingStructuredInputResponders.remove(id, responder)) {
                        _pendingStructuredInputRequests.value = _pendingStructuredInputRequests.value - id
                        _pendingStructuredInputRequest.value = _pendingStructuredInputRequests.value.values.firstOrNull()
                    }
                }
            }
        }
    }
}

internal fun CodexService.clearPendingServerRequests() {
    pendingApprovalResponders.clear()
    synchronized(pendingStructuredInputResponders) {
        pendingStructuredInputResponders.clear()
        _pendingStructuredInputRequests.value = emptyMap()
        _pendingStructuredInputRequest.value = null
    }
    _pendingApprovalRequest.value = null
}
