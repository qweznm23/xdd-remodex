package com.remodex.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.ui.turn.AccountUsageDetailsContent
import com.remodex.mobile.ui.turn.UsageRateLimitRowDetail
import com.remodex.mobile.ui.turn.formatUsageCalendarTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
internal fun AuthProfilesSection(state: AuthProfilesState, connected: Boolean, onSwitched: () -> Unit) {
    val scope = rememberCoroutineScope()
    val files = rememberAuthFileActions(state)
    var selection by remember { mutableStateOf<AuthProfile?>(null) }
    var showError by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(60_000) } }
    LaunchedEffect(connected) { if (connected && state.busy == null) state.refresh() }
    LaunchedEffect(connected, state.pendingOperationId) { state.observeSwitchConnection(connected) }
    val currentlyConnected by rememberUpdatedState(connected)
    LaunchedEffect(state.pendingOperationId, state.restartStatus) {
        while (state.switching) {
            delay(3_000)
            state.checkSwitchDeadline()
            if (state.switching && currentlyConnected && state.busy == null) state.refresh()
        }
    }
    LaunchedEffect(state.restartStatus) {
        if (state.restartStatus == "ready") onSwitched()
    }
    LaunchedEffect(state.failure) {
        state.failure?.let { android.util.Log.e("RemodexAuthProfiles", "operation=${state.failureOperation ?: "profile_management"}\n" + authProfileDiagnostic(it), it) }
    }
    val locale = LocalConfiguration.current.locales[0]
    fun date(value: Instant?) = value?.let { formatUsageCalendarTime(it, now, locale = locale) } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(if (state.supportsManagement) R.string.auth_manage_section_title else R.string.auth_profiles_title), Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { scope.launch { state.refresh() } }, enabled = connected && state.busy == null) {
                Text(stringResource(R.string.auth_profiles_refresh))
            }
        }
        Text(stringResource(R.string.auth_profiles_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.supportsManagement) AuthManagementPanel(state, files, connected, now)
        val showingSwitch = state.switching || state.busy?.startsWith("switch:") == true
        if (showingSwitch) {
            AuthSwitchProgress(state, connected)
        } else if (state.busy == "list") {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (!showingSwitch && state.restartStatus == "ready") {
            val profile = state.switchProfileId
            Text(if (profile == null) stringResource(R.string.auth_profiles_switch_ready)
                else stringResource(R.string.auth_progress_confirmed, profile), color = MaterialTheme.colorScheme.primary)
        }
        if (state.restartStatus in setOf("failed", "verification_failed", "superseded_by_newer_switch")) Text(stringResource(R.string.auth_profiles_restart_failed), color = MaterialTheme.colorScheme.error)
        if (state.failure != null) {
            if (!state.switching) Text(stringResource(R.string.auth_profiles_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { showError = !showError }) { Text(stringResource(R.string.questions_error_details)) }
            if (showError) SelectionContainer {
                Text(authProfileDiagnostic(state.failure!!), Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.available == false) Text(stringResource(R.string.auth_profiles_unavailable))
        else if (state.available == true && state.profiles.isEmpty()) Text(stringResource(R.string.auth_profiles_empty))
        state.profiles.forEach { profile -> key(profile.id) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            val result = state.quota[profile.id]
            ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.auth_profiles_number, profile.id), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (state.supportsManagement) AuthProfileFileMenu(profile, state, files, connected && state.busy == null && !state.switching)
                    }
                    result?.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    result?.plan?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    Text(stringResource(R.string.auth_profiles_expiry, date(profile.expires)), style = MaterialTheme.typography.bodySmall,
                        color = if (profile.expires?.isBefore(now) == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(if (expanded) R.string.auth_profiles_collapse else R.string.auth_profiles_token_details))
                    }
                    if (expanded) {
                        SelectionContainer { Text(profile.path, style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.auth_profiles_issued, date(profile.issued)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.auth_profiles_refreshed, date(profile.refreshed)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.auth_profiles_modified, date(profile.modified)), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.auth_profiles_refresh_token_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { scope.launch { state.query(profile) } }, enabled = connected && state.busy == null && !state.switching) {
                            Text(stringResource(if (state.busy == "query:${profile.id}") R.string.auth_profiles_querying else R.string.auth_profiles_query))
                        }
                        OutlinedButton(onClick = { selection = profile }, enabled = connected && state.busy == null && state.restartStatus != "restart_scheduled") {
                            Text(stringResource(R.string.auth_profiles_switch))
                        }
                    }
                    if (state.supportsTokenRefresh) {
                        RefreshTokenAction(profile, state, connected)
                    }
                    if (state.busy == "query:${profile.id}") LinearProgressIndicator(Modifier.fillMaxWidth())
                    result?.let {
                        HorizontalDivider()
                        Text(stringResource(R.string.auth_profiles_queried, date(it.at)), style = MaterialTheme.typography.labelSmall)
                        if (it.rows.isEmpty()) Text(stringResource(R.string.usage_rate_limits_empty))
                        it.rows.forEach { row -> UsageRateLimitRowDetail(row, now) }
                        AccountUsageDetailsContent(it.details)
                    }
                }
            }
        } }
    }
    selection?.let { profile ->
        AlertDialog(onDismissRequest = { selection = null }, title = { Text(stringResource(R.string.auth_profiles_switch_title, profile.id)) },
            text = { Text(stringResource(R.string.auth_profiles_switch_body)) },
            confirmButton = { TextButton(onClick = { selection = null; scope.launch { state.switch(profile) } }) {
                Text(stringResource(R.string.auth_profiles_switch_confirm))
            } }, dismissButton = { TextButton(onClick = { selection = null }) { Text(stringResource(R.string.auth_profiles_cancel)) } })
    }
}

@Composable
internal fun RefreshTokenAction(profile: AuthProfile, state: AuthProfilesState, connected: Boolean,
    onRefreshed: suspend () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val refreshing = state.busy == "refreshToken:${profile.id}"
    Column {
        OutlinedButton(onClick = {
            scope.launch {
                state.refreshToken(profile)
                if (state.lastRefreshed?.id == profile.id && state.failure == null) onRefreshed()
            }
        }, enabled = connected && state.busy == null && !state.switching) {
            Text(stringResource(if (refreshing) R.string.auth_token_refreshing else R.string.auth_token_refresh))
        }
        if (state.lastRefreshed == profile) {
            Text(stringResource(R.string.auth_token_updated), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}
