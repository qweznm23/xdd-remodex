package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexRateLimitWindow
import com.remodex.mobile.core.model.accountWeeklyQuota
import com.remodex.mobile.ui.LocalCodexRepository
import com.remodex.mobile.ui.sidebar.RemodexCircleIconButton
import com.remodex.mobile.ui.theme.RemodexAnchoredMenu
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
internal fun currentWeeklyQuota(): CodexRateLimitWindow? {
    val buckets by LocalCodexRepository.current.rateLimitBuckets.collectAsStateWithLifecycle()
    val window = remember(buckets) { accountWeeklyQuota(buckets) }
    val reset = window?.resetsAt ?: return window
    return key(reset) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        var expired by remember { mutableStateOf(!Instant.now().isBefore(reset)) }
        LaunchedEffect(reset, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                delay(Duration.between(Instant.now(), reset).toMillis().coerceAtLeast(0))
                expired = true
            }
        }
        window.takeUnless { expired }
    }
}

@Composable
internal fun weeklyQuotaLabel(window: CodexRateLimitWindow?): String =
    if (window == null) stringResource(R.string.weekly_quota_unknown)
    else stringResource(R.string.weekly_quota_summary, window.remainingPercent)

@Composable
internal fun weeklyQuotaCountdown(reset: Instant, compact: Boolean = false): String {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(Instant.now(), lifecycle, reset) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = Instant.now()
                delay(60_000L)
            }
        }
    }
    val minutes = remainingQuotaResetMinutes(reset, now)
    if (compact && minutes >= 1440) {
        val days = minutes / 1440
        val hours = minutes % 1440 / 60
        return if (hours == 0L) stringResource(R.string.session_info_reset_days, days)
        else stringResource(R.string.session_info_reset_days_hours, days, hours)
    }
    return quotaCountdownText(minutes)
}

@Composable
internal fun quotaCountdownText(minutes: Long?): String = when {
    minutes == null -> "—"
    minutes == 0L -> stringResource(R.string.usage_reset_due)
    minutes >= 1440 -> stringResource(R.string.usage_reset_in_days, minutes / 1440, minutes % 1440 / 60)
    minutes >= 60 -> stringResource(R.string.usage_reset_in_hours, minutes / 60, minutes % 60)
    else -> stringResource(R.string.usage_reset_in_minutes, minutes)
}

@Composable
internal fun WeeklyQuotaMoreMenu(
    description: String,
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    sessionPanel: Boolean = false,
    running: Boolean = false,
    environment: (@Composable () -> Unit)? = null,
    items: @Composable () -> Unit,
) {
    val repository = LocalCodexRepository.current
    val window = currentWeeklyQuota()
    val label = weeklyQuotaLabel(window)
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    var usageOpen by remember { mutableStateOf(false) }
    var infoDeviceId by remember { mutableStateOf<String?>(null) }
    val badge: @Composable () -> Unit = {
        Box(Modifier.size(36.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            if (window == null) {
                Box(Modifier.size(36.dp).border(BorderStroke(2.dp, track), CircleShape))
            } else {
                CircularProgressIndicator(
                    progress = { window.remainingPercent / 100f }, modifier = Modifier.size(36.dp),
                    color = color, trackColor = track, strokeWidth = 2.dp, gapSize = 0.dp,
                )
            }
            Text(window?.remainingPercent?.toString() ?: "—", maxLines = 1, softWrap = false,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = (12f / androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)).sp,
                    lineHeight = (16f / androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)).sp,
                ))
        }
    }
    // A detail sheet replaces the popup; ordinary dismissals keep the return-to-anchor transition.
    val transition = if (usageOpen || infoDeviceId != null) null else updateTransition(expanded, label = "Quota menu")
    val atSource = transition == null || (!expanded && !transition.currentState && !transition.isRunning)
    Box {
        RemodexCircleIconButton(
            onClick = { setExpanded(true) },
            contentDescription = "$description · $label", size = 48.dp, icon = { if (atSource) badge() },
        )
        val width = if (sessionPanel) {
            val available = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 24.dp
            val preferred = 286.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
            available.coerceIn(48.dp, preferred)
        } else null
        if (transition != null) RemodexAnchoredMenu(transition, onDismiss = { setExpanded(false) }, panelWidth = width, badge = badge) {
            val openUsage = {
                setExpanded(false)
                usageOpen = true
            }
            if (sessionPanel) {
                SessionInfoOverview(window, running, openUsage, onOpenSettings?.let { open ->
                    {
                        setExpanded(false)
                        open()
                    }
                })
                environment?.invoke()
                val deviceId by repository.relayMacDeviceId.collectAsStateWithLifecycle()
                DropdownMenuItem(text = { Text(stringResource(R.string.host_system_info)) }, enabled = deviceId != null,
                    onClick = { infoDeviceId = deviceId; setExpanded(false) })
            } else {
                DropdownMenuItem(text = {
                    Column {
                        Text(label)
                        window?.resetsAt?.let { Text(weeklyQuotaCountdown(it), style = MaterialTheme.typography.bodySmall) }
                    }
                }, onClick = openUsage)
            }
            HorizontalDivider()
            items()
        }
    }
    if (usageOpen) {
        val thread by repository.activeThreadId.collectAsStateWithLifecycle()
        ThreadUsageStatusBottomSheet(true, { usageOpen = false }, thread.orEmpty(), repository)
    }
    infoDeviceId?.let { device ->
        val thread by repository.activeThreadId.collectAsStateWithLifecycle()
        val threads by repository.threads.collectAsStateWithLifecycle()
        val cwd = threads.firstOrNull { it.id == thread }?.cwd
        com.remodex.mobile.ui.mydevices.HostSystemInfoSheet(repository, device, cwd) { infoDeviceId = null }
    }
}
