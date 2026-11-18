package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.R
import com.remodex.mobile.core.model.AIChangeSet
import com.remodex.mobile.core.model.AIChangeSetStatus
import com.remodex.mobile.core.model.AssistantRevertPrimaryBlockReason
import com.remodex.mobile.core.model.CodexRateLimitBucket
import com.remodex.mobile.core.model.CodexRateLimitDisplayRow
import com.remodex.mobile.core.model.ContextWindowUsage
import com.remodex.mobile.core.model.TurnUsageSheetLogic
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.data.QueuedTurnDraftPreview
import com.remodex.mobile.services.AiChangeSetRevertService
import com.remodex.mobile.ui.LocalAIChangeSetPersistence
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * J.7d: detailed usage + account rate limits for the current thread, backed by
 * [CodexRepository] flows (same as Settings and [TurnComposerUsageStrip]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadUsageStatusBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    threadId: String,
    repository: CodexRepository,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    RemodexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
    ) {
        ThreadUsageStatusSheetContent(
            threadId = threadId,
            repository = repository,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ThreadUsageStatusSheetContent(
    threadId: String,
    repository: CodexRepository,
    onDismiss: () -> Unit,
) {
    val ready by repository.isSessionReady.collectAsStateWithLifecycle()
    val conn by repository.connectionState.collectAsStateWithLifecycle()
    val hasResolved by repository.hasResolvedRateLimitsSnapshot.collectAsStateWithLifecycle()
    val isLoadingRL by repository.isLoadingRateLimits.collectAsStateWithLifecycle()
    val rlErr by repository.rateLimitsErrorMessage.collectAsStateWithLifecycle()
    val buckets by repository.rateLimitBuckets.collectAsStateWithLifecycle()
    val accountDetails by repository.accountUsageDetails.collectAsStateWithLifecycle()
    val contextUsageMap by repository.contextWindowUsageByThread.collectAsStateWithLifecycle()
    val contextLoading by repository.contextWindowUsageLoadingThreads.collectAsStateWithLifecycle()
    val contextErrors by repository.contextWindowUsageErrorByThread.collectAsStateWithLifecycle()
    val runningTurnByThread by repository.runningTurnIdByThread.collectAsStateWithLifecycle()
    val protectedRunningFallback by repository.protectedRunningFallbackThreadIds.collectAsStateWithLifecycle()
    val queuedDepthByThread by repository.turnDraftQueueDepthByThread.collectAsStateWithLifecycle()
    val queuedPreviewByThread by repository.turnDraftQueuePreviewByThread.collectAsStateWithLifecycle()
    val threads by repository.threads.collectAsStateWithLifecycle()
    val currentTrustedMacDeviceId by repository.currentTrustedMacDeviceId.collectAsStateWithLifecycle()
    val aiChangeSetPersistence = LocalAIChangeSetPersistence.current

    val displayRows = remember(buckets) { CodexRateLimitBucket.visibleDisplayRows(buckets) }
    val usage: ContextWindowUsage? = contextUsageMap[threadId]
    val ctxLoading = contextLoading.contains(threadId)
    val ctxErr = contextErrors[threadId]
    val connected = conn is ConnectionState.Connected
    var refreshing by remember { mutableStateOf(false) }
    var refreshFailure by remember { mutableStateOf<Exception?>(null) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val anyLoading = refreshing || ctxLoading || isLoadingRL
    val scope = rememberCoroutineScope()
    val isThreadRunning =
        remember(threadId, runningTurnByThread, protectedRunningFallback) {
            TurnUsageSheetLogic.isThreadTurnActive(threadId, runningTurnByThread, protectedRunningFallback)
        }
    val queuedDraftCount =
        remember(threadId, queuedDepthByThread) {
            queuedDepthByThread[threadId] ?: 0
        }
    val queuedPreviews =
        remember(threadId, queuedPreviewByThread) {
            queuedPreviewByThread[threadId].orEmpty()
        }
    var recentChangeSets by remember(threadId, currentTrustedMacDeviceId) {
        mutableStateOf(
            TurnUsageSheetLogic.recentChangeSetsForThread(
                threadId,
                aiChangeSetPersistence.load(currentTrustedMacDeviceId),
            ),
        )
    }
    val threadCwd =
        remember(threadId, threads) {
            threads.firstOrNull { it.id == threadId }?.cwd
        }

    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        refreshFailure = null
        try {
            repository.refreshUsageStatus(threadId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            refreshFailure = failure
            android.util.Log.e("RemodexUsage", "operation=usage-refresh thread=$threadId stage=sheet attempt=1", failure)
        } finally {
            refreshing = false
        }
    }
    LaunchedEffect(threadId, currentTrustedMacDeviceId) {
        recentChangeSets = TurnUsageSheetLogic.recentChangeSetsForThread(
            threadId, aiChangeSetPersistence.load(currentTrustedMacDeviceId))
        if (ready && connected) refresh()
    }
    val errors = listOfNotNull(ctxErr, rlErr, refreshFailure?.let { it.message ?: it.javaClass.name }).distinct()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(Instant.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = Instant.now()
                delay(60_000)
            }
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val lastRead = accountDetails.lastSuccessfulReadAt?.let { formatUsageCalendarTime(it, now, locale = locale) }
        ?: stringResource(R.string.usage_details_not_read)

    // Keep the sheet's anchor and header stable while independent RPCs publish results.
    Column(Modifier.fillMaxWidth().fillMaxHeight(.88f)) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.turn_usage_sheet_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            val refreshLabel = stringResource(R.string.cd_turn_usage_sheet_refresh_all)
            IconButton(onClick = { scope.launch { refresh() } }, enabled = ready && connected && !anyLoading,
                modifier = Modifier.semantics { contentDescription = refreshLabel }) {
                if (anyLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Refresh, null)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, stringResource(R.string.cd_turn_usage_sheet_close))
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(when {
                    !ready || !connected -> R.string.usage_rate_limits_offline
                    anyLoading -> R.string.usage_refreshing_keep_data
                    errors.isNotEmpty() -> R.string.usage_refresh_failed_keep_data
                    else -> R.string.usage_last_successful_read
                }, lastRead),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (errors.isNotEmpty() && !anyLoading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (errors.isNotEmpty()) TextButton(onClick = { showErrors = !showErrors }) {
                Text(stringResource(R.string.questions_error_details))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (showErrors && errors.isNotEmpty()) UsageSection(stringResource(R.string.questions_error_details)) {
                errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            UsageSection(stringResource(R.string.usage_account_limits_title)) {
                RateLimitsStatusBlock(displayRows, hasResolved, now)
            }
            UsageSection(stringResource(R.string.usage_context_window_title)) {
                ContextWindowStatusBlock(usage)
            }
            UsageSection(stringResource(R.string.turn_usage_runtime_title)) {
                ThreadRuntimeStatusBlock(connected, ready, isThreadRunning, queuedDraftCount, queuedPreviews) { draftId ->
                    scope.launch {
                        runCatching { repository.removeQueuedTurnDraft(threadId, draftId) }
                    }
                }
            }
            var showDetails by rememberSaveable { mutableStateOf(false) }
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.usage_balance_cards), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { showDetails = !showDetails }, modifier = Modifier.size(32.dp)) {
                            Icon(if (showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                stringResource(if (showDetails) R.string.turn_usage_revert_hide_details else R.string.turn_usage_revert_show_details))
                        }
                    }
                    AccountUsageSummary(accountDetails)
                    if (showDetails) AccountUsageDetailsContent(accountDetails)
                }
            }
            if (recentChangeSets.isNotEmpty()) {
                AssistantRevertStatusBlock(
                    changeSets = recentChangeSets,
                    repository = repository,
                    threadCwd = threadCwd,
                    onChangeSetsUpdated = {
                        recentChangeSets =
                            TurnUsageSheetLogic.recentChangeSetsForThread(
                                threadId,
                                aiChangeSetPersistence.load(currentTrustedMacDeviceId),
                            )
                    },
                    markChangeSetReverted = { changeSetId ->
                        val now = Instant.now()
                        aiChangeSetPersistence.save(
                            TurnUsageSheetLogic.markChangeSetReverted(
                                changeSets = aiChangeSetPersistence.load(currentTrustedMacDeviceId),
                                changeSetId = changeSetId,
                                now = now,
                            ),
                            currentTrustedMacDeviceId,
                        )
                    },
                    recordChangeSetRevertError = { changeSetId, errorMessage ->
                        val now = Instant.now()
                        aiChangeSetPersistence.save(
                            TurnUsageSheetLogic.recordChangeSetRevertError(
                                changeSets = aiChangeSetPersistence.load(currentTrustedMacDeviceId),
                                changeSetId = changeSetId,
                                message = errorMessage,
                                now = now,
                            ),
                            currentTrustedMacDeviceId,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun UsageSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ContextWindowStatusBlock(usage: ContextWindowUsage?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(usage?.let { "${it.percentUsed}%" } ?: "—", style = MaterialTheme.typography.headlineMedium)
        LinearProgressIndicator(progress = { usage?.fractionUsed?.toFloat()?.coerceIn(0f, 1f) ?: 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp))
        Text(usage?.let { stringResource(R.string.usage_context_window_tokens, it.tokensUsedFormatted, it.tokenLimitFormatted, it.percentUsed) }
            ?: stringResource(R.string.usage_context_no_data),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ThreadRuntimeStatusBlock(
    connected: Boolean,
    sessionReady: Boolean,
    isThreadRunning: Boolean,
    queuedDraftCount: Int,
    queuedPreviews: List<QueuedTurnDraftPreview>,
    onRemoveQueuedDraft: (String) -> Unit,
) {
    val queueItemEmptyLabel = stringResource(R.string.turn_queue_item_empty)
    val statusItems = listOf(
        stringResource(if (connected) R.string.turn_usage_runtime_connected else R.string.turn_usage_runtime_disconnected) to connected,
        stringResource(if (sessionReady) R.string.turn_usage_runtime_ready else R.string.turn_usage_runtime_not_ready) to sessionReady,
        stringResource(if (isThreadRunning) R.string.turn_usage_runtime_running else R.string.turn_usage_runtime_idle) to isThreadRunning,
    )
    statusItems.forEach { (text, active) ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(Modifier.size(7.dp), shape = RoundedCornerShape(50),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {}
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (queuedDraftCount > 0) {
        Text(
            text = stringResource(R.string.turn_queue_pending_count, queuedDraftCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        queuedPreviews.take(3).forEach { draft ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = draft.text.lines().firstOrNull()?.trim().orEmpty().ifBlank { queueItemEmptyLabel },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val meta =
                        buildList {
                            if (draft.attachmentCount > 0) {
                                add(stringResource(R.string.turn_queue_item_attachments, draft.attachmentCount))
                            }
                            if (draft.collaborationMode == com.remodex.mobile.core.model.CodexCollaborationModeKind.plan) {
                                add(stringResource(R.string.turn_plan_mode_chip))
                            }
                        }.joinToString(" / ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { onRemoveQueuedDraft(draft.id) }) {
                    Text(stringResource(R.string.turn_queue_remove))
                }
            }
        }
        if (queuedPreviews.size > 3) {
            Text(
                text = stringResource(R.string.turn_queue_more_items, queuedPreviews.size - 3),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AssistantRevertStatusBlock(
    changeSets: List<AIChangeSet>,
    repository: CodexRepository,
    threadCwd: String?,
    onChangeSetsUpdated: () -> Unit,
    markChangeSetReverted: (changeSetId: String) -> Unit,
    recordChangeSetRevertError: (changeSetId: String, errorMessage: String) -> Unit,
) {
    Text(
        text = stringResource(R.string.turn_usage_revert_title),
        style = MaterialTheme.typography.titleSmall,
    )
    if (changeSets.isEmpty()) {
        Text(
            text = stringResource(R.string.turn_usage_revert_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val runtimeRevertRpcAvailable = true
    val revertService = remember(repository) { AiChangeSetRevertService(repository) }
    val scope = rememberCoroutineScope()
    var expandedIds by remember(changeSets) { mutableStateOf(emptySet<String>()) }
    var applyingIds by remember(changeSets) { mutableStateOf(emptySet<String>()) }
    var actionErrors by remember(changeSets) { mutableStateOf<Map<String, String>>(emptyMap()) }
    changeSets.take(6).forEach { changeSet ->
        val statusLabel =
            when (changeSet.status) {
                AIChangeSetStatus.collecting -> stringResource(R.string.turn_usage_revert_status_collecting)
                AIChangeSetStatus.ready -> stringResource(R.string.turn_usage_revert_status_ready)
                AIChangeSetStatus.reverted -> stringResource(R.string.turn_usage_revert_status_reverted)
                AIChangeSetStatus.failed -> stringResource(R.string.turn_usage_revert_status_failed)
                AIChangeSetStatus.notRevertable -> stringResource(R.string.turn_usage_revert_status_not_revertable)
            }
        val blockReason = TurnUsageSheetLogic.revertPrimaryBlockReason(changeSet, runtimeRevertRpcAvailable)
        val blockReasonMessage =
            when (blockReason) {
                AssistantRevertPrimaryBlockReason.RuntimeEndpointUnavailable ->
                    stringResource(R.string.turn_usage_revert_reason_runtime_unavailable)
                AssistantRevertPrimaryBlockReason.AlreadyReverted ->
                    stringResource(R.string.turn_usage_revert_reason_already_reverted)
                AssistantRevertPrimaryBlockReason.StatusNotReady ->
                    stringResource(R.string.turn_usage_revert_reason_not_ready)
                AssistantRevertPrimaryBlockReason.MissingInversePatch ->
                    stringResource(R.string.turn_usage_revert_reason_missing_inverse)
                AssistantRevertPrimaryBlockReason.NotRevertableStatus ->
                    stringResource(R.string.turn_usage_revert_reason_not_revertable)
                AssistantRevertPrimaryBlockReason.ChangeSetFailed ->
                    stringResource(R.string.turn_usage_revert_reason_failed)
                null -> null
            }
        val isExpanded = changeSet.id in expandedIds
        val isApplying = changeSet.id in applyingIds
        val actionError = actionErrors[changeSet.id]
        val workingDirectory = changeSet.repoRoot ?: threadCwd
        val blockReasonOverride =
            when {
                workingDirectory.isNullOrBlank() ->
                    stringResource(R.string.turn_usage_revert_reason_missing_cwd)
                else -> null
            }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            shape = MaterialTheme.shapes.small,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.turn_usage_revert_item_title, changeSet.turnId.takeLast(8)),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text =
                        stringResource(
                            R.string.turn_usage_revert_item_metrics,
                            changeSet.fileChanges.size,
                            changeSet.fileChanges.sumOf { it.additions },
                            changeSet.fileChanges.sumOf { it.deletions },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            expandedIds =
                                if (isExpanded) {
                                    expandedIds - changeSet.id
                                } else {
                                    expandedIds + changeSet.id
                                }
                        },
                    ) {
                        Text(
                            if (isExpanded) {
                                stringResource(R.string.turn_usage_revert_hide_details)
                            } else {
                                stringResource(R.string.turn_usage_revert_show_details)
                            },
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            if (workingDirectory.isNullOrBlank() || isApplying) return@TextButton
                            scope.launch {
                                applyingIds = applyingIds + changeSet.id
                                actionErrors = actionErrors - changeSet.id
                                val result =
                                    runCatching {
                                        revertService.apply(changeSet = changeSet, workingDirectory = workingDirectory)
                                    }
                                result
                                    .onSuccess { applyResult ->
                                        if (applyResult.success) {
                                            markChangeSetReverted(changeSet.id)
                                        } else {
                                            val message =
                                                applyResult.unsupportedReasons.firstOrNull()
                                                    ?: applyResult.conflicts.firstOrNull()?.message
                                                    ?: "Patch revert failed."
                                            recordChangeSetRevertError(changeSet.id, message)
                                            actionErrors = actionErrors + (changeSet.id to message)
                                        }
                                        onChangeSetsUpdated()
                                    }.onFailure { error ->
                                        val message = error.message ?: "Patch revert failed."
                                        recordChangeSetRevertError(changeSet.id, message)
                                        actionErrors = actionErrors + (changeSet.id to message)
                                        onChangeSetsUpdated()
                                    }
                                applyingIds = applyingIds - changeSet.id
                            }
                        },
                        enabled =
                            !isApplying &&
                                !workingDirectory.isNullOrBlank() &&
                                TurnUsageSheetLogic.revertPrimaryEnabled(changeSet, runtimeRevertRpcAvailable),
                    ) {
                        Text(stringResource(R.string.turn_usage_revert_action))
                    }
                }
                if (blockReasonOverride != null || blockReasonMessage != null) {
                    Text(
                        text = blockReasonOverride ?: blockReasonMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (actionError != null) {
                    Text(
                        text = actionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isExpanded) {
                    changeSet.unsupportedReasons.take(3).forEach { reason ->
                        Text(
                            text = "- $reason",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    changeSet.fileChanges.take(5).forEach { fileChange ->
                        Text(
                            text =
                                stringResource(
                                    R.string.turn_usage_revert_item_file_line,
                                    fileChange.path,
                                    fileChange.additions,
                                    fileChange.deletions,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
    if (changeSets.size > 6) {
        Text(
            text = stringResource(R.string.turn_usage_revert_more, changeSets.size - 6),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RateLimitsStatusBlock(
    displayRows: List<CodexRateLimitDisplayRow>,
    hasResolvedSnapshot: Boolean,
    now: Instant,
) {
    if (displayRows.isNotEmpty()) {
        displayRows.forEach { row -> androidx.compose.runtime.key(row.id) { UsageRateLimitRowDetail(row, now) } }
    } else if (!hasResolvedSnapshot) {
        // Reserve the two common windows on the first read. No shimmer or layout animation.
        UsageRateLimitRowDetail(null, now, stringResource(R.string.usage_quota_short_window))
        UsageRateLimitRowDetail(null, now, stringResource(R.string.usage_quota_weekly))
    } else {
        Text(stringResource(R.string.usage_rate_limits_empty), Modifier.heightIn(min = 190.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun UsageRateLimitRowDetail(row: CodexRateLimitDisplayRow?, now: Instant, placeholderLabel: String = "") {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // CJK fallback glyphs are taller than the placeholder; reserve the same
        // number-line height, scaled with accessibility font size.
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp * LocalDensity.current.fontScale), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                row == null -> placeholderLabel
                row.label == "Weekly" -> stringResource(R.string.usage_quota_weekly)
                row.window.windowDurationMins == 300 -> stringResource(R.string.usage_quota_short_window)
                else -> row.label
            }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(row?.let { stringResource(R.string.usage_quota_remaining, it.window.remainingPercent) } ?: "—",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        LinearProgressIndicator(progress = { row?.window?.remainingPercent?.div(100f) ?: 0f }, modifier = Modifier.fillMaxWidth().height(6.dp))
        val reset = row?.window?.resetsAt
        val minutes = reset?.let { remainingQuotaResetMinutes(it, now) }
        val countdown = when {
            minutes == null -> "—"
            minutes == 0L -> stringResource(R.string.usage_reset_due)
            minutes >= 1440 -> stringResource(R.string.usage_reset_in_days, minutes / 1440, minutes % 1440 / 60)
            minutes >= 60 -> stringResource(R.string.usage_reset_in_hours, minutes / 60, minutes % 60)
            else -> stringResource(R.string.usage_reset_in_minutes, minutes)
        }
        val calendar = reset?.let { formatUsageCalendarTime(it, now, locale = LocalConfiguration.current.locales[0]) } ?: "—"
        Text(countdown + "\n" + calendar,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp * LocalDensity.current.fontScale),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AccountUsageSummary(details: com.remodex.mobile.core.model.AccountUsageDetails) {
    val unavailable = stringResource(if (details.lastSuccessfulReadAt == null) R.string.usage_details_not_read else R.string.usage_details_unavailable)
    val balance = details.balances.firstOrNull()?.let {
        when {
            it.unlimited -> stringResource(R.string.usage_credit_unlimited)
            it.balance != null -> it.balance
            !it.hasCredits -> stringResource(R.string.usage_credit_none)
            else -> unavailable
        }
    } ?: unavailable
    listOf(stringResource(R.string.usage_credit_balance_title) to balance,
        stringResource(R.string.usage_reset_cards_title) to (details.resetCredits?.let { stringResource(R.string.usage_reset_cards_count, it.availableCount) } ?: unavailable))
        .forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
}
