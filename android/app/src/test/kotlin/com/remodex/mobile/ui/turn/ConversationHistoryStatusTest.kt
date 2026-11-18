package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageRole
import java.time.Instant
import java.util.concurrent.TimeoutException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationHistoryStatusTest {
    private fun row(id: String, text: String) = CodexMessage(id = id, threadId = "thread", role = CodexMessageRole.assistant, text = text, createdAt = Instant.EPOCH)

    @Test fun receivingHistoryDoesNotFinishIndicatorBeforePresentation() {
        assertEquals(HistoryLoadingPhase.Loading, historyLoadingPhase(true, true, false, false, null))
        assertEquals(HistoryLoadingPhase.Preparing, historyLoadingPhase(true, false, true, false, null))
        assertEquals(HistoryLoadingPhase.Ready, historyLoadingPhase(true, false, true, true, null))
        assertFalse(hasPresentedHistory(listOf(row("new", "完整历史")), emptyList()))
        assertFalse(hasPresentedHistory(listOf(row("new", "完整历史")), listOf(row("old", "完整历史"))))
        assertFalse(hasPresentedHistory(listOf(row("new", "完整历史")), listOf(row("new", "完整"))))
        assertTrue(hasPresentedHistory(listOf(row("new", "完整历史")), listOf(row("new", "完整历史"))))
    }

    @Test fun failuresRemainRetryableAndOfflineIsNotReportedAsEmptyHistory() {
        assertEquals(HistoryLoadingPhase.Failed, historyLoadingPhase(true, false, false, true, "RPC -32000"))
        assertEquals(HistoryLoadingPhase.Connecting, historyLoadingPhase(false, false, false, true, null))
        assertEquals(HistoryLoadingPhase.Loading, historyLoadingPhase(true, true, false, true, "old failure"))
        assertEquals(HistoryLoadingPhase.Ready, historyLoadingPhase(true, false, true, true, null))
        assertTrue(hasPresentedHistory(emptyList(), emptyList()))
    }

    @Test fun liveTokensAndAuthoritativeCompletionDoNotStarveHistoryAcknowledgement() {
        val received = row("a", "回答")
        assertTrue(hasPresentedHistory(listOf(received), listOf(row("a", "回答还在继续"), row("b", "新消息"))))
        assertTrue(hasPresentedHistory(listOf(received), listOf(row("a", "最终修订的回答").copy(isAssistantMessageComplete = true))))
        assertFalse(hasPresentedHistory(listOf(received), listOf(row("a", "其他文本"))))
    }

    @Test fun liveRowsMayAdvanceTheRenderWindowButAnOldSampleCannotFinishRefresh() {
        val received = listOf(row("a", "较早"), row("b", "最新历史"))
        val sampled = received + row("c", "新实时消息")
        val window = sampled.takeLast(2)
        assertFalse(hasPresentedHistoryWindow(received.last(), received, received.take(1), received.take(1), received.take(1)))
        assertFalse(hasPresentedHistoryWindow(received.last(), received, sampled, window, received))
        assertTrue(hasPresentedHistoryWindow(received.last(), received, sampled, window, window))
        val muchLater = sampled + row("d", "更多实时消息")
        assertTrue(hasPresentedHistoryWindow(received.last(), received, muchLater, muchLater.takeLast(2), muchLater.takeLast(2)))
    }

    @Test fun manualRefreshWaitsForItsDisplayedRevisionNotAnOldFrameOrOtherThread() = runTest {
        val receipts = MutableStateFlow<HistoryPresentationReceipt?>(HistoryPresentationReceipt("thread", 1))
        val work = async { awaitHistoryPresentation(receipts, "thread", 2) }
        runCurrent()
        assertFalse(work.isCompleted)
        receipts.value = HistoryPresentationReceipt("other", 9)
        runCurrent()
        assertFalse(work.isCompleted)
        receipts.value = HistoryPresentationReceipt("thread", 3)
        work.await()
    }

    @Test fun displayTimeoutPreservesStageAndCancellationStillPropagates() = runTest {
        val receipts = MutableStateFlow<HistoryPresentationReceipt?>(null)
        val failure = assertFailsWith<TimeoutException> { awaitHistoryPresentation(receipts, "thread", 2, timeoutMillis = 100) }
        assertTrue(failure.message!!.contains("stage=history-presentation"))
        assertTrue(failure.message!!.contains("revision=2"))
        val work = async { awaitHistoryPresentation(receipts, "thread", 2) }
        runCurrent()
        work.cancel(CancellationException("left conversation"))
        assertFailsWith<CancellationException> { work.await() }
    }

    @Test fun hiddenTimelinePausesDisplayDeadlineInsteadOfReportingARefreshFailure() = runTest {
        val receipts = MutableStateFlow<HistoryPresentationReceipt?>(null)
        val visible = MutableStateFlow(false)
        val work = async { awaitHistoryPresentation(receipts, "thread", 2, timeoutMillis = 100, renderingEnabled = visible) }
        advanceTimeBy(1000)
        assertFalse(work.isCompleted)
        visible.value = true
        runCurrent()
        receipts.value = HistoryPresentationReceipt("thread", 2)
        work.await()
    }
}
