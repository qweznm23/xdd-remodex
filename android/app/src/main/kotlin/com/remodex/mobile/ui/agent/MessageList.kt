package com.remodex.mobile.ui.agent

import androidx.lifecycle.repeatOnLifecycle
import android.content.ClipData
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.R
import com.remodex.mobile.core.model.AIChangeSet
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageDeliveryState
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.core.model.CommandExecutionDetails
import com.remodex.mobile.ui.turn.ReasoningSummaryRow
import com.remodex.mobile.ui.turn.ChatDetailSheet
import com.remodex.mobile.ui.turn.TimelineActionRow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.ChevronRight
import com.remodex.mobile.ui.turn.TurnMessageRow
import com.remodex.mobile.ui.turn.CommandGroupRow
import com.remodex.mobile.ui.turn.TurnTimelineGroupedRunsRow
import kotlinx.coroutines.launch

internal const val PREDICTION_FOOTER_KEY = "composer-prediction-footer"

/**
 * Scrollable conversation timeline. SwiftUI: `ScrollView` + `LazyVStack` pattern.
 */
@Composable
internal fun MessageList(
    presentation: TimelinePresentation,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    showEmptyContent: Boolean = true,
    hiddenEarlierCount: Int = 0,
    canLoadEarlierMessages: Boolean = true,
    isLoadingOlderHistory: Boolean = false,
    olderHistoryError: String? = null,
    onLoadEarlierMessages: (() -> Unit)? = null,
    commandExecutionDetailsByItemId: Map<String, CommandExecutionDetails> = emptyMap(),
    onOpenFullMessage: ((CodexMessage) -> Unit)? = null,
    onOpenPlanDetails: ((CodexMessage) -> Unit)? = null,
    onApplyPlan: ((CodexMessage) -> Unit)? = null,
    canApplyPlan: Boolean = true,
    isAssistantTurnActive: Boolean = false,
    activeTurnId: String? = null,
    activityTimings: Map<String, TurnActivityTiming> = emptyMap(),
    predictionFooter: (@Composable () -> Unit)? = null,
    verticalItemSpacing: Dp = 10.dp,
    contentPadding: PaddingValues =
        PaddingValues(top = 10.dp, bottom = 18.dp, start = 2.dp, end = 2.dp),
    emptyContent: @Composable BoxScope.() -> Unit = {
        Column(
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.remodex_icon),
                contentDescription = null,
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = stringResource(R.string.turn_timeline_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    },
    messageContent: @Composable (CodexMessage) -> Unit = { msg ->
        when (msg.role) {
            CodexMessageRole.user ->
                UserMessageBubble(message = msg, onOpenFullMessage = onOpenFullMessage)
            CodexMessageRole.assistant ->
                AssistantMessageBlock(
                    message = msg,
                    onOpenFullMessage = onOpenFullMessage,
                    onOpenPlanDetails = onOpenPlanDetails,
                    onApplyPlan = onApplyPlan,
                    canApplyPlan = canApplyPlan,
                )
            CodexMessageRole.system ->
                TurnMessageRow(
                    message = msg,
                    commandExecutionDetails = msg.itemId?.let { commandExecutionDetailsByItemId[it] },
                    onOpenFullMessage = onOpenFullMessage,
                    onOpenPlanDetails = onOpenPlanDetails,
                    onApplyPlan = onApplyPlan,
                    canApplyPlan = canApplyPlan,
                )
        }
    },
    /** When non-null, shows copy + fork icon row under the last assistant message (no card container). */
    onForkThread: (() -> Unit)? = null,
    forkThreadEnabled: Boolean = false,
    assistantUndoChangeSetsByMessageId: Map<String, AIChangeSet> = emptyMap(),
    applyingUndoChangeSetIds: Set<String> = emptySet(),
    onUndoAssistantChanges: ((AIChangeSet) -> Unit)? = null,
) {
    val messages = presentation.messages
    val lastAssistantIndex = messages.indexOfLast { it.role == CodexMessageRole.assistant }
    val lastAssistantMessage = messages.getOrNull(lastAssistantIndex)
    val showTrailingActions =
        shouldShowAssistantTrailingActions(
            lastAssistantMessage = lastAssistantMessage,
            isAssistantTurnActive = isAssistantTurnActive,
            onForkThreadAvailable = onForkThread != null,
        )
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val timelineItems = presentation.items
    val transientStatusText = presentation.statusText
    val lastAssistantDisplayIndex =
        remember(timelineItems) {
            timelineItems.indexOfLast { item ->
                (item is TimelineListItem.Single && item.message.role == CodexMessageRole.assistant) ||
                    (item is TimelineListItem.MessageChunk && item.message.role == CodexMessageRole.assistant)
            }
        }
    val lastAssistantText =
        if (lastAssistantIndex >= 0) {
            messages[lastAssistantIndex].text.trim()
        } else {
            ""
        }
    val lastAssistantChangeSet =
        if (lastAssistantIndex >= 0) {
            assistantUndoChangeSetsByMessageId[messages[lastAssistantIndex].id]
        } else {
            null
        }
    val copyEnabled = lastAssistantText.isNotEmpty()
    val copyActionLabel = stringResource(R.string.turn_message_action_copy_cd)
    val forkActionLabel = stringResource(R.string.turn_message_action_fork_cd)
    val undoActionLabel = stringResource(R.string.turn_message_action_undo_changes)

    val hasFooter = predictionFooter != null
    val arrangement = remember(verticalItemSpacing, hasFooter) {
        val spaced = Arrangement.spacedBy(verticalItemSpacing)
        if (!hasFooter) spaced else object : Arrangement.Vertical by spaced {
            override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
                with(spaced) { arrange(totalSize, sizes, outPositions) }
                // In short conversations keep the suggestion beside the composer.
                if (sizes.isNotEmpty()) outPositions[sizes.lastIndex] = totalSize - sizes.last()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (messages.isEmpty() && !presentation.hasActivityRow && onLoadEarlierMessages == null) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (showEmptyContent) emptyContent()
            }
        } else {
            LazyColumn(
                state = listState,
                // Chat should stop at its boundary without Android's edge stretch/rebound.
                overscrollEffect = null,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = arrangement,
                contentPadding = contentPadding,
            ) {
                if (onLoadEarlierMessages != null) {
                    item(key = "load-earlier-messages") {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            TextButton(
                                onClick = onLoadEarlierMessages,
                                enabled = canLoadEarlierMessages && !isLoadingOlderHistory,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (isLoadingOlderHistory) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(
                                        if (hiddenEarlierCount > 0) {
                                            stringResource(R.string.turn_timeline_load_earlier_messages, hiddenEarlierCount)
                                        } else {
                                            stringResource(R.string.turn_timeline_load_older_history)
                                        },
                                    )
                                }
                            }
                            olderHistoryError?.takeIf { it.isNotBlank() }?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                }
                itemsIndexed(
                    items = timelineItems,
                    key = { _, item -> item.stableKey },
                    contentType = { _, item -> item.contentType() },
                ) { index, item ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        TimelineListItemContent(
                            item = item,
                            groupDuration = if (item is TimelineListItem.AssistantWorkGroup)
                                activityTimings[item.messages.firstNotNullOfOrNull { it.turnId }]?.finalDuration else null,
                            messageContent = messageContent,
                            commandExecutionDetailsByItemId = commandExecutionDetailsByItemId,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        presentation.elapsedFooterKeys[item.stableKey]?.takeUnless { it == activeTurnId }?.let { turn ->
                            activityTimings[turn]?.finalDuration?.let { duration -> ActivityElapsedText(duration) }
                        }
                        if (showTrailingActions && index == lastAssistantDisplayIndex) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 2.dp, top = 2.dp, bottom = 0.dp),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = {
                                        if (copyEnabled) {
                                            scope.launch {
                                                clipboard.setClipEntry(
                                                    ClipData.newPlainText(
                                                        copyActionLabel,
                                                        lastAssistantText,
                                                    ).toClipEntry(),
                                                )
                                            }
                                        }
                                    },
                                    enabled = copyEnabled,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = copyActionLabel,
                                        modifier = Modifier.size(17.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = { onForkThread?.invoke() },
                                    enabled = forkThreadEnabled,
                                ) {
                                    Icon(
                                        painter = painterResource(LucideR.drawable.lucide_ic_git_fork),
                                        contentDescription = forkActionLabel,
                                        modifier =
                                            Modifier
                                                .size(17.dp)
                                                .rotate(90f),
                                        tint =
                                            if (forkThreadEnabled) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        },
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        lastAssistantChangeSet?.let { onUndoAssistantChanges?.invoke(it) }
                                    },
                                    enabled =
                                        lastAssistantChangeSet != null &&
                                            onUndoAssistantChanges != null &&
                                            lastAssistantChangeSet.id !in applyingUndoChangeSetIds,
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Undo,
                                        contentDescription = undoActionLabel,
                                        modifier = Modifier.size(17.dp),
                                        tint =
                                            if (
                                                lastAssistantChangeSet != null &&
                                                lastAssistantChangeSet.id !in applyingUndoChangeSetIds
                                            ) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
                if (transientStatusText != null) {
                    item(key = "transient-activity-status") {
                        Column {
                            TransientActivityStatusRow(transientStatusText, activityTimings[activeTurnId], Modifier.fillMaxWidth())
                            if (presentation.activeSummary.isNotEmpty()) {
                                ReasoningSummaryRow(presentation.activeSummary)
                            }
                        }
                    }
                }
                if (predictionFooter != null) {
                    item(key = PREDICTION_FOOTER_KEY, contentType = PREDICTION_FOOTER_KEY) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { predictionFooter() }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineListItemContent(
    item: TimelineListItem,
    messageContent: @Composable (CodexMessage) -> Unit,
    commandExecutionDetailsByItemId: Map<String, CommandExecutionDetails>,
    modifier: Modifier = Modifier,
    groupDuration: Long? = null,
) {
    when (item) {
        is TimelineListItem.Single ->
            messageContent(item.message)
        is TimelineListItem.MessageChunk -> {
            val candidate = item.toRenderMessage()
            // Earlier chunks keep their message instance while only the last chunk grows.
            val rendered = remember(candidate) { candidate }
            messageContent(rendered)
        }
        is TimelineListItem.AssistantWorkGroup ->
            AssistantWorkGroupRow(
                group = item,
                duration = groupDuration,
                messageContent = messageContent,
                commandExecutionDetailsByItemId = commandExecutionDetailsByItemId,
                modifier = modifier,
            )
        is TimelineListItem.CommandExecutionGroup ->
            CommandGroupRow(
                groupKey = item.stableKey,
                messages = item.messages,
                commandExecutionDetailsByItemId = commandExecutionDetailsByItemId,
            )
        is TimelineListItem.FileChangeGroup ->
            TurnTimelineGroupedRunsRow(
                groupKey = item.stableKey,
                collapsedTitle = stringResource(R.string.turn_timeline_group_modified_files),
                messages = item.messages,
                commandExecutionDetailsByItemId = commandExecutionDetailsByItemId,
            )
    }
}

internal fun TimelineListItem.contentType(): String = when (this) {
    is TimelineListItem.Single -> message.timelineContentType()
    is TimelineListItem.MessageChunk -> "assistant_chat_chunk"
    is TimelineListItem.AssistantWorkGroup -> "assistant_work_group"
    is TimelineListItem.CommandExecutionGroup -> "timeline_cmd_group"
    is TimelineListItem.FileChangeGroup -> "timeline_fc_group"
}

private fun CodexMessage.timelineContentType(): String =
    "${role.name}_${kind.name}"

internal fun shouldShowAssistantTrailingActions(
    lastAssistantMessage: CodexMessage?,
    isAssistantTurnActive: Boolean,
    onForkThreadAvailable: Boolean,
): Boolean =
    onForkThreadAvailable &&
        lastAssistantMessage != null &&
        !isAssistantTurnActive &&
        !lastAssistantMessage.isStreaming

internal fun TimelineListItem.MessageChunk.toRenderMessage(): CodexMessage =
    message.copy(
        id = stableKey,
        text = chunkText,
        structuredUserInputRequest = message.structuredUserInputRequest.takeIf { isLastChunk },
        isStreaming = message.isStreaming && isLastChunk,
        attachments =
            if (isFirstChunk) {
                message.attachments
            } else {
                emptyList()
            },
        deliveryState =
            if (isLastChunk) {
                message.deliveryState
            } else {
                CodexMessageDeliveryState.confirmed
            },
    )

@Composable
private fun AssistantWorkGroupRow(
    group: TimelineListItem.AssistantWorkGroup,
    duration: Long?,
    messageContent: @Composable (CodexMessage) -> Unit,
    commandExecutionDetailsByItemId: Map<String, CommandExecutionDetails>,
    modifier: Modifier = Modifier,
) {
    var showDetails by rememberSaveable(group.stableKey) { mutableStateOf(false) }
    val details = stringResource(R.string.turn_timeline_group_work_details)
    val title = if (duration == null) details else stringResource(
        R.string.turn_activity_details_elapsed, details, activityElapsedLabel(duration),
    )
    TimelineActionRow(
        modifier = modifier,
        onClick = { showDetails = true },
        leading = { Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)) },
        trailing = { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp)) },
    ) { Text(title, style = MaterialTheme.typography.bodyMedium) }
    if (showDetails) {
        ChatDetailSheet(title, onDismiss = { showDetails = false }) {
            ActivityDetailsList(group.detailItems) { item ->
                TimelineListItemContent(item, messageContent, commandExecutionDetailsByItemId, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TransientActivityStatusRow(
    statusText: TimelineActivityLabel,
    timing: TurnActivityTiming?,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .background(
                    color = colors.surfaceVariant.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimelineActivityDots(color = colors.onSurfaceVariant)
        Text(
            text = statusText.filename?.let { stringResource(statusText.resource, it) } ?: stringResource(statusText.resource),
            modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant.copy(alpha = 0.64f),
        )
        if (timing?.start != null && timing.finalDuration == null) {
            RunningActivityElapsed(timing.start)
        }
    }
}

@Composable
private fun RunningActivityElapsed(start: Long) {
    val visible = com.remodex.mobile.ui.turn.LocalTimelineRenderingEnabled.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val elapsed by androidx.compose.runtime.produceState(System.currentTimeMillis() - start, start, visible, lifecycle) {
        if (!visible) return@produceState
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis() - start
                kotlinx.coroutines.delay(1000L)
            }
        }
    }
    if (elapsed >= 0) ActivityElapsedText(elapsed, Modifier.padding(start = 8.dp))
}

@Composable
private fun ActivityElapsedText(millis: Long, modifier: Modifier = Modifier) {
    Text(activityElapsedLabel(millis), modifier, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun activityElapsedLabel(millis: Long): String {
    val seconds = millis / 1000
    return when {
        millis in 1..999 -> stringResource(R.string.turn_elapsed_subsecond)
        seconds < 60 -> stringResource(R.string.turn_elapsed_seconds, seconds)
        seconds < 3600 -> stringResource(R.string.turn_elapsed_minutes, seconds / 60, seconds % 60)
        else -> stringResource(R.string.turn_elapsed_hours, seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
}
