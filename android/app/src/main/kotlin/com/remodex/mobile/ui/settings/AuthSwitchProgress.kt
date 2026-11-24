package com.remodex.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import kotlinx.coroutines.delay

internal enum class SwitchPhase { Submit, AwaitRestart, Reconnect, Verify }

internal fun AuthProfilesState.progressPhase(connected: Boolean): SwitchPhase = when {
    !switching -> SwitchPhase.Submit
    !connected -> SwitchPhase.Reconnect
    sawSwitchDisconnect -> SwitchPhase.Verify
    else -> SwitchPhase.AwaitRestart
}

@Composable
internal fun AuthSwitchProgress(state: AuthProfilesState, connected: Boolean) {
    val started = state.switchStartedNanos
    val expected = state.restartExpectedNanos
    var now by remember(started, expected) { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(started, expected) {
        while (true) {
            now = System.nanoTime()
            delay(1000)
        }
    }
    val phase = state.progressPhase(connected)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val profile = state.switchProfileId
            Text(if (profile == null && state.restartHistory.firstOrNull()?.kind == "manual") stringResource(R.string.auth_restart_action)
                else if (profile == null) stringResource(R.string.auth_progress_title)
                else stringResource(R.string.auth_progress_target, profile),
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (started != null) {
                val seconds = (now - started).coerceAtLeast(0) / 1_000_000_000L
                Text(stringResource(R.string.auth_progress_elapsed, seconds), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (phase != SwitchPhase.Submit && state.restartHistory.isEmpty()) ProgressRow(stringResource(R.string.auth_progress_selected), done = true)
        if (phase == SwitchPhase.Verify) ProgressRow(stringResource(R.string.auth_progress_restored), done = true)
        val current = when (phase) {
            SwitchPhase.Submit -> stringResource(R.string.auth_progress_submit)
            SwitchPhase.Reconnect -> stringResource(R.string.auth_progress_reconnect)
            SwitchPhase.Verify -> stringResource(R.string.auth_progress_verify)
            SwitchPhase.AwaitRestart -> {
                val remaining = expected?.let { (it - now + 999_999_999L) / 1_000_000_000L }
                if (remaining != null && remaining > 0) stringResource(R.string.auth_progress_scheduled, remaining)
                else stringResource(R.string.auth_progress_waiting)
            }
        }
        val stage = state.restartHistory.firstOrNull { it.operationId == state.pendingOperationId }
            ?.detail?.objectValue?.get("stage")?.stringValue
        val stageLabel = when (stage) {
            "restart_preflight", "restart_selection" -> R.string.auth_stage_check
            "handover_stop", "direct_stop" -> R.string.auth_stage_handover
            "switch_write" -> R.string.auth_stage_write
            "daemon_restart", "service_restart", "daemon_start", "direct_start" -> R.string.auth_stage_restart
            "runtime_account_verification" -> R.string.auth_progress_verify
            else -> null
        }
        ProgressRow(if (stageLabel == null || !connected) current else stringResource(stageLabel), done = false)
    }
}

@Composable
private fun ProgressRow(label: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (done) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}
