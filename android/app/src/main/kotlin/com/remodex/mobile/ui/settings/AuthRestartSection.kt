package com.remodex.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
internal fun AuthRestartSection(state: AuthProfilesState, connected: Boolean, scope: CoroutineScope) {
    if (!state.supportsManualRestart && state.restartHistory.isEmpty()) return
    var confirmation by rememberSaveable { mutableStateOf(false) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.supportsManualRestart) {
                OutlinedButton(onClick = { confirmation = true }, enabled = connected && state.busy == null && !state.switching) {
                    Text(stringResource(R.string.auth_restart_action))
                }
            }
            TextButton(onClick = { historyOpen = true }) { Text(stringResource(R.string.auth_restart_history)) }
        }
        AuthActionFeedback(state, "restart", connected, scope)
        if (state.switching || state.busy?.startsWith("switch:") == true) AuthSwitchProgress(state, connected)
        else state.restartHistory.firstOrNull()?.let { RestartSummary(it) }
    }
    if (confirmation) {
        RemodexAlertDialog(
            onDismissRequest = { confirmation = false },
            title = { Text(stringResource(R.string.auth_restart_action)) },
            text = { Text(stringResource(R.string.auth_restart_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmation = false
                    scope.launch { state.restartCodex() }
                }, enabled = connected && state.busy == null && !state.switching) {
                    Text(stringResource(R.string.auth_restart_action))
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = false }) { Text(stringResource(R.string.auth_profiles_cancel)) } },
        )
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
    val label = if (record.kind == "switch" && record.profile != null) stringResource(R.string.auth_progress_target, record.profile)
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
    (record.finishedAt ?: record.requestedAt)?.let { timestamp ->
        Text(formatUsageCalendarTime(timestamp, Instant.now(), locale = LocalConfiguration.current.locales[0]),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
