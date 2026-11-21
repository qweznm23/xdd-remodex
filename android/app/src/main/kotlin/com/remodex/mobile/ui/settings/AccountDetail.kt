package com.remodex.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexRateLimitWindow
import com.remodex.mobile.ui.shared.RemainingQuotaBar
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import com.remodex.mobile.ui.turn.weeklyQuotaCountdown
import com.remodex.mobile.ui.turn.weeklyQuotaLabel

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AccountDetailSheet(dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    RemodexModalBottomSheet(onDismissRequest = dismiss, sheetGesturesEnabled = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun AccountDetailHeader(kind: String, title: String, identity: String?, plan: String?,
    profile: AuthProfile?, enabled: Boolean, rename: (AuthProfile, String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(kind, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            if (!plan.isNullOrBlank()) Surface(shape = MaterialTheme.shapes.small,
                color = colors.surfaceContainerHigh, contentColor = colors.onSurface) {
                Text(plan, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            if (profile != null) {
                var editing by remember(profile.id) { mutableStateOf<AuthProfile?>(null) }
                val label = if (profile.displayName.isNullOrBlank()) R.string.account_set_alias else R.string.account_edit_alias
                TextButton(onClick = { editing = profile }, enabled = enabled) { Text(stringResource(label)) }
                editing?.let { original ->
                    ProfileNameDialog(label, original.displayName.orEmpty(), R.string.auth_library_save, { editing = null }) {
                        editing = null
                        rename(original, it)
                    }
                }
            }
        }
        identity?.removePrefix("$title · ")?.removeSuffix(" · $title")?.takeUnless { it.isBlank() || it == title }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun AccountDetailQuota(weekly: CodexRateLimitWindow?, checked: String?, unknown: String,
    actions: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh, contentColor = colors.onSurface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (weekly == null) unknown else weeklyQuotaLabel(weekly), style = MaterialTheme.typography.titleLarge)
            if (weekly != null) {
                RemainingQuotaBar(weekly.remainingPercent)
                weekly.resetsAt?.let { Text(weeklyQuotaCountdown(it), style = MaterialTheme.typography.bodySmall) }
            }
            checked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            actions()
        }
    }
}

@Composable
internal fun AccountDetailSection(title: String, expanded: Boolean, toggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val state = stringResource(if (expanded) R.string.account_section_expanded else R.string.account_section_collapsed)
    Column {
        HorizontalDivider(color = colors.outlineVariant)
        Surface(onClick = toggle, modifier = Modifier.fillMaxWidth().semantics { stateDescription = state },
            color = androidx.compose.ui.graphics.Color.Transparent, contentColor = colors.onSurface) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
            }
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
