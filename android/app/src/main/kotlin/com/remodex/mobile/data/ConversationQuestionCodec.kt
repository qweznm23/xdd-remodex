package com.remodex.mobile.data

import com.remodex.mobile.core.model.*
import kotlinx.serialization.json.*

/** Native async question messages and the desktop's explicit question-reply envelope. */
internal object ConversationQuestionCodec {
    const val SKIPPED_ANSWER = "用户选择跳过此问题。"
    fun isSkipped(request: CodexStructuredUserInputRequest): Boolean = request.submitted &&
        (request.source == "rpc" && request.answers.isEmpty() ||
            request.answers.isNotEmpty() && request.answers.values.all { it == listOf(SKIPPED_ANSWER) })
    private val json = Json { ignoreUnknownKeys = true }
    private const val OPEN = "<send_user_message_question_reply>"
    private const val CLOSE = "</send_user_message_question_reply>"

    fun decode(item: Map<String, JSONValue>, live: Boolean = false): CodexStructuredUserInputRequest? {
        val id = item["id"]?.stringValue?.takeIf { it.isNotBlank() } ?: return null
        val questions = item["questions"]?.arrayValue?.mapIndexedNotNull { index, value ->
            val q = value.objectValue ?: return@mapIndexedNotNull null
            val title = q["title"]?.stringValue?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val questionId = JsonArray(listOf(JsonPrimitive("request_user_input_async"), JsonPrimitive(id), JsonPrimitive(index))).toString()
            CodexStructuredUserInputQuestion(questionId, "", title, true, false,
                q["options"]?.arrayValue.orEmpty().mapNotNull { it.stringValue?.takeIf(String::isNotBlank) }
                    .map { CodexStructuredUserInputOption(id = it, label = it, description = "") })
        }.orEmpty()
        return questions.takeIf { it.isNotEmpty() }?.let { CodexStructuredUserInputRequest(JSONValue.Str(id), it, source = "async", isLive = live) }
    }

    fun fromPending(request: PendingStructuredInputRequest) = CodexStructuredUserInputRequest(
        JSONValue.Str(request.id), request.questions.map { q ->
            CodexStructuredUserInputQuestion(q.id, q.header, q.question, true, q.isSecret,
                q.options.map { CodexStructuredUserInputOption(id = it.label, label = it.label, description = it.description.orEmpty()) })
        }, turnId = request.turnId, itemId = request.itemId,
    )

    fun matchesPending(threadId: String, request: CodexStructuredUserInputRequest, pending: PendingStructuredInputRequest?): Boolean =
        pending != null && pending.threadId == threadId && request.source == "rpc" &&
            request.requestID.stringValue == pending.id && request.turnId == pending.turnId && request.itemId == pending.itemId &&
            request.questions == fromPending(pending).questions

    fun markerId(request: PendingStructuredInputRequest): String = "input:" + java.util.UUID.nameUUIDFromBytes(
        (request.threadId.orEmpty() + "\n" + fromPending(request).identity).toByteArray()).toString()

    fun asPending(threadId: String, request: CodexStructuredUserInputRequest) = PendingStructuredInputRequest(
        request.requestID.stringValue.orEmpty(), threadId, null,
        request.questions.map { q -> PendingStructuredInputQuestion(q.id, q.header, q.question,
            q.options.map { PendingStructuredInputOption(it.label, it.description) }, q.isSecret) },
    )

    fun mergeRequest(current: CodexStructuredUserInputRequest?, incoming: CodexStructuredUserInputRequest): CodexStructuredUserInputRequest {
        if (current == null || current.identity != incoming.identity || current.questions != incoming.questions) return incoming
        return incoming.copy(answers = incoming.answers + current.answers,
            submitted = incoming.submitted || current.submitted, isLive = incoming.isLive || current.isLive)
    }

    data class Reply(val id: String, val question: String, val answer: String)
    fun submissionText(request: CodexStructuredUserInputRequest, answers: Map<String, List<String>>, allowHistorical: Boolean): String {
        require(!request.submitted) { "This question already has an answer; refresh the conversation" }
        require(request.isAnswerableAsync || allowHistorical && request.canReplyFromHistory) {
            "Historical questions require an explicit user action"
        }
        val remaining = request.copy(questions = request.questions.filter { it.id !in request.answers })
        if (request.source == "async") return replyText(remaining, answers)
        // Expired RPC responders cannot be revived. Send an explicit follow-up message instead.
        require(remaining.questions.isNotEmpty() && answers.keys == remaining.questions.map { it.id }.toSet())
        return remaining.questions.joinToString("\n\n") { question ->
            val answer = answers.getValue(question.id).joinToString("\n").trim()
            require(answer.isNotBlank())
            "${question.question}\n$answer"
        }
    }
    fun replies(text: String): List<Reply> {
        val start = text.indexOf(OPEN)
        if (start < 0) return emptyList()
        val end = text.indexOf(CLOSE, start + OPEN.length)
        if (end < 0) return emptyList()
        // Malformed envelopes remain visible as their original text; they never mark a question answered.
        val values = try { json.parseToJsonElement(text.substring(start + OPEN.length, end)) as? JsonArray }
            catch (_: kotlinx.serialization.SerializationException) { null } ?: return emptyList()
        return values.mapNotNull { value ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val id = (obj["questionItemId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val answer = (obj["answer"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            Reply(id, (obj["question"] as? JsonPrimitive)?.contentOrNull.orEmpty(), answer)
        }
    }

    fun displayReply(text: String): String {
        val replies = replies(text)
        if (replies.isEmpty()) return text
        val body = displayAnswers(replies.map { it.answer })
        val start = text.indexOf(OPEN); val end = text.indexOf(CLOSE, start) + CLOSE.length
        return text.take(start) + body + text.substring(end)
    }

    fun displayAnswers(answers: List<String>): String = answers.singleOrNull()
        ?: answers.mapIndexed { index, answer -> "${index + 1}. $answer" }.joinToString("\n")

    fun replyText(request: CodexStructuredUserInputRequest, answers: Map<String, List<String>>): String {
        require(request.source == "async")
        require(request.questions.isNotEmpty() && answers.keys == request.questions.map { it.id }.toSet())
        val values = request.questions.map { q ->
            val answer = answers.getValue(q.id).joinToString("\n").trim()
            require(answer.isNotBlank())
            buildJsonObject { put("questionItemId", q.id); put("question", q.question); put("answer", answer) }
        }
        return OPEN + "\n" + JsonArray(values).toString().replace("<", "\\u003c") + "\n" + CLOSE
    }

    /** Run only on history/user-message/question boundaries, never on streaming text deltas. */
    fun reconcile(messages: List<CodexMessage>): List<CodexMessage> {
        if (messages.none { it.structuredUserInputRequest != null }) return messages
        val answers = messages.asSequence().filter { it.role == CodexMessageRole.user && it.deliveryState == CodexMessageDeliveryState.confirmed }
            .flatMap { replies(it.text).asSequence() }.associate { it.id to listOf(it.answer) }
        val lastUser = messages.indexOfLast { it.role == CodexMessageRole.user && !it.isRpcAnswerReceipt &&
            it.deliveryState == CodexMessageDeliveryState.confirmed }
        val latestReplyIds = messages.getOrNull(lastUser)?.let { replies(it.text).map { reply -> reply.id }.toSet() }.orEmpty()
        return messages.mapIndexed { index, message ->
            val request = message.structuredUserInputRequest ?: return@mapIndexed message
            if (request.source != "async") return@mapIndexed message
            val matched = request.questions.mapNotNull { q -> answers[q.id]?.let { q.id to it } }.toMap()
            val merged = request.answers + matched
            val partialReply = request.questions.any { it.id in latestReplyIds }
            val updated = request.copy(answers = merged,
                submitted = request.submitted || request.questions.all { it.id in merged },
                isLive = request.isLive && (index > lastUser || partialReply))
            if (updated == request) message else message.copy(structuredUserInputRequest = updated)
        }
    }
}

internal val CodexMessage.isRpcAnswerReceipt: Boolean get() = id.startsWith("rpc-answer:")
