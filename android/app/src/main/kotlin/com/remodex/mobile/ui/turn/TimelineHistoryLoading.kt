package com.remodex.mobile.ui.turn

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.hasConversationContent
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.ui.agent.TimelineListItem
import com.remodex.mobile.ui.agent.TimelinePresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

/** One automatic load per user gesture, including its fling; programmatic scrolling never arms it. */
internal class TimelineHistoryAutoLoadGate {
    private var requested = false
    private var towardHistory = false
    private var userGesture = false
    fun beginGesture() { requested = false; towardHistory = false; userGesture = true }
    fun cancelGesture() { userGesture = false; towardHistory = false }
    fun scroll(deltaY: Float, userInput: Boolean, atStart: Boolean, enabled: Boolean): Boolean {
        if (userInput && deltaY != 0f) towardHistory = deltaY > 0f
        if (!userGesture || !towardHistory || requested || deltaY <= 0f || !atStart || !enabled) return false
        requested = true
        return true
    }
}

internal fun timelineWindowStartIndex(messages: List<CodexMessage>, firstId: String?, tailCount: Int, following: Boolean): Int {
    val readerStart = if (!following && firstId != null) messages.indexOfFirst { it.id == firstId } else -1
    val start = readerStart.takeIf { it >= 0 } ?: (messages.size - tailCount).coerceAtLeast(0)
    if (start == 0) return 0
    // Activity rows can occupy the whole tail of a long-running turn.
    for (i in messages.lastIndex downTo 0) {
        if (messages[i].hasConversationContent()) return minOf(start, i)
    }
    return start
}

private fun TimelineListItem.messageIds(): List<String> = when (this) {
    is TimelineListItem.Single -> listOf(message.id)
    is TimelineListItem.MessageChunk -> listOf(message.id)
    is TimelineListItem.AssistantWorkGroup -> messages.map { it.id }
    is TimelineListItem.CommandExecutionGroup -> messages.map { it.id }
    is TimelineListItem.FileChangeGroup -> messages.map { it.id }
}

internal data class HistoryViewportAnchor(val key: String, val messageId: String?, val offset: Int)
internal fun historyAnchorIndex(items: List<TimelineListItem>, anchor: HistoryViewportAnchor): Int? =
    items.indexOfFirst { it.stableKey == anchor.key }.takeIf { it >= 0 }
        ?: anchor.messageId?.let { id -> items.indexOfFirst { id in it.messageIds() }.takeIf { it >= 0 } }

internal data class TimelineHistoryLoadingUi(val modifier: Modifier, val busy: Boolean, val error: String?, val showHeader: Boolean, val load: () -> Unit)

private data class PendingHistoryAnchor(val anchor: HistoryViewportAnchor?, val firstId: String, val gesture: Int)

/** The repository receives pages immediately; reveal them at idle with the visible message anchored. */
@Composable
internal fun rememberTimelineHistoryLoading(
    threadId: String,
    repository: CodexRepository,
    listState: LazyListState,
    messages: List<CodexMessage>,
    visibleMessages: List<CodexMessage>,
    presentation: TimelinePresentation,
    displayedHiddenCount: Int,
    hiddenCount: Int,
    canLoadRemote: Boolean,
    remoteLoading: Boolean,
    error: String?,
    ready: Boolean,
    historyRevision: Long,
    onReadHistory: () -> Unit,
    onReveal: (String, Int) -> Unit,
): TimelineHistoryLoadingUi = key(threadId, historyRevision) {
    val scope = rememberCoroutineScope()
    val gate = remember { TimelineHistoryAutoLoadGate() }
    var busy by remember { mutableStateOf(false) }
    var localFailure by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<PendingHistoryAnchor?>(null) }
    var gesture by remember { mutableIntStateOf(0) }
    val latestMessages by rememberUpdatedState(messages)
    val latestVisibleMessages by rememberUpdatedState(visibleMessages)
    val latestPresentation by rememberUpdatedState(presentation)
    val showHeader = displayedHiddenCount > 0 || canLoadRemote || remoteLoading || busy || !error.isNullOrBlank() || localFailure != null
    val latestHeaderCount by rememberUpdatedState(if (showHeader) 1 else 0)
    val visible by rememberUpdatedState(LocalTimelineRenderingEnabled.current)
    val hidden by rememberUpdatedState(hiddenCount)
    val canRemote by rememberUpdatedState(canLoadRemote)
    val loadingRemote by rememberUpdatedState(remoteLoading)
    val connectionReady by rememberUpdatedState(ready)
    val readHistory by rememberUpdatedState(onReadHistory)
    val reveal by rememberUpdatedState(onReveal)
    val failure by rememberUpdatedState(error)

    val load: () -> Unit = remember(repository, listState) {
        load@{
            if (busy || pending != null || loadingRemote || !visible) return@load
            if (hidden == 0 && (!connectionReady || (!canRemote && failure.isNullOrBlank()))) return@load
            busy = true
            localFailure = null
            readHistory()
            scope.launch {
                var stage = "older_history_request"
                try {
                    val beforeMessageId = latestVisibleMessages.firstOrNull()?.id
                    if (connectionReady && canRemote) {
                        loadOlderHistoryUntilVisibleProgress(
                            beforeMessageId = beforeMessageId,
                            messages = { repository.messagesByThread.value[threadId].orEmpty() },
                            pagination = { repository.threadHistoryPaginationByThread.value[threadId] },
                            loadPage = {
                                if (!repository.isSessionReady.value) throw CodexServiceError.Disconnected
                                repository.loadOlderThreadHistory(threadId)
                                repository.loadingOlderHistoryThreadIds.first { threadId !in it }
                                if (!repository.isSessionReady.value) throw CodexServiceError.Disconnected
                                repository.olderHistoryErrorByThread.value[threadId]?.let { error(it) }
                            },
                        )
                        // Only the fetched prefix is needed. The live tail can keep changing.
                        stage = "history_display_catchup"
                        val receivedFirst = repository.messagesByThread.value[threadId]?.firstOrNull()?.id
                        if (receivedFirst != null) awaitHistoryPresentation(stage) {
                            snapshotFlow { latestMessages }.first { rows -> rows.any { it.id == receivedFirst } }
                        }
                    }
                    stage = "history_reveal"
                    snapshotFlow { !visible || !listState.isScrollInProgress }.first { it }
                    if (!visible) return@launch
                    val rows = latestMessages
                    val start = olderHistoryBoundary(rows, beforeMessageId)
                    val nextStart = olderHistoryRevealStart(rows, start)
                    if (nextStart < start) {
                        val layout = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key != "load-earlier-messages" }
                        val item = layout?.let { entry -> latestPresentation.items.firstOrNull { it.stableKey == entry.key } }
                        val anchor = if (layout != null && item != null) HistoryViewportAnchor(item.stableKey, item.messageIds().firstOrNull(), layout.offset) else null
                        pending = PendingHistoryAnchor(anchor, rows[nextStart].id, gesture)
                        reveal(rows[nextStart].id, rows.size - nextStart)
                        stage = "history_presentation"
                        awaitHistoryPresentation(stage) { snapshotFlow { pending == null || !visible }.first { it } }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    localFailure = "$stage: ${failure.message ?: failure.javaClass.name}"
                    if (stage != "older_history_request" || repository.olderHistoryErrorByThread.value[threadId].isNullOrBlank()) {
                        android.util.Log.e("RemodexHistoryUI", "operation=load-older thread=$threadId stage=$stage attempt=1", failure)
                    }
                } finally { pending = null; busy = false }
            }
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> { gesture++; gate.beginGesture() }
                is DragInteraction.Cancel -> gate.cancelGesture()
                else -> Unit // A released drag may still fling into the history boundary.
            }
        }
    }
    val canAuto by rememberUpdatedState(
        visible && !busy && pending == null && !remoteLoading && error.isNullOrBlank() && localFailure == null &&
            (hiddenCount > 0 || (ready && canLoadRemote)),
    )
    val connection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (gate.scroll(available.y, source == NestedScrollSource.UserInput, !listState.canScrollBackward, canAuto)) load()
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (gate.scroll(consumed.y + available.y, source == NestedScrollSource.UserInput, !listState.canScrollBackward, canAuto)) load()
                return Offset.Zero
            }
        }
    }
    // Commit the anchor with the new item provider, before its first measurement/draw.
    // scrollToItem followed by scrollBy exposes the intermediate position for a frame.
    // Never keep correcting offsets after this transaction: subsequent gestures own scrolling.
    SideEffect {
        val request = pending
        if (request != null && presentation.messages.firstOrNull()?.id == request.firstId &&
            visibleMessages.firstOrNull()?.id == request.firstId) {
            if (gesture == request.gesture && !listState.isScrollInProgress) request.anchor?.let { anchor ->
                historyAnchorIndex(presentation.items, anchor)?.let { index ->
                    listState.requestScrollToItem(index + latestHeaderCount, -anchor.offset)
                }
            }
            pending = null
        }
    }
    TimelineHistoryLoadingUi(Modifier.nestedScroll(connection), busy || pending != null, localFailure, showHeader, load)
}

private suspend fun awaitHistoryPresentation(stage: String, wait: suspend () -> Unit) {
    if (withTimeoutOrNull(15_000) { wait(); true } == null) {
        throw TimeoutException("History display timed out stage=$stage timeoutMs=15000")
    }
}
