package com.remodex.mobile.ui.agent

import com.remodex.mobile.ui.turn.ConversationQuestionAction
import com.remodex.mobile.ui.turn.WeeklyQuotaMoreMenu
import com.remodex.mobile.ui.theme.conversationColors
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.annotation.DrawableRes
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.core.model.GitDiffTotals
import com.remodex.mobile.core.model.TurnGitActionKind
import com.remodex.mobile.ui.theme.RemodexGitAddition
import com.remodex.mobile.ui.theme.RemodexDropdownMenu
import com.remodex.mobile.ui.sidebar.RemodexCircleIconButton
import com.remodex.mobile.ui.sidebar.rememberSidebarColorPalette

/** Truncate long filesystem paths for the header subtitle (middle ellipsis). */
fun truncatePathMiddle(
    path: String,
    maxLen: Int = 44,
): String {
    if (path.length <= maxLen) return path
    val ellipsis = "…"
    val inner = maxLen - ellipsis.length
    val head = inner / 2
    val tail = inner - head
    return path.take(head) + ellipsis + path.takeLast(tail)
}

/**
 * Main turn toolbar: title, repo path, running pill, git actions menu (incl. repository diff),
 * session information and handoff actions.
 * SwiftUI reference: [TurnToolbarContent](CodexMobile/CodexMobile/Views/Turn/TurnToolbarContent.swift).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationHeader(
    title: String,
    threadId: String?,
    pathSubtitle: String?,
    onPathClick: (() -> Unit)?,
    repoDiffTotals: GitDiffTotals?,
    isLoadingRepoDiff: Boolean,
    canViewRepoDiff: Boolean,
    showGitActions: Boolean,
    onGitAction: ((TurnGitActionKind) -> Unit)?,
    gitActionsBusy: Boolean,
    showsDiscardRuntimeRecovery: Boolean,
    isGitActionEnabled: Boolean,
    showDesktopHandoff: Boolean,
    handingOffToDesktop: Boolean,
    showWorktreeHandoff: Boolean,
    handingOffWorktree: Boolean,
    isWorktreeProject: Boolean,
    running: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onContinueDesktop: () -> Unit,
    onWorktreeHandoff: () -> Unit,
    onRefreshConversation: (() -> Unit)? = null,
    environment: (@Composable () -> Unit)? = null,
    refreshingConversation: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    var gitMenuExpanded by remember { mutableStateOf(false) }
    val gitMenuCd = stringResource(R.string.cd_git_actions_menu)
    val reading = MaterialTheme.colorScheme.conversationColors().reading
    TopAppBar(
        modifier = modifier,
        expandedHeight = 56.dp,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = reading, scrolledContainerColor = reading),
        navigationIcon = { HeaderMenuButton(onOpenDrawer) },
        title = {
            val pathAction = if (pathSubtitle != null && onPathClick != null) {
                Modifier.clickable(role = Role.Button, onClick = onPathClick)
            } else Modifier
            Column(
                modifier = Modifier.fillMaxWidth().then(pathAction).heightIn(min = 48.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (pathSubtitle != null) {
                    Text(pathSubtitle, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
        },
        actions = {
            ConversationQuestionAction(threadId) {
                if (running) {
                    val thinking = stringResource(R.string.turn_top_bar_thinking)
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 6.dp).size(16.dp).semantics { contentDescription = thinking },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        strokeWidth = 2.dp,
                    )
                }
            }
            HeaderActions(
                repoDiffTotals = repoDiffTotals,
                isLoadingRepoDiff = isLoadingRepoDiff,
                canViewRepoDiff = canViewRepoDiff,
                showGitActions = showGitActions,
                onGitAction = onGitAction,
                gitActionsBusy = gitActionsBusy,
                showsDiscardRuntimeRecovery = showsDiscardRuntimeRecovery,
                isGitActionEnabled = isGitActionEnabled,
                gitMenuCd = gitMenuCd,
                overflowExpanded = overflowExpanded,
                onSetOverflowExpanded = { overflowExpanded = it },
                onOpenSettings = onOpenSettings,
                gitMenuExpanded = gitMenuExpanded,
                onSetGitMenuExpanded = { gitMenuExpanded = it },
                showDesktopHandoff = showDesktopHandoff,
                handingOffToDesktop = handingOffToDesktop,
                showWorktreeHandoff = showWorktreeHandoff,
                handingOffWorktree = handingOffWorktree,
                isWorktreeProject = isWorktreeProject,
                running = running,
                onContinueDesktop = onContinueDesktop,
                onWorktreeHandoff = onWorktreeHandoff,
                onRefreshConversation = onRefreshConversation,
                environment = environment,
                refreshingConversation = refreshingConversation,
            )
        },
    )
}

@Composable
private fun HeaderMenuButton(onOpenDrawer: () -> Unit) {
    RemodexCircleIconButton(
        onClick = onOpenDrawer,
        contentDescription = stringResource(R.string.cd_open_navigation_drawer),
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_menu),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun HeaderActions(
    repoDiffTotals: GitDiffTotals?,
    isLoadingRepoDiff: Boolean,
    canViewRepoDiff: Boolean,
    showGitActions: Boolean,
    onGitAction: ((TurnGitActionKind) -> Unit)?,
    gitActionsBusy: Boolean,
    showsDiscardRuntimeRecovery: Boolean,
    isGitActionEnabled: Boolean,
    gitMenuCd: String,
    overflowExpanded: Boolean,
    onSetOverflowExpanded: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    gitMenuExpanded: Boolean,
    onSetGitMenuExpanded: (Boolean) -> Unit,
    showDesktopHandoff: Boolean,
    handingOffToDesktop: Boolean,
    showWorktreeHandoff: Boolean,
    handingOffWorktree: Boolean,
    isWorktreeProject: Boolean,
    running: Boolean,
    onContinueDesktop: () -> Unit,
    onWorktreeHandoff: () -> Unit,
    onRefreshConversation: (() -> Unit)?,
    environment: (@Composable () -> Unit)?,
    refreshingConversation: Boolean,
) {
    val colors = rememberSidebarColorPalette()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showGitActions && onGitAction != null) {
            Box {
                RemodexCircleIconButton(
                    onClick = { onSetGitMenuExpanded(true) },
                    enabled = (canViewRepoDiff || isGitActionEnabled) && !gitActionsBusy,
                    contentDescription = gitMenuCd,
                ) {
                    val tint = androidx.compose.material3.LocalContentColor.current
                    if (gitActionsBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = tint,
                        )
                    } else {
                        Box(modifier = Modifier.size(20.dp)) {
                            GitNodeConnectorIcon(
                                tint = tint,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                GitActionsDropdown(
                    expanded = gitMenuExpanded,
                    onDismissRequest = { onSetGitMenuExpanded(false) },
                    repoDiffTotals = repoDiffTotals,
                    isLoadingRepoDiff = isLoadingRepoDiff,
                    canViewRepoDiff = canViewRepoDiff,
                    showsDiscardRuntimeRecovery = showsDiscardRuntimeRecovery,
                    isGitActionEnabled = isGitActionEnabled,
                    onSelect = {
                        onSetGitMenuExpanded(false)
                        onGitAction(it)
                    },
                )
            }
        }

        WeeklyQuotaMoreMenu(
            expanded = overflowExpanded,
            setExpanded = onSetOverflowExpanded,
            description = stringResource(
                if (refreshingConversation) R.string.conversation_refreshing else R.string.session_info_title,
            ),
            onOpenSettings = onOpenSettings,
            sessionPanel = true,
            running = running,
            environment = environment,
        ) {
            if (onRefreshConversation != null || showDesktopHandoff) {
                val actions: @Composable (Modifier) -> Unit = { item ->
                    if (onRefreshConversation != null) {
                        SessionFooterAction(
                            stringResource(if (refreshingConversation) R.string.conversation_refreshing else R.string.session_info_refresh),
                            LucideR.drawable.lucide_ic_refresh_cw, item, enabled = !refreshingConversation,
                        ) { onSetOverflowExpanded(false); onRefreshConversation() }
                    }
                    if (showDesktopHandoff) {
                        SessionFooterAction(
                            stringResource(if (handingOffToDesktop) R.string.turn_open_desktop_opening else R.string.session_info_continue_desktop),
                            LucideR.drawable.lucide_ic_laptop, item, enabled = !handingOffToDesktop,
                        ) { onSetOverflowExpanded(false); onContinueDesktop() }
                    }
                }
                if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.35f) {
                    Column { actions(Modifier.fillMaxWidth()) }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) { actions(Modifier.weight(1f)) }
                }
            }
            if (showWorktreeHandoff) {
                DropdownMenuItem(
                    leadingIcon = { Icon(painterResource(LucideR.drawable.lucide_ic_git_branch), null, Modifier.size(20.dp)) },
                    text = {
                        Text(
                            if (handingOffWorktree) {
                                stringResource(R.string.composer_worktree_preparing)
                            } else if (isWorktreeProject) {
                                stringResource(R.string.composer_runtime_menu_handoff_local)
                            } else {
                                stringResource(R.string.composer_runtime_menu_worktree_handoff)
                            },
                        )
                    },
                    onClick = {
                        onSetOverflowExpanded(false)
                        if (!handingOffWorktree) onWorktreeHandoff()
                    },
                    enabled = !handingOffWorktree,
                )
            }
        }
    }
}

@Composable
private fun SessionFooterAction(label: String, @DrawableRes icon: Int, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)) {
        Icon(painterResource(icon), null, Modifier.size(16.dp))
        Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun GitActionsDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    repoDiffTotals: GitDiffTotals?,
    isLoadingRepoDiff: Boolean,
    canViewRepoDiff: Boolean,
    showsDiscardRuntimeRecovery: Boolean,
    isGitActionEnabled: Boolean,
    onSelect: (TurnGitActionKind) -> Unit,
) {
    RemodexDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        panelWidth = 272.dp,
    ) {
        GitActionMenuSectionHeader(stringResource(R.string.git_action_section_changes))
        GitActionChangesMenuItem(
            totals = repoDiffTotals,
            isLoading = isLoadingRepoDiff,
            enabled = canViewRepoDiff,
            onClick = { onSelect(TurnGitActionKind.viewRepositoryDiff) },
        )
        HorizontalDivider()
        GitActionMenuSectionHeader(stringResource(R.string.git_action_section_write))
        GitActionMenuItem(
            label = TurnGitActionKind.commit.title,
            useGitNodeIcon = true,
            onClick = { onSelect(TurnGitActionKind.commit) },
            enabled = isGitActionEnabled,
        )
        GitActionMenuItem(
            label = TurnGitActionKind.push.title,
            iconRes = LucideR.drawable.lucide_ic_arrow_up,
            onClick = { onSelect(TurnGitActionKind.push) },
            enabled = isGitActionEnabled,
        )
        GitActionMenuItem(
            label = TurnGitActionKind.commitAndPush.title,
            iconRes = LucideR.drawable.lucide_ic_cloud,
            onClick = { onSelect(TurnGitActionKind.commitAndPush) },
            enabled = isGitActionEnabled,
        )
        GitActionMenuItem(
            label = stringResource(R.string.git_action_commit_push_pr),
            iconRes = LucideR.drawable.lucide_ic_github,
            onClick = { onSelect(TurnGitActionKind.commitPushAndPullRequest) },
            enabled = isGitActionEnabled,
        )
        GitActionMenuItem(
            label = TurnGitActionKind.createPR.title,
            iconRes = LucideR.drawable.lucide_ic_github,
            onClick = { onSelect(TurnGitActionKind.createPR) },
            enabled = isGitActionEnabled,
        )
        HorizontalDivider()
        GitActionMenuSectionHeader(stringResource(R.string.git_action_section_update))
        GitActionMenuItem(
            label = TurnGitActionKind.syncNow.title,
            iconRes = LucideR.drawable.lucide_ic_refresh_cw,
            onClick = { onSelect(TurnGitActionKind.syncNow) },
            enabled = isGitActionEnabled,
        )
        if (showsDiscardRuntimeRecovery) {
            HorizontalDivider()
            GitActionMenuSectionHeader(stringResource(R.string.git_action_section_recovery))
            GitActionMenuItem(
                label = TurnGitActionKind.discardRuntimeChangesAndSync.title,
                iconRes = LucideR.drawable.lucide_ic_trash_2,
                onClick = { onSelect(TurnGitActionKind.discardRuntimeChangesAndSync) },
                enabled = isGitActionEnabled,
                labelColor = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun GitActionMenuSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun GitActionMenuItem(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    @DrawableRes iconRes: Int? = null,
    useGitNodeIcon: Boolean = false,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = if (enabled) labelColor else labelColor.copy(alpha = 0.42f),
            )
        },
        leadingIcon = {
            GitActionMenuLeadingIcon(
                iconRes = iconRes,
                useGitNodeIcon = useGitNodeIcon,
                enabled = enabled,
            )
        },
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun GitActionMenuLeadingIcon(
    @DrawableRes iconRes: Int?,
    useGitNodeIcon: Boolean,
    enabled: Boolean,
) {
    val tint =
        MaterialTheme.colorScheme.onSurface.copy(
            alpha = if (enabled) 0.92f else 0.42f,
        )
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when {
            useGitNodeIcon ->
                GitNodeConnectorIcon(
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            iconRes != null ->
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = tint,
                )
        }
    }
}

@Composable
private fun GitActionChangesMenuItem(
    totals: GitDiffTotals?,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else if (totals != null) {
                    GitActionDiffTotalsRow(totals = totals, enabled = enabled)
                }
            }
        },
        leadingIcon = {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_file_diff),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint =
                    MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (enabled) 0.92f else 0.42f,
                    ),
            )
        },
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
private fun GitActionDiffTotalsRow(
    totals: GitDiffTotals,
    enabled: Boolean,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val labelAlpha = if (enabled) 1f else 0.42f
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "+${totals.additions}",
            style = MaterialTheme.typography.labelLarge,
            color = RemodexGitAddition.copy(alpha = labelAlpha),
        )
        Text(
            text = "-${totals.deletions}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error.copy(alpha = labelAlpha),
        )
        if (totals.binaryFiles > 0) {
            Text(
                text = "B${totals.binaryFiles}",
                style = MaterialTheme.typography.labelMedium,
                color = muted.copy(alpha = labelAlpha),
            )
        }
    }
}
