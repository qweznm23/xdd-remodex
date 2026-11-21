package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.*
import com.remodex.mobile.data.ConversationQuestionCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

internal suspend fun CodexService.skipConversationQuestionsInternal(threadId: String, request: CodexStructuredUserInputRequest) {
    if (request.source == "rpc") {
        val pending = _pendingStructuredInputRequests.value[request.requestID.stringValue]
        if (!ConversationQuestionCodec.matchesPending(threadId, request, pending)) throw CodexServiceError.NoPendingUserInput
        resolvePendingStructuredInputForRepository(pending!!.id, emptyMap())
    } else {
        val current = conversationQuestionsByThread.value[threadId].orEmpty().firstOrNull {
            it.identity == request.identity && it.questions == request.questions
        } ?: throw CodexServiceError.NoPendingUserInput
        answerConversationQuestionsInternal(threadId, current, current.questions.filter { it.id !in current.answers }
            .associate { it.id to listOf(ConversationQuestionCodec.SKIPPED_ANSWER) })
    }
}

/** Async question replies use the same explicit desktop reply envelope, without changing model/settings. */
internal suspend fun CodexService.answerConversationQuestionsInternal(
    threadId: String,
    request: CodexStructuredUserInputRequest,
    answers: Map<String, List<String>>,
    allowHistorical: Boolean = false,
) = withContext(Dispatchers.IO) {
    if (request.source == "rpc" && !allowHistorical) {
        val pending = _pendingStructuredInputRequests.value[request.requestID.stringValue]
        if (!ConversationQuestionCodec.matchesPending(threadId, request, pending)) throw CodexServiceError.NoPendingUserInput
        resolvePendingStructuredInputForRepository(pending!!.id, answers)
        return@withContext
    }
    questionSubmissionMutex.withLock {
        if (!sessionReady) throw CodexServiceError.Disconnected
        val current = messagesByThread.value[threadId].orEmpty().mapNotNull { it.structuredUserInputRequest }
            .firstOrNull { it.identity == request.identity && it.questions == request.questions }
            ?: throw CodexServiceError.InvalidInput("Question is no longer available in this conversation")
        if (current.submitted) throw CodexServiceError.InvalidInput("This question already has an answer; refresh the conversation")
        val generation = connectionGeneration
        val text = ConversationQuestionCodec.submissionText(current, answers, allowHistorical)
        val clientId = UUID.nameUUIDFromBytes((threadId + "\n" + text).toByteArray()).toString()
        val pendingId = if (current.source == "async") messageTimelineStore.appendPendingUserMessage(threadId, text) else null
        var stage = "question_reply_send"
        try {
            val response = questionReplyDeadline { sendRequestImpl("turn/start", JSONValue.Obj(mapOf(
                "threadId" to JSONValue.Str(threadId),
                "clientUserMessageId" to JSONValue.Str(clientId),
                "input" to JSONValue.Arr(listOf(JSONValue.Obj(mapOf(
                    "type" to JSONValue.Str("text"), "text" to JSONValue.Str(text),
                    "text_elements" to JSONValue.Arr(emptyList()),
                )))),
            ))) }
            if (connectionGeneration != generation) throw CancellationException("Question reply belongs to a replaced connection")
            stage = "question_reply_decode"
            val turnId = response.result?.objectValue?.get("turn")?.objectValue?.get("id")?.stringValue?.takeIf { it.isNotBlank() }
                ?: throw CodexServiceError.InvalidResponse("turn/start question reply missing turn.id")
            stage = "question_reply_record"
            messageTimelineStore.appendMirroredUser(threadId, turnId, text, requireCurrentRequest = {
                if (connectionGeneration != generation) throw CancellationException("Question reply record belongs to a replaced connection")
            })
            bumpThreadActivityAfterTurn(threadId)
        } catch (error: Exception) {
            if (pendingId != null) withContext(kotlinx.coroutines.NonCancellable) {
                try { messageTimelineStore.discardUnconfirmedQuestionReply(threadId, pendingId) }
                catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
            if (error is CancellationException) throw error
            android.util.Log.e("RemodexQuestions", "operation=answer thread=$threadId request=${request.requestID.stringValue} stage=$stage attempt=1", error)
            throw error
        }
    }
}

/** Only this deadline becomes a retryable timeout; parent cancellation and nested deadlines propagate. */
internal suspend fun <T : Any> questionReplyDeadline(timeoutMillis: Long = 30_000, action: suspend () -> T): T {
    var timeoutCause: kotlinx.coroutines.TimeoutCancellationException? = null
    val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
        try { action() }
        catch (timeout: kotlinx.coroutines.TimeoutCancellationException) { timeoutCause = timeout; throw timeout }
    }
    return result ?: throw java.util.concurrent.TimeoutException("Question reply timed out stage=question_reply_send timeoutMs=$timeoutMillis").apply {
        timeoutCause?.let { initCause(it) }
    }
}
