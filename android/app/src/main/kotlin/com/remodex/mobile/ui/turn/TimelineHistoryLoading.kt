package com.remodex.mobile.ui.turn

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.ui.agent.TimelineListItem
import com.remodex.mobile.ui.agent.TimelinePresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One automatic page per user gesture, including its fling; programmatic scrolling never arms it. */
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
    if (!following && firstId != null) messages.indexOfFirst { it.id == firstId }.takeIf { it >= 0 }?.let { return it }
    return (messages.size - tailCount).coerceAtLeast(0)
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

internal data class TimelineHistoryLoadingUi(val modifier: Modifier, val busy: Boolean, val error: String?, val load: () -> Unit)

private data class PendingHistoryAnchor(val anchor: HistoryViewportAnchor?, val firstId: String, val gesture: Int)

internal fun shouldContinueHistoryReveal(
    before: TimelineListItem?, after: TimelineListItem?, beforeSize: Int?, afterSize: Int?,
    sameGesture: Boolean, hasMore: Boolean,
): Boolean = sameGesture && hasMore && before is TimelineListItem.AssistantWorkGroup &&
    after is TimelineListItem.AssistantWorkGroup && before.stableKey == after.stableKey &&
    beforeSize != null && beforeSize == afterSize

/** The repository receives pages immediately; reveal them at idle with the visible message anchored. */
@Composable
internal fun rememberTimelineHistoryLoading(
    threadId: String,
    repository: CodexRepository,
    listState: LazyListState,
    messages: List<CodexMessage>,
    visibleMessages: List<CodexMessage>,
    presentation: TimelinePresentation,
    headerCount: Int,
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
    val latestHeaderCount by rememberUpdatedState(headerCount)
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
                    val startedGesture = gesture
                    do {
                        val beforeItem = latestPresentation.items.firstOrNull()
                        val beforeSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == beforeItem?.stableKey }?.size
                        if (hidden == 0) {
                            stage = "older_history_request"
                            loadOlderHistoryUntilVisibleProgress(
                                beforeMessageId = latestVisibleMessages.firstOrNull()?.id,
                                messages = { repository.messagesByThread.value[threadId].orEmpty() },
                                pagination = { repository.threadHistoryPaginationByThread.value[threadId] },
                                loadPage = {
                                    repository.loadOlderThreadHistory(threadId)
                                    // If another caller owned this page, wait for its committed cursor.
                                    repository.loadingOlderHistoryThreadIds.first { threadId !in it }
                                },
                            )
                            // Wait for the sampled prefix, without requiring an ever-moving live tail.
                            stage = "history_display_catchup"
                            val receivedFirst = repository.messagesByThread.value[threadId]?.firstOrNull()?.id
                            if (receivedFirst != null) snapshotFlow { latestMessages }.first { rows -> rows.any { it.id == receivedFirst } }
                        }
                        stage = "history_reveal"
                        snapshotFlow { !listState.isScrollInProgress && visible }.first { it }
                        val rows = latestMessages
                        val shown = latestVisibleMessages
                        val start = rows.indexOfFirst { it.id == shown.firstOrNull()?.id }.takeIf { it >= 0 } ?: rows.size
                        val nextStart = (start - 80).coerceAtLeast(0)
                        if (nextStart < start && rows.isNotEmpty()) {
                            val layout = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key != "load-earlier-messages" }
                            val item = layout?.let { latestPresentation.items.getOrNull(it.index - latestHeaderCount) }
                            val anchor = if (layout != null && item != null) HistoryViewportAnchor(item.stableKey, item.messageIds().firstOrNull(), layout.offset) else null
                            pending = PendingHistoryAnchor(anchor, rows[nextStart].id, gesture)
                            reveal(rows[nextStart].id, rows.size - nextStart)
                            stage = "history_presentation"
                            snapshotFlow { pending == null }.first { it }
                            withFrameNanos { } // Measure the committed row before checking visible progress.
                        } else break
                        val afterItem = latestPresentation.items.firstOrNull()
                        val afterSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == afterItem?.stableKey }?.size
                        // Cached work may only enlarge a collapsed card's hidden contents. An
                        // expanded card grows in height and stops after one ordinary batch.
                    } while (shouldContinueHistoryReveal(beforeItem, afterItem, beforeSize, afterSize,
                        startedGesture == gesture, hidden > 0 || (connectionReady && canRemote)))
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
                    listState.requestScrollToItem(index + headerCount, -anchor.offset)
                }
            }
            pending = null
        }
    }
    TimelineHistoryLoadingUi(Modifier.nestedScroll(connection), busy || pending != null, localFailure, load)
}
