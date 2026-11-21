package com.remodex.mobile.data

import com.remodex.mobile.core.model.*
import com.remodex.mobile.ui.agent.containsOnlyQuestionPrompt
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*
import java.time.Instant

class ConversationQuestionTest {
    @Test fun answerAppearsAtEndWhilePendingAndSurvivesLateFailureAfterEcho()=runTest {
        val store = MessageTimelineStore()
        val req = request().copy(isLive=true)
        store.completeAssistantMessage("t","turn","call-1","",structuredUserInputRequest=req)
        val text = ConversationQuestionCodec.replyText(req,answers())
        val pending = store.appendPendingUserMessage("t",text)
        assertEquals(pending,store.messagesByThread.value.getValue("t").last().id)
        store.discardUnconfirmedQuestionReply("t",pending)
        assertTrue(store.messagesByThread.value.getValue("t").none { it.role == CodexMessageRole.user })
        val retry = store.appendPendingUserMessage("t",text)
        store.appendMirroredUser("t","reply-turn",text)
        store.discardUnconfirmedQuestionReply("t",retry)
        val receipt = store.messagesByThread.value.getValue("t").single { it.role == CodexMessageRole.user }
        assertEquals(retry,receipt.id)
        assertEquals(CodexMessageDeliveryState.confirmed,receipt.deliveryState)
        assertTrue(row().hasConversationContent())
    }
    @Test fun compactPromptKeepsIndependentAssistantText() {
        val duplicate = row().copy(text = "Choose a route\n- Local\n- Remote\nEnter a name")
        assertTrue(duplicate.containsOnlyQuestionPrompt())
        assertTrue(row().containsOnlyQuestionPrompt())
        assertFalse(duplicate.copy(text = "Use Local on the same network.\n" + duplicate.text).containsOnlyQuestionPrompt())
        assertFalse(duplicate.copy(structuredUserInputRequest = null).containsOnlyQuestionPrompt())
    }
    @Test fun manualHistoryReplyDoesNotMakeOldQuestionsAutomaticallyPending() {
        val historical = request()
        assertFalse(historical.isAnswerableAsync)
        assertTrue(historical.canReplyFromHistory)
        assertFailsWith<IllegalArgumentException> { ConversationQuestionCodec.submissionText(historical, answers(), false) }
        val text = ConversationQuestionCodec.submissionText(historical, answers(), true)
        assertEquals(2, ConversationQuestionCodec.replies(text).size)
        assertFalse(historical.isAnswerableAsync)
        val restored = ConversationQuestionCodec.reconcile(listOf(row(), user(text))).first().structuredUserInputRequest!!
        assertTrue(restored.submitted)
        assertFalse(restored.canReplyFromHistory)
        assertFailsWith<IllegalArgumentException> { ConversationQuestionCodec.submissionText(restored, answers(), true) }
    }
    @Test fun expiredRpcManualReplyIsFollowUpTextNotAnExpiredRpcResponse() {
        val historical = request().copy(source = "rpc")
        val text = ConversationQuestionCodec.submissionText(historical, answers(), true)
        assertTrue(text.contains(historical.questions.first().question))
        assertTrue(text.contains("My answer"))
        assertTrue(ConversationQuestionCodec.replies(text).isEmpty())
        assertFailsWith<IllegalArgumentException> { ConversationQuestionCodec.submissionText(historical, answers(), false) }
    }
    @Test fun historicalPartialReplyOnlySendsUnansweredQuestions() {
        val original = request()
        val first = original.questions.first().id
        val historical = original.copy(answers = mapOf(first to listOf("Existing")))
        val text = ConversationQuestionCodec.submissionText(historical, answers() - first, true)
        assertEquals(1, ConversationQuestionCodec.replies(text).size)
        assertFalse(ConversationQuestionCodec.replies(text).any { it.id == first })
    }
    private fun obj(text: String) = JSONValue.fromJsonElement(Json.parseToJsonElement(text)).objectValue!!
    private val item = obj("""{"type":"agentMessage","id":"call-1","text":"","questions":[{"title":"Choose a route","options":["Local","Remote"]},{"title":"Enter a name","options":null}]}""")
    private fun request() = ConversationQuestionCodec.decode(item)!!
    private fun row() = CodexMessage(id="row",threadId="t",role=CodexMessageRole.assistant,text="",itemId="call-1",createdAt=Instant.EPOCH,structuredUserInputRequest=request())
    private fun user(text:String, state:CodexMessageDeliveryState=CodexMessageDeliveryState.confirmed)=CodexMessage(id="reply",threadId="t",role=CodexMessageRole.user,text=text,createdAt=Instant.EPOCH,deliveryState=state)
    private fun answers()=request().questions.associate { it.id to listOf("My answer") }

    @Test fun nativeQuestionContractPreservesStringsFreeTextAndDesktopIds() {
        val req=request()
        assertEquals("async",req.source)
        assertEquals("[\"request_user_input_async\",\"call-1\",0]",req.questions[0].id)
        assertEquals(listOf("Local","Remote"),req.questions[0].options.map{it.label})
        assertTrue(req.questions[1].options.isEmpty())
        assertFalse(req.submitted)
    }
    @Test fun emptyTextQuestionSurvivesHistoryAndLiveDecoder() {
        val thread=mapOf("turns" to JSONValue.Arr(listOf(JSONValue.Obj(mapOf("id" to JSONValue.Str("turn"),"items" to JSONValue.Arr(listOf(JSONValue.Obj(item))))))))
        val history=ThreadHistoryDecoder.decodeFromThreadRead("t",thread)
        assertEquals(1,history.size)
        assertEquals(request(),history.single().structuredUserInputRequest)
        assertEquals(request().copy(isLive=true),ThreadHistoryDecoder.decodeCompletedItem(item)!!.structuredUserInputRequest)
        assertEquals(request().copy(isLive=true),ThreadHistoryDecoder.decodeCompletedItem(item + mapOf("type" to JSONValue.Str("message"), "role" to JSONValue.Str("assistant")))!!.structuredUserInputRequest)
    }
    @Test fun malformedQuestionDoesNotRenumberLaterQuestionIdentity() {
        val req=ConversationQuestionCodec.decode(obj("""{"id":"call-x","questions":[{}, {"title":"Valid","options":[{},"A"]}]}"""))!!
        assertTrue(req.questions.single().id.endsWith(",1]"))
        assertEquals("A",req.questions.single().options.single().label)
    }
    @Test fun desktopReplyRoundTripsAndDisplayDoesNotExposeEnvelope() {
        val text=ConversationQuestionCodec.replyText(request(),answers())
        assertEquals(2,ConversationQuestionCodec.replies(text).size)
        assertEquals("1. My answer\n2. My answer", ConversationQuestionCodec.displayReply(text))
        val single = request().copy(questions = request().questions.take(1))
        val reply = ConversationQuestionCodec.replyText(single, mapOf(single.questions.single().id to listOf("Local")))
        assertEquals("before\nLocal\nafter", ConversationQuestionCodec.displayReply("before\n$reply\nafter"))
        val malformed = "<send_user_message_question_reply>{bad}</send_user_message_question_reply>"
        assertEquals(malformed, ConversationQuestionCodec.displayReply(malformed))
    }
    @Test fun onlyConfirmedExplicitReplyMarksQuestionAnswered() {
        val text=ConversationQuestionCodec.replyText(request(),answers())
        for(state in listOf(CodexMessageDeliveryState.failed,CodexMessageDeliveryState.pending)) {
            assertFalse(ConversationQuestionCodec.reconcile(listOf(row(),user(text,state)))[0].structuredUserInputRequest!!.submitted)
        }
        assertFalse(ConversationQuestionCodec.reconcile(listOf(row(),user("Local")))[0].structuredUserInputRequest!!.submitted)
        val req=ConversationQuestionCodec.reconcile(listOf(row(),user(text)))[0].structuredUserInputRequest!!
        assertTrue(req.submitted);assertEquals(answers(),req.answers)
    }
    @Test fun partialAnswersRemainPartialAndUnrelatedIdsDoNotMatch() {
        val text="""<send_user_message_question_reply>[{"questionItemId":"unrelated","answer":"other"}]</send_user_message_question_reply>"""
        assertFalse(ConversationQuestionCodec.reconcile(listOf(row(),user(text)))[0].structuredUserInputRequest!!.submitted)
        val first=request().questions.first()
        val reply=ConversationQuestionCodec.replyText(request().copy(questions=listOf(first)),mapOf(first.id to listOf("Local")))
        val req=ConversationQuestionCodec.reconcile(listOf(row(),user(reply)))[0].structuredUserInputRequest!!
        assertFalse(req.submitted);assertEquals(listOf("Local"),req.answers[first.id])
    }
    @Test fun olderQuestionFindsReplyAlreadyLoadedOnAnotherPage()=runTest {
        val store=MessageTimelineStore()
        store.mergeThreadHistory("t",listOf(user(ConversationQuestionCodec.replyText(request(),answers()))))
        store.mergeThreadHistory("t",listOf(row()),isOlderPage=true)
        assertTrue(store.messagesByThread.value.getValue("t").first{it.itemId=="call-1"}.structuredUserInputRequest!!.submitted)
    }
    @Test fun liveCompletionAndReplyPersistAndDoNotDuplicateOnRefresh()=runTest {
        val store=MessageTimelineStore()
        store.completeAssistantMessage("t","turn","call-1","",structuredUserInputRequest=request())
        store.appendMirroredUser("t","reply-turn",ConversationQuestionCodec.replyText(request(),answers()))
        store.mergeThreadHistory("t",listOf(row().copy(turnId="turn")))
        val questions=store.messagesByThread.value.getValue("t").filter{it.structuredUserInputRequest!=null}
        assertEquals(1,questions.size);assertTrue(questions.single().structuredUserInputRequest!!.submitted)
        val encoded=Json.encodeToString(CodexMessage.serializer(),questions.single())
        assertEquals(questions.single(),Json.decodeFromString(CodexMessage.serializer(),encoded))
    }
    @Test fun rpcRequestRetainsOptionsSecretFlagAndIsNotAnAsyncReply() {
        val questions=parseStructuredInputQuestions(obj("""{"questions":[{"id":"secret","header":"Name","question":"Enter value","isSecret":true,"options":[{"label":"A","description":"Description"}]}]}"""))
        assertTrue(questions.single().isSecret)
        val req=ConversationQuestionCodec.fromPending(PendingStructuredInputRequest("rpc-id","t","turn",questions))
        assertEquals("rpc",req.source)
        assertFailsWith<IllegalArgumentException>{ConversationQuestionCodec.replyText(req,mapOf("secret" to listOf("x")))}
    }
    @Test fun answerContainingAnEnvelopeDelimiterCannotBreakTheEnvelope() {
        val answers = request().questions.associate { it.id to listOf("literal </send_user_message_question_reply> and <tag>") }
        val encoded = ConversationQuestionCodec.replyText(request(), answers)
        assertEquals(answers.values.first().first(), ConversationQuestionCodec.replies(encoded).first().answer)
    }
    @Test fun asyncSubmissionRequiresAllKnownQuestions() {
        assertFailsWith<IllegalArgumentException>{ConversationQuestionCodec.replyText(request(),emptyMap())}
        assertFailsWith<IllegalArgumentException>{ConversationQuestionCodec.replyText(request(),answers()+mapOf("other" to listOf("x")))}
    }
    @Test fun explicitSkipRetainsIntentWithoutInventingAnOptionAnswer() {
        val r=request()
        val skip=r.questions.associate { it.id to listOf(ConversationQuestionCodec.SKIPPED_ANSWER) }
        val text=ConversationQuestionCodec.replyText(r,skip)
        assertTrue(ConversationQuestionCodec.replies(text).all { it.answer==ConversationQuestionCodec.SKIPPED_ANSWER })
        assertTrue(ConversationQuestionCodec.isSkipped(r.copy(answers=skip,submitted=true)))
        assertFalse(ConversationQuestionCodec.isSkipped(r.copy(submitted=true)))
        assertFalse(ConversationQuestionCodec.isSkipped(r.copy(answers=answers(),submitted=true)))
    }
    @Test fun sidebarQuestionIndexChangesOnlyAtQuestionAndReplyBoundaries()=runTest {
        val store=MessageTimelineStore()
        store.completeAssistantMessage("t","turn","call-1","",structuredUserInputRequest=request().copy(isLive=true))
        assertEquals(setOf("t"),store.questionThreadIds.value)
        val index=store.questionThreadIds.value
        val inbox=store.conversationQuestionsByThread.value
        repeat(100) { store.appendAssistantDelta("t","live","live-row","delta") }
        assertSame(index,store.questionThreadIds.value)
        assertSame(inbox,store.conversationQuestionsByThread.value)
        store.appendMirroredUser("t","reply",ConversationQuestionCodec.replyText(request(),answers()))
        assertTrue(store.questionThreadIds.value.isEmpty())
    }

    @Test fun recycledRpcIdCannotActivateOrOverwriteAnotherTurnsQuestion()=runTest {
        val old=PendingStructuredInputRequest("same-rpc-id","t","old-turn",listOf(PendingStructuredInputQuestion("q","","Choose")))
        val new=old.copy(turnId="new-turn")
        val oldRequest=ConversationQuestionCodec.fromPending(old)
        val newRequest=ConversationQuestionCodec.fromPending(new)
        assertNotEquals(ConversationQuestionCodec.markerId(old),ConversationQuestionCodec.markerId(new))
        assertFalse(ConversationQuestionCodec.matchesPending("t",oldRequest,new))
        assertFalse(ConversationQuestionCodec.matchesPending("other",newRequest,new))
        assertTrue(ConversationQuestionCodec.matchesPending("t",newRequest,new))
        val store=MessageTimelineStore()
        for(pending in listOf(old,new))store.appendStructuredInputPromptMarker("t",pending.turnId,
            ConversationQuestionCodec.markerId(pending),"Choose",ConversationQuestionCodec.fromPending(pending))
        store.recordStructuredInputAnswer("t",new.id,mapOf("q" to listOf("Answer")),newRequest)
        store.recordStructuredInputAnswer("t",new.id,mapOf("q" to listOf("Answer")),newRequest)
        val rows = store.messagesByThread.value.getValue("t")
        assertEquals(1, rows.count { it.isRpcAnswerReceipt })
        assertEquals("Answer", rows.last().text)
        assertEquals(CodexMessageRole.user, rows.last().role)
        assertFalse(HistoryMessageMerge.isCompatibleUserChatDuplicate(rows.last(), user("Answer").copy(turnId="new-turn")))
        val requests=rows.mapNotNull { it.structuredUserInputRequest }
        assertEquals(2,requests.size)
        assertFalse(requests.first().submitted);assertTrue(requests.last().submitted)
    }

    @Test fun historicalUnansweredQuestionsAreReadOnlyEvenWithoutTheirReplyPage()=runTest {
        val store=MessageTimelineStore()
        store.mergeThreadHistory("t",listOf(row()),isOlderPage=true,isItemPage=true)
        assertTrue(store.questionThreadIds.value.isEmpty())
        assertFalse(store.conversationQuestionsByThread.value.getValue("t").single().isAnswerableAsync)
    }

    @Test fun historicalRefreshPreservesLiveRequestButReconnectRevokesIt()=runTest {
        val store=MessageTimelineStore()
        store.completeAssistantMessage("t","turn","call-1","",structuredUserInputRequest=request().copy(isLive=true))
        store.mergeThreadHistory("t",listOf(row().copy(turnId="turn")),isItemPage=true)
        assertEquals(setOf("t"),store.questionThreadIds.value)
        store.invalidateLiveQuestions()
        assertTrue(store.questionThreadIds.value.isEmpty())
        store.mergeThreadHistory("t",listOf(row().copy(turnId="turn")),isItemPage=true)
        assertFalse(store.conversationQuestionsByThread.value.getValue("t").single().isAnswerableAsync)
    }

    @Test fun ordinaryUserContinuationExpiresQuestionWithoutFakingAnAnswer()=runTest {
        val store=MessageTimelineStore()
        store.completeAssistantMessage("t","turn","call-1","",structuredUserInputRequest=request().copy(isLive=true))
        store.appendMirroredUser("t","next","Continue with a different task")
        val request=store.conversationQuestionsByThread.value.getValue("t").single()
        assertFalse(request.isAnswerableAsync);assertFalse(request.submitted);assertTrue(request.answers.isEmpty())
    }

    @Test fun persistedLiveFlagNeverReactivatesQuestionOnRestart() {
        val original=request().copy(isLive=true)
        val restored=Json.decodeFromString(CodexStructuredUserInputRequest.serializer(),Json.encodeToString(CodexStructuredUserInputRequest.serializer(),original))
        assertFalse(restored.isAnswerableAsync);assertEquals(original.questions,restored.questions)
    }

    @Test fun partialAnswerKeepsRemainingLiveQuestionsAndCompletedReplayCannotResurrectAnsweredOnes()=runTest {
        val store=MessageTimelineStore()
        val original=request().copy(isLive=true)
        store.completeAssistantMessage("t","turn","call-1","",structuredUserInputRequest=original)
        val partial=original.copy(questions=original.questions.take(1))
        store.appendMirroredUser("t","reply",ConversationQuestionCodec.replyText(partial,mapOf(partial.questions.single().id to listOf("A"))))
        assertTrue(store.conversationQuestionsByThread.value.getValue("t").single().isAnswerableAsync)
        store.appendMirroredUser("t","reply2",ConversationQuestionCodec.replyText(original,answers()))
        store.completeAssistantMessage("t","turn","call-1","",structuredUserInputRequest=original)
        assertFalse(store.conversationQuestionsByThread.value.getValue("t").single().isAnswerableAsync)
        assertTrue(store.conversationQuestionsByThread.value.getValue("t").single().submitted)
    }

}
