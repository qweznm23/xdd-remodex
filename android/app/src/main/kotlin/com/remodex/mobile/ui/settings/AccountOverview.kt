package com.remodex.mobile.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexRateLimitWindow
import com.remodex.mobile.ui.shared.RemainingQuotaBar
import com.remodex.mobile.ui.turn.formatUsageCalendarTime
import com.remodex.mobile.ui.turn.weeklyQuotaCountdown
import com.remodex.mobile.ui.turn.weeklyQuotaLabel
import java.time.Instant

@Composable
private fun AccountIdentity(title: String, identity: String?, plan: String?, prominent: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
        if (!plan.isNullOrBlank()) Surface(shape = MaterialTheme.shapes.small, color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface) {
            Text(plan, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
        }
        Icon(painterResource(LucideR.drawable.lucide_ic_chevron_right), null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
    identity?.removePrefix("$title · ")?.removeSuffix(" · $title")?.takeUnless { it.isBlank() || it == title }?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun TokenExpiry(expires: Instant?, now: Instant) {
    if (expires == null) return
    val time = formatUsageCalendarTime(expires, now, locale = LocalConfiguration.current.locales[0])
    Text(stringResource(if (expires.isAfter(now)) R.string.account_access_until else R.string.account_access_ended, time),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun CurrentAccountOverview(title: String, identity: String?, plan: String?, currentFile: AuthProfile?,
    weekly: CodexRateLimitWindow?, verification: String?, now: Instant, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        color = colors.surface, contentColor = colors.onSurface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(if (verification == null) LucideR.drawable.lucide_ic_check else LucideR.drawable.lucide_ic_users),
                    null, Modifier.size(16.dp))
                Text(verification ?: stringResource(R.string.account_current_heading), style = MaterialTheme.typography.labelLarge)
            }
            AccountIdentity(title, identity, plan, prominent = true)
            Spacer(Modifier.height(2.dp))
            if (weekly != null) {
                Text(weeklyQuotaLabel(weekly), style = MaterialTheme.typography.titleMedium)
                RemainingQuotaBar(weekly.remainingPercent)
                weekly.resetsAt?.let { Text(weeklyQuotaCountdown(it), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
            TokenExpiry(currentFile?.expires, now)
        }
    }
}

@Composable
internal fun SavedAccountOverview(profile: AuthProfile, result: ProfileQuota?, now: Instant, pending: Boolean,
    enabled: Boolean, querying: Boolean, open: () -> Unit, query: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val weekly = result?.weekly?.takeIf { it.resetsAt?.isAfter(now) != false }
    Column {
        Column(Modifier.fillMaxWidth().clickable(enabled = !pending, role = Role.Button, onClick = open)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AccountIdentity(profileTitle(profile), profile.accountIdentity(result?.email), result?.plan, prominent = false)
            TokenExpiry(profile.expires, now)
        }
        if (!pending) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when {
                    weekly != null -> weeklyQuotaLabel(weekly)
                    result == null -> stringResource(R.string.account_quota_not_read)
                    else -> weeklyQuotaLabel(null)
                }, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                if (result != null) Text(stringResource(R.string.account_last_query,
                    formatUsageCalendarTime(result.at, now, locale = LocalConfiguration.current.locales[0])),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            TextButton(onClick = query, enabled = enabled) {
                Text(stringResource(if (querying) R.string.auth_profiles_querying else R.string.account_check_quota))
            }
        }
    }
}
