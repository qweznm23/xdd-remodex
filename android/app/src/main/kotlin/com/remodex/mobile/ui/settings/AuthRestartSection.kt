package com.remodex.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.ui.shared.RemainingQuotaBar
import com.remodex.mobile.ui.turn.weeklyQuotaLabel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.ui.theme.RemodexDropdownMenu
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import com.remodex.mobile.ui.theme.RemodexAlertDialog
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import com.remodex.mobile.ui.turn.formatUsageCalendarTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant

internal data class AuthRestartRecord(
    val operationId: String,
    val kind: String,
    val profile: String?,
    val status: String,
    val requestedAt: Instant?,
    val finishedAt: Instant?,
    val recovery: String?,
    val authorizationUnconfirmed: Boolean,
    val detail: JSONValue,
) {
    companion object {
        fun decode(value: JSONValue): AuthRestartRecord {
            val fields = requireNotNull(value.objectValue)
            val status = requireNotNull(fields["status"]?.stringValue)
            val recovery = fields["recovery"]?.objectValue?.get("status")?.stringValue
            val legacyFailure = fields["selectedOnDisk"]?.boolValue == true && status in setOf("failed", "verification_failed")
            fun time(key: String) = fields[key]?.stringValue?.let(Instant::parse)
            return AuthRestartRecord(
                requireNotNull(fields["operationId"]?.stringValue), requireNotNull(fields["kind"]?.stringValue),
                fields["profile"]?.stringValue, status, time("requestedAt"), time("finishedAt"), recovery,
                recovery == "verification_required" || legacyFailure, value,
            )
        }
    }
}

private val restartDetailsJson = Json { prettyPrint = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthRestartSection(state: AuthProfilesState, connected: Boolean, scope: CoroutineScope, verify: (() -> Unit)?) {
    val control = state.runtimeControl
    val failure = state.failedSwitch
    val pending = state.switching || state.busy?.startsWith("switch:") == true
    if (control.isEmpty() && !state.supportsManualRestart && state.restartHistory.isEmpty() &&
        failure == null && !pending && verify == null) return
    val action = control["action"]?.stringValue
    val mode = control["mode"]?.stringValue
    val canAdopt = control["canAdopt"]?.boolValue == true || action == "adopt"
    val canRestart = state.supportsManualRestart && action != "adopt"
    val actionText = if (action == "start") R.string.auth_start_action else R.string.auth_restart_action
    var approval by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var more by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    val enabled = connected && state.busy == null && !state.switching
    val latest = state.restartHistory.firstOrNull()
    val manualFailure = failure == null && latest?.status in setOf("failed", "verification_failed")
    val attention = failure != null || manualFailure
    val modeLabel = mode?.let {
        stringResource(when (it) {
            "daemon" -> R.string.auth_mode_daemon
            "ssh" -> R.string.auth_mode_ssh
            "direct" -> R.string.auth_mode_direct
            "systemd" -> R.string.auth_mode_service
            "stopped" -> R.string.auth_mode_stopped
            else -> R.string.auth_mode_unknown
        })
    }
    @Composable fun name(id: String): String {
        val entry = state.management?.entries?.firstOrNull { it.profile.id == id }?.profile
            ?: state.profiles.firstOrNull { it.id == id }
        return entry?.let { profileTitle(it) } ?: stringResource(R.string.auth_profile_number, id)
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = colors.surface,
        contentColor = colors.onSurface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = MaterialTheme.shapes.medium,
                    color = if (attention) colors.errorContainer else colors.surfaceContainerHigh,
                    contentColor = if (attention) colors.onErrorContainer else colors.onSurfaceVariant) {
                    Icon(painterResource(if (attention) LucideR.drawable.lucide_ic_triangle_alert else LucideR.drawable.lucide_ic_monitor),
                        null, Modifier.padding(8.dp).size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(when {
                        failure?.restartFailed == true || manualFailure -> R.string.auth_switch_restart_failed
                        failure != null -> R.string.auth_switch_incomplete
                        else -> R.string.auth_runtime_title
                    }), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    modeLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                }
                if (state.restartHistory.isNotEmpty() || canAdopt || verify != null) Box {
                    IconButton(onClick = { more = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.auth_runtime_more))
                    }
                    RemodexDropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        if (verify != null) DropdownMenuItem(text = { Text(stringResource(R.string.auth_recovery_verify)) },
                            enabled = enabled, onClick = { more = false; verify() })
                        if (state.restartHistory.isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.auth_restart_history)) },
                            onClick = { more = false; historyOpen = true })
                        if (canAdopt) DropdownMenuItem(text = { Text(stringResource(R.string.auth_adopt_action)) },
                            enabled = enabled, onClick = {
                                more = false
                                approval = state.listenerConfirmation
                                confirmation = "adopt"
                            })
                    }
                }
            }
            if (failure != null) {
                Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = MaterialTheme.shapes.medium,
                    color = colors.surfaceContainerLow, contentColor = colors.onSurface) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (connected && failure.runtime != null) stringResource(R.string.auth_switch_using, name(failure.runtime))
                            else stringResource(R.string.auth_switch_using_unknown), style = MaterialTheme.typography.titleSmall)
                        val weekly = state.liveQuota?.weekly?.takeIf { connected && failure.runtime != null &&
                            state.liveQuotaProfile == failure.runtime && it.resetsAt?.isAfter(Instant.now()) != false }
                        if (weekly != null) {
                            Text(weeklyQuotaLabel(weekly), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge)
                            RemainingQuotaBar(weekly.remainingPercent)
                        }
                        Text(stringResource(R.string.auth_switch_selected, name(failure.target)), style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant)
                    }
                }
                AuthActionFeedback(state, "runtime-quota", connected, scope)
            }
            if (pending) AuthSwitchProgress(state, connected)

            AuthActionFeedback(state, "restart", connected, scope)
            AuthActionFeedback(state, "restart-progress", connected, scope)
            if (canRestart) {
                FilledTonalButton(onClick = {
                    approval = state.listenerConfirmation
                    confirmation = action ?: "restart"
                }, enabled = enabled) { Text(stringResource(actionText)) }
            } else if (action == "adopt") {
                Text(stringResource(R.string.auth_direct_update_bridge), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
        }
    }
    confirmation?.let { choice ->
        val adopting = choice == "adopt"
        val title = stringResource(if (adopting) R.string.auth_adopt_action else actionText)
        val target = state.currentLibraryProfiles.firstOrNull()?.let { profileTitle(it) }
            ?: state.currentProfile?.let { profileTitle(it) } ?: "—"
        RemodexAlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(title) },
            text = { Text(when {
                adopting -> stringResource(R.string.auth_adopt_confirm, target)
                mode in setOf("direct", "ssh") -> stringResource(R.string.auth_direct_restart_confirm, target)
                else -> stringResource(R.string.auth_restart_confirm_body)
            }) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = null
                    scope.launch {
                        if (adopting) state.adoptCodex(requireNotNull(approval)) else state.restartCodex(approval)
                    }
                }, enabled = enabled && approval == state.listenerConfirmation &&
                    (if (adopting) canAdopt && approval != null else canRestart && choice == (action ?: "restart"))) { Text(title) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.auth_profiles_cancel)) } })
    }
    if (historyOpen) {
        RemodexModalBottomSheet(onDismissRequest = { historyOpen = false }, sheetGesturesEnabled = true) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.auth_restart_history), style = MaterialTheme.typography.titleLarge)
                if (state.restartHistory.isEmpty()) Text(stringResource(R.string.auth_restart_empty))
                state.restartHistory.forEachIndexed { index, record ->
                    if (index > 0) HorizontalDivider()
                    RestartSummary(record)
                    TextButton(onClick = { expandedId = record.operationId.takeUnless { it == expandedId } }) {
                        Text(stringResource(if (expandedId == record.operationId) R.string.auth_profiles_collapse else R.string.auth_restart_details))
                    }
                    if (expandedId == record.operationId) SelectionContainer {
                        Text(restartDetailsJson.encodeToString(JsonElement.serializer(), JSONValue.toJsonElement(record.detail)),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RestartSummary(record: AuthRestartRecord) {
    val label = if (record.kind == "adopt") stringResource(R.string.auth_adopt_action)
        else if (record.kind == "switch" && record.profile != null) stringResource(R.string.auth_progress_target, record.profile)
        else stringResource(R.string.auth_restart_action)
    val status = when (record.status) {
        "ready" -> R.string.auth_restart_ready
        "recovered" -> R.string.auth_restart_recovered
        "restart_scheduled", "running" -> R.string.auth_restart_pending
        "superseded_by_newer_switch" -> R.string.auth_restart_superseded
        "restarted" -> R.string.auth_restart_unverified
        else -> when (record.recovery) {
            "rolled_back", "original_retained" -> R.string.auth_restart_rolled_back
            "verification_required" -> R.string.auth_restart_unverified
            else -> R.string.auth_restart_failed
        }
    }
    Text(stringResource(R.string.auth_restart_summary, label, stringResource(status)), style = MaterialTheme.typography.bodyMedium,
        color = if (record.status in setOf("failed", "verification_failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    val fields = record.detail.objectValue.orEmpty()
    fields["failure"]?.objectValue?.let { error ->
        val cause = error["stderr"]?.stringValue?.trim()?.takeIf { it.isNotEmpty() } ?: error["message"]?.stringValue
        if (!cause.isNullOrBlank()) Text(cause, maxLines = 3, style = MaterialTheme.typography.bodySmall)
    }
    (record.finishedAt ?: record.requestedAt)?.let { timestamp ->
        Text(formatUsageCalendarTime(timestamp, Instant.now(), locale = LocalConfiguration.current.locales[0]),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
