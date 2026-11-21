package com.remodex.mobile.ui.turn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexRateLimitWindow
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.ui.LocalCodexRepository

@Composable
internal fun SessionInfoOverview(quota: CodexRateLimitWindow?, running: Boolean,
    onUsage: () -> Unit, onSettings: (() -> Unit)?) {
    val repository = LocalCodexRepository.current
    val thread by repository.activeThreadId.collectAsStateWithLifecycle()
    val snapshots by repository.contextWindowUsageByThread.collectAsStateWithLifecycle()
    val connection by repository.connectionState.collectAsStateWithLifecycle()
    val approval by repository.pendingApprovalRequest.collectAsStateWithLifecycle()
    val usage = snapshots[thread]?.takeIf { it.tokenLimit > 0 }
    val state = when {
        connection is ConnectionState.Offline || connection is ConnectionState.Error -> R.string.turn_usage_runtime_disconnected
        connection is ConnectionState.Connecting -> R.string.session_info_connecting
        thread != null && approval?.threadId == thread -> R.string.session_info_approval
        running -> R.string.turn_usage_runtime_running
        else -> null
    }
    if (state != null) {
        Text(stringResource(state), Modifier.padding(start = 16.dp, top = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top) {
        InfoMetric(stringResource(R.string.session_info_quota), quota?.remainingPercent,
            quota?.resetsAt?.let { weeklyQuotaCountdown(it, compact = true) }, onUsage, Modifier.weight(1f))
        Box(Modifier.weight(1f)) {
            InfoMetric(stringResource(R.string.session_info_context), usage?.percentUsed,
                usage?.let { stringResource(R.string.session_info_token_counts, it.tokensUsedFormatted, it.tokenLimitFormatted) },
                onUsage, Modifier.fillMaxWidth(), reserveSettingsSpace = onSettings != null)
            if (onSettings != null) IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(painterResource(LucideR.drawable.lucide_ic_settings), stringResource(R.string.nav_settings), Modifier.size(18.dp))
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun InfoMetric(label: String, percent: Int?, detail: String?, onClick: () -> Unit, modifier: Modifier,
    reserveSettingsSpace: Boolean = false) {
    Column(modifier.clickable(role = Role.Button, onClick = onClick).heightIn(min = 48.dp)
        .padding(horizontal = 8.dp, vertical = 8.dp)) {
        val heading = if (reserveSettingsSpace) Modifier.padding(end = 38.dp) else Modifier
        Text(label, modifier = heading, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(percent?.let { "$it%" } ?: "—", modifier = heading, style = MaterialTheme.typography.headlineSmall)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
