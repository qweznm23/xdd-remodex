package com.remodex.mobile.ui.turn

import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.R
import com.remodex.mobile.core.config.FeatureFlags
import com.remodex.mobile.core.model.ThreadExecutionState
import com.remodex.mobile.core.model.CodexAccessMode

// The toolbar lives outside the NavHost. Only the matching mounted conversation supplies its controls.
internal class SessionEnvironmentHost(val threadId: String?) {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}
internal val LocalSessionEnvironment = compositionLocalOf<SessionEnvironmentHost?> { null }

@Composable
internal fun BindSessionEnvironment(threadId: String, content: @Composable () -> Unit) {
    val host = LocalSessionEnvironment.current ?: return
    if (threadId != host.threadId) return
    val latest = rememberUpdatedState(content)
    val section: @Composable () -> Unit = remember(host) { { latest.value() } }
    DisposableEffect(host, section) {
        host.content = section
        onDispose { if (host.content === section) host.content = null }
    }
}

@Composable
internal fun SessionEnvironmentSections(
    directory: String?,
    threadId: String,
    isWorktreeProject: Boolean,
    worktreeHandoffEnabled: Boolean,
    isHandingOffWorktree: Boolean,
    onWorktreeHandoff: () -> Unit,
    execution: ThreadExecutionState?,
    readError: String?,
    accessPickerEnabled: Boolean,
    accessError: String?,
    onSelectAccessMode: (CodexAccessMode) -> Unit,
    gitBranchPaneState: GitBranchPaneState,
    branchPickerEnabled: Boolean,
    isSwitchingGitBranch: Boolean,
    onRefreshGitBranches: () -> Unit,
    onCheckoutGitBranch: (String) -> Unit,
    onCreateGitBranch: (String) -> Unit,
    onOpenBranchSelector: () -> Unit,
) = key(threadId) {
    val uriHandler = LocalUriHandler.current
    val unknown = stringResource(R.string.session_info_unreported)
    val environments = execution?.environments
    val local = stringResource(if (isWorktreeProject) R.string.composer_runtime_worktree else R.string.composer_runtime_local)
    val runtime = when {
        environments == null -> if (isWorktreeProject) stringResource(R.string.composer_runtime_worktree) else unknown
        environments.isEmpty() -> stringResource(R.string.session_info_no_environment)
        else -> environments.joinToString(" · ") { id -> if (id == "local") local else id.ifBlank { unknown } }
    }
    val project = directory?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
    val location = project?.let { "$runtime · $it" } ?: runtime
    InfoDisclosure(stringResource(R.string.session_info_environment), location, LucideR.drawable.lucide_ic_laptop) {
        readError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val subdued = MaterialTheme.colorScheme.onSurfaceVariant
        if (!directory.isNullOrBlank()) {
            Text(stringResource(R.string.session_info_directory), style = MaterialTheme.typography.labelMedium, color = subdued)
            Text(directory, style = MaterialTheme.typography.bodyMedium)
        }
        var cloudError by remember { mutableStateOf<String?>(null) }
        TextButton(onClick = {
            try {
                uriHandler.openUri("https://chatgpt.com/codex")
                cloudError = null
            } catch (failure: IllegalArgumentException) {
                Log.e("RemodexEnvironment", "stage=open_cloud_url", failure)
                cloudError = failure.localizedMessage ?: failure.javaClass.name
            }
        }) {
            Text(stringResource(R.string.composer_runtime_menu_cloud))
        }
        cloudError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (FeatureFlags.gitIntegrationEnabled) {
            TurnGitBranchAccessory(state = gitBranchPaneState, branchPickerEnabled = branchPickerEnabled,
                isSwitchingBranch = isSwitchingGitBranch, onRefreshBranches = onRefreshGitBranches,
                onCheckoutBranch = onCheckoutGitBranch, onCreateBranch = onCreateGitBranch,
                onOpenBranchSelector = onOpenBranchSelector, onBranchPickerOpenChange = {}, compact = true)
            TextButton(onClick = onWorktreeHandoff, enabled = worktreeHandoffEnabled && !isHandingOffWorktree) {
                Text(stringResource(when {
                    isHandingOffWorktree -> R.string.composer_worktree_preparing
                    isWorktreeProject -> R.string.composer_runtime_menu_handoff_local
                    else -> R.string.composer_runtime_menu_worktree_handoff
                }))
            }
        }
    }
    val selectedAccessMode = execution?.preset
    val accessSummary = selectedAccessMode?.let { accessLabel(it) }
        ?: listOfNotNull(execution?.profile, execution?.sandbox, execution?.approval).joinToString(" · ").ifBlank { unknown }
    InfoDisclosure(stringResource(R.string.turn_runtime_access_label), accessSummary, LucideR.drawable.lucide_ic_shield) {
        accessError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Column(Modifier.selectableGroup()) {
            CodexAccessMode.entries.forEach { mode ->
                val selected = mode == selectedAccessMode
                Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = accessPickerEnabled,
                    role = Role.RadioButton, onClick = { if (!selected) onSelectAccessMode(mode) })
                    .heightIn(min = 48.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = selected, onClick = null, enabled = accessPickerEnabled)
                    Text(accessLabel(mode), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun accessLabel(mode: CodexAccessMode) =
    stringResource(if (mode == CodexAccessMode.onRequest) R.string.access_on_request else R.string.access_full)

@Composable
private fun InfoDisclosure(title: String, summary: String, @DrawableRes icon: Int, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.session_info_expanded else R.string.session_info_collapsed)
    val angle by animateFloatAsState(if (expanded) 180f else 0f, label = "Disclosure")
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
        .semantics { stateDescription = state }.heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon), null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(summary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Icon(painterResource(LucideR.drawable.lucide_ic_chevron_down), null, Modifier.size(18.dp).rotate(angle))
    }
    AnimatedVisibility(expanded, enter = expandVertically(expandFrom = Alignment.Top),
        exit = shrinkVertically(shrinkTowards = Alignment.Top)) {
        Column(Modifier.fillMaxWidth().padding(start = 40.dp, end = 16.dp, bottom = 8.dp), content = content)
    }
}
