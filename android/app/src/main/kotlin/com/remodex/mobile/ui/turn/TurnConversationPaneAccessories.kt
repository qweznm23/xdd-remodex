package com.remodex.mobile.ui.turn

import com.remodex.mobile.ui.theme.LocalSheetClose
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexCollaborationModeKind
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.data.QueuedTurnDraftPreview
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import com.remodex.mobile.ui.theme.isAgentLightChrome

@Composable
internal fun QueuedDraftsCard(
    previews: List<QueuedTurnDraftPreview>,
    totalCount: Int,
    canRestore: Boolean,
    onRestore: (String) -> Unit,
    onRemove: (String) -> Unit,
    error: String?, onRetry: () -> Unit, canSteer: Boolean, enabled: Boolean, onSteer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (totalCount > 0) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 144.dp)) {
                    items(previews.size, key = { previews[it].id }) { index ->
                        val item = previews[index]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.text.ifBlank { stringResource(R.string.turn_queue_item_empty) }, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                val status = stringResource(when {
                                    item.recovery -> R.string.queue_steer_recovery
                                    item.waiting -> R.string.queue_waiting_sync
                                    !item.editable -> R.string.queue_source_editor
                                    else -> R.string.queue_on_host
                                })
                                val detail = if (item.attachmentCount > 0) {
                                    "$status · " + stringResource(R.string.queue_attachment_count, item.attachmentCount)
                                } else status
                                Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                            QueueIcon(com.composables.icons.lucide.R.drawable.lucide_ic_square_pen, R.string.turn_steer_edit_cd, canRestore && item.editable) { onRestore(item.id) }
                            if (canSteer && !item.waiting && item.editable) QueueIcon(com.composables.icons.lucide.R.drawable.lucide_ic_send_horizontal, R.string.turn_steer_cd, enabled) { onSteer(item.id) }
                            QueueIcon(com.composables.icons.lucide.R.drawable.lucide_ic_trash_2, R.string.turn_steer_delete_cd, enabled) { onRemove(item.id) }
                        }
                    }
                }
            }
            if (error != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.error)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.queue_retry_sync)) }
            }
        }
    }
}

@Composable
private fun QueueIcon(icon: Int, label: Int, enabled: Boolean, click: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = click, enabled = enabled) {
        androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(icon), stringResource(label), Modifier.size(18.dp))
    }
}

@Composable
internal fun WorktreeHandoffAccessoryCard(
    isWorktreeProject: Boolean,
    enabled: Boolean,
    inProgress: Boolean,
    onHandoff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        if (isWorktreeProject) {
                            stringResource(R.string.turn_worktree_handoff_to_local_title)
                        } else {
                            stringResource(R.string.turn_worktree_handoff_to_worktree_title)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.turn_worktree_handoff_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                )
            }
            TextButton(
                onClick = onHandoff,
                enabled = enabled && !inProgress,
            ) {
                if (inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text =
                        if (isWorktreeProject) {
                            stringResource(R.string.turn_worktree_handoff_to_local_action)
                        } else {
                            stringResource(R.string.turn_worktree_handoff_to_worktree_action)
                        },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun WorktreeHandoffActionSheet(
    visible: Boolean,
    isWorktreeProject: Boolean,
    inProgress: Boolean,
    availableBaseBranches: List<String>,
    defaultBaseBranch: String?,
    currentBranch: String?,
    sourceProjectPath: String?,
    localTargetPath: String?,
    associatedWorktreePath: String?,
    hasAssociatedWorktree: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val choices =
        remember(availableBaseBranches, currentBranch, defaultBaseBranch) {
            worktreeHandoffBaseBranchChoices(
                availableBranches = availableBaseBranches,
                currentBranch = currentBranch,
                defaultBranch = defaultBaseBranch,
            )
        }
    var selectedBaseBranch by remember(choices, defaultBaseBranch) {
        mutableStateOf(defaultBaseBranch?.takeIf { it in choices } ?: choices.firstOrNull())
    }
    val model =
        worktreeHandoffSheetModel(
            isWorktreeProject = isWorktreeProject,
            inProgress = inProgress,
            selectedBaseBranch = selectedBaseBranch,
            localTargetPath = localTargetPath,
            associatedWorktreePath = associatedWorktreePath.takeIf { hasAssociatedWorktree },
        )
    val requiresBaseBranch = model.requiresBaseBranch
    val canConfirm = model.canConfirm

    RemodexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text =
                    if (isWorktreeProject) {
                        stringResource(R.string.turn_worktree_handoff_sheet_to_local_title)
                    } else {
                        stringResource(R.string.turn_worktree_handoff_sheet_to_worktree_title)
                    },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text =
                    if (isWorktreeProject) {
                        stringResource(R.string.turn_worktree_handoff_sheet_to_local_message)
                    } else {
                        stringResource(R.string.turn_worktree_handoff_sheet_to_worktree_message)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sourceProjectPath?.takeIf { it.isNotBlank() }?.let { path ->
                Text(
                    text = stringResource(R.string.worktree_source, path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isWorktreeProject) {
                model.targetPath?.let { path ->
                    Text(
                        text = stringResource(R.string.worktree_target, path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (hasAssociatedWorktree) {
                model.targetPath?.let { path ->
                    Text(
                        text = stringResource(R.string.worktree_target, path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (requiresBaseBranch) {
                Text(
                    text = stringResource(R.string.worktree_create_from),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (choices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.turn_worktree_handoff_sheet_no_base_branch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    choices.take(8).forEach { branch ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RadioButton(
                                selected = selectedBaseBranch == branch,
                                onClick = { selectedBaseBranch = branch },
                                enabled = !inProgress,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = branch,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                val badges =
                                    buildList {
                                        if (branch == currentBranch) add(stringResource(R.string.git_branch_badge_current))
                                        if (branch == defaultBaseBranch) add(stringResource(R.string.git_branch_badge_default))
                                    }.joinToString(" · ")
                                if (badges.isNotBlank()) {
                                    Text(
                                        text = badges,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = LocalSheetClose.current,
                    enabled = !inProgress,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(
                    onClick = { onConfirm(if (requiresBaseBranch) selectedBaseBranch else null) },
                    enabled = canConfirm,
                    modifier = Modifier.weight(1.4f),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    if (inProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = androidx.compose.material3.LocalContentColor.current,
                        )
                    } else {
                        Text(
                            text =
                                if (isWorktreeProject) {
                                    stringResource(R.string.turn_worktree_handoff_to_local_action)
                                } else {
                                    stringResource(R.string.turn_worktree_handoff_to_worktree_action)
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ForkThreadActionSheet(
    visible: Boolean,
    projectPath: String?,
    inProgress: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    RemodexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.turn_fork_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.turn_fork_sheet_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            projectPath
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { path ->
                    Text(
                        text = stringResource(R.string.turn_fork_sheet_project_path, path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = LocalSheetClose.current,
                    enabled = !inProgress,
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = onConfirm,
                    enabled = !inProgress,
                ) {
                    if (inProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(stringResource(R.string.turn_fork_sheet_confirm))
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PlanDetailsActionSheet(
    visible: Boolean,
    message: CodexMessage?,
    canApplyPlan: Boolean,
    onDismiss: () -> Unit,
    onApplyPlan: () -> Unit,
) {
    if (!visible || message == null) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snapshot = PlanAccessorySnapshot.fromMessage(message)
    val lightChrome = isAgentLightChrome()
    val accent =
        if (lightChrome) {
            Color(0xFFE4C25F)
        } else {
            MaterialTheme.colorScheme.tertiary
        }
    val panelColor =
        if (lightChrome) {
            Color(0xFF5A4312).copy(alpha = 0.14f)
        } else {
            Color(0xFF5A4312).copy(alpha = 0.30f)
        }
    val contentColor =
        if (lightChrome) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    RemodexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = panelColor,
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.turn_plan_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                )
                snapshot.progressText?.let { progress ->
                    Text(
                        text = stringResource(R.string.turn_plan_sheet_progress, progress),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
                TurnRichMarkdownBody(
                    markdown = planMarkdownFromMessage(message),
                    contentColor = contentColor,
                    modifier = Modifier.fillMaxWidth(),
                    keyPrefix = "${message.id}-plan-sheet",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = LocalSheetClose.current) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    TextButton(
                        onClick = onApplyPlan,
                        enabled = canApplyPlan,
                    ) {
                        Text(stringResource(R.string.turn_plan_apply_action))
                    }
                }
            }
        }
    }
}
