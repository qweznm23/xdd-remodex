package com.remodex.mobile.ui.settings

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
internal fun ObserveAuthProfiles(state: AuthProfilesState, connected: Boolean) {
    LaunchedEffect(state, connected) { if (connected && state.busy == null) state.refresh() }
    LaunchedEffect(state, connected, state.pendingOperationId) { state.observeSwitchConnection(connected) }
    val currentlyConnected by rememberUpdatedState(connected)
    LaunchedEffect(state, state.pendingOperationId, state.restartStatus) {
        while (state.switching) {
            delay(3_000)
            state.checkSwitchDeadline()
            if (state.switching && currentlyConnected && state.busy == null) state.pollRestart()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, lifecycle, connected, state.verifyingIdentity, state.switching) {
        if (!connected || !state.verifyingIdentity || state.switching) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            delay(15_000)
            while (state.verifyingIdentity && !state.switching) {
                if (state.busy == null) state.refresh()
                delay(60_000)
            }
        }
    }
    LaunchedEffect(state.failure) {
        state.failure?.let { android.util.Log.e("RemodexAuthProfiles", "operation=${state.failureOperation ?: "profile_management"}\n" + authProfileDiagnostic(it), it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthProfilesSection(state: AuthProfilesState, files: AuthFileActions, connected: Boolean,
    scope: kotlinx.coroutines.CoroutineScope, adding: Boolean, dismissAdd: () -> Unit,
    currentFiles: List<AuthProfile>, currentName: String, identity: String?, verification: String?,
    hasCurrent: Boolean, currentFile: AuthProfile?, plan: String?, openCurrent: (String) -> Unit, verifyCurrent: () -> Unit) {
    var selection by remember { mutableStateOf<AuthProfile?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(60_000) } }
    val locale = LocalConfiguration.current.locales[0]
    fun date(value: Instant?) = value?.let { formatUsageCalendarTime(it, now, locale = locale) } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val activeIds = currentFiles.map { it.id }.toSet()
        val rows = state.profiles.filterNot { it.id in activeIds }
        val currentWeekly = com.remodex.mobile.ui.turn.currentWeeklyQuota().takeUnless { state.verifyingIdentity }
        val recoveryFirst = state.verifyingIdentity || state.failedSwitch != null
        val verify = verifyCurrent.takeIf { verification != null && !state.switching }
        if (recoveryFirst) AuthRestartSection(state, connected, scope, verify)
        if (connected && !state.initialListSettled && !state.verifyingIdentity) {
            CurrentAccountOverview("—", null, null, null, currentWeekly, null, now) { openCurrent("current") }
            return@Column
        }
        if (currentFiles.isEmpty() && hasCurrent) {
            CurrentAccountOverview(currentName, identity, plan, currentFile, currentWeekly, verification, now) { openCurrent("current") }
        }
        currentFiles.forEach { profile -> key(profile.id) {
            CurrentAccountOverview(profileTitle(profile), profile.accountIdentity() ?: identity, plan,
                currentFile ?: profile, currentWeekly, verification, now) { openCurrent(profile.id) }
            AuthSwitchFeedback(state, profile.id, connected, scope)
        } }
        if (rows.isNotEmpty()) {
            Text(stringResource(R.string.account_saved_group), Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface) {
                Column {
                    rows.forEachIndexed { index, profile -> key(profile.id) {
                        SavedAccountOverview(profile, state.quota[profile.id], now, state.switching && state.switchProfileId == profile.id,
                            connected && state.busy == null && !state.switching, state.busy == "query:${profile.id}",
                            open = { detailId = profile.id }, query = { scope.launch { state.query(profile) } })
                        if (detailId != profile.id) AuthActionFeedback(state, "query:${profile.id}", connected, scope)
                        AuthSwitchFeedback(state, profile.id, connected, scope)
                        if (index < rows.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    } }
                }
            }
        }
        AuthActionFeedback(state, "list", connected, scope)
        if (state.available == false) Text(stringResource(R.string.auth_profiles_unavailable))
        else if (state.available == true && currentFiles.isEmpty() && !hasCurrent && rows.isEmpty()) Text(stringResource(R.string.auth_profiles_empty))
        if (currentFiles.isEmpty() && !hasCurrent) TextButton(onClick = { openCurrent("current") }) {
            Text(stringResource(R.string.settings_current_account))
        }
        if (!recoveryFirst) AuthRestartSection(state, connected, scope, verify)
        state.profiles.firstOrNull { it.id == detailId }?.let { profile -> key(profile.id) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            val result = state.quota[profile.id]
            var quotaDetails by rememberSaveable { mutableStateOf(false) }
            val enabled = connected && state.busy == null && !state.switching
            AccountDetailSheet({ detailId = null }) {
                AccountDetailHeader(stringResource(R.string.account_saved_detail), profileTitle(profile),
                    profile.accountIdentity(result?.email), result?.plan, profile.takeIf { state.management?.nextProfileId != null },
                    enabled) { file, name -> scope.launch { state.rename(file, name) } }
                AuthActionFeedback(state, "file:${profile.id}", connected, scope)
                AuthMaintenanceFailure(state.management?.entries?.firstOrNull { it.profile.id == profile.id })
                AccountDetailQuota(result?.weekly?.takeIf { it.resetsAt?.isAfter(now) != false },
                    result?.let { stringResource(R.string.account_last_query, date(it.at)) },
                    stringResource(if (result == null) R.string.account_quota_not_read else R.string.weekly_quota_unknown)) {
                    TextButton(onClick = { scope.launch { state.query(profile) } }, enabled = enabled) {
                        Text(stringResource(if (state.busy == "query:${profile.id}") R.string.auth_profiles_querying else R.string.auth_profiles_query))
                    }
                }
                AuthActionFeedback(state, "query:${profile.id}", connected, scope)
                FilledTonalButton(onClick = { selection = profile; detailId = null }, enabled = enabled &&
                    (state.runtimeControl.isEmpty() || state.runtimeControl["action"]?.stringValue == "restart")) {
                    Text(stringResource(R.string.auth_profiles_switch))
                }
                result?.let {
                    AccountDetailSection(stringResource(R.string.settings_section_usage), quotaDetails, { quotaDetails = !quotaDetails }) {
                        if (it.rows.isEmpty()) Text(stringResource(R.string.usage_rate_limits_empty))
                        it.rows.forEach { row -> UsageRateLimitRowDetail(row, now) }
                        AccountUsageDetailsContent(it.details)
                    }
                }
                AccountDetailSection(stringResource(R.string.auth_profiles_token_details), expanded, { expanded = !expanded }) {
                    if (state.supportsManagement) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.auth_manage_file), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        AuthProfileFileMenu(profile, state, files, enabled, scope)
                    }
                    Text(stringResource(R.string.auth_profiles_expiry, date(profile.expires)), style = MaterialTheme.typography.bodySmall,
                        color = if (profile.expires?.isBefore(now) == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(profile.path, style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.auth_profiles_issued, date(profile.issued)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.auth_profiles_refreshed, date(profile.refreshed)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.auth_profiles_modified, date(profile.modified)), style = MaterialTheme.typography.bodySmall)
                    if (state.supportsTokenRefresh) RefreshTokenAction(profile, state, connected, scope)
                    Text(stringResource(R.string.auth_profiles_refresh_token_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
        // During a restart the target may temporarily be absent from both lists.
        state.switchProfileId?.takeIf { id -> id !in activeIds && rows.none { it.id == id } &&
            (state.verifyingIdentity || state.management?.entries.orEmpty().none { it.active && it.profile.id == id }) }?.let {
            AuthSwitchFeedback(state, it, connected, scope)
        }
        if (state.supportsManagement) AuthManagementPanel(state, files, connected, now, adding, dismissAdd, scope)
        AuthActionFeedback(state, "removed", connected, scope)
    }
    selection?.let { profile ->
        RemodexAlertDialog(onDismissRequest = { selection = null }, title = { Text(stringResource(R.string.auth_library_switch, profileTitle(profile))) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileAccountLine(profile, state.quota[profile.id]?.email)
                Text(stringResource(R.string.auth_profiles_switch_body))
            } },
            confirmButton = { TextButton(onClick = { selection = null; scope.launch { state.switch(profile) } }) {
                Text(stringResource(R.string.auth_profiles_switch_confirm))
            } }, dismissButton = { TextButton(onClick = { selection = null }) { Text(stringResource(R.string.auth_profiles_cancel)) } })
    }
}

@Composable
internal fun RefreshTokenAction(profile: AuthProfile, state: AuthProfilesState, connected: Boolean,
    scope: kotlinx.coroutines.CoroutineScope, onRefreshed: suspend () -> Unit = {}) {
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
        AuthActionFeedback(state, "refreshToken:${profile.id}", connected, scope)
    }
}
