package com.remodex.mobile.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.services.FileTransferFailure
import com.remodex.mobile.services.saveWorkspaceFile
import com.remodex.mobile.ui.turn.formatUsageCalendarTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant

internal class AuthFileActions(val chooseImport: (String, String) -> Unit, val chooseExport: (AuthProfile) -> Unit)

@Composable
internal fun rememberAuthFileActions(state: AuthProfilesState): AuthFileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Retain the exact selected revision across the system document picker.
    var importId by rememberSaveable { mutableStateOf<String?>(null) }
    var importRevision by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportRevision by rememberSaveable { mutableStateOf<String?>(null) }
    val chooseImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = importId; val revision = importRevision
        importId = null; importRevision = null
        if (uri != null) {
            if (id == null || revision == null) state.reportFileFailure(FileTransferFailure("auth_import_selection", "selection_lost", "Choose the authorization slot again"))
            else scope.launch {
                state.importFile(id, revision) {
                    context.contentResolver.openInputStream(uri)
                        ?: throw FileTransferFailure("auth_import_open", "open_failed", "Cannot open selected authorization file")
                }
            }
        }
    }
    val chooseExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportId; val revision = exportRevision
        exportId = null; exportRevision = null
        if (uri != null) {
            if (id == null || revision == null) state.reportFileFailure(FileTransferFailure("auth_export_selection", "selection_lost", "Choose the authorization file again"))
            else scope.launch {
                var saverOwnsCleanup = false
                try {
                    state.exportFile(AuthProfile(id, revision, "")) { transfer, info, cwd ->
                        saverOwnsCleanup = true
                        saveWorkspaceFile(context, transfer, info, cwd, uri) {}
                    }
                    if (!saverOwnsCleanup) state.failure?.let { removeEmptyAuthExport(context, uri, it) }
                } catch (cancelled: CancellationException) {
                    if (!saverOwnsCleanup) removeEmptyAuthExport(context, uri, cancelled)
                    throw cancelled
                }
            }
        }
    }
    return AuthFileActions(chooseImport = { id, revision ->
        importId = id; importRevision = revision
        chooseImport.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }, chooseExport = { profile ->
        exportId = profile.id; exportRevision = profile.revision
        chooseExport.launch(if (profile.id == "current") "auth-current.json" else "auth-${profile.id}.json")
    })
}

private suspend fun removeEmptyAuthExport(context: android.content.Context, uri: android.net.Uri, failure: Exception) {
    withContext(NonCancellable + Dispatchers.IO) {
        try {
            if (!android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                throw FileTransferFailure("auth_export_cleanup", "delete_failed", "Cannot remove the unwritten export destination")
            }
        } catch (cleanup: Exception) {
            failure.addSuppressed(cleanup)
            android.util.Log.e("RemodexAuthProfiles", "operation=export stage=cleanup; primary failure retained", failure)
        }
    }
}

@Composable
internal fun AuthProfileFileMenu(profile: AuthProfile, state: AuthProfilesState, files: AuthFileActions, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.auth_manage_file))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.auth_manage_check)) }, onClick = {
                open = false; scope.launch { state.checkFile(profile) }
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.auth_manage_export)) }, onClick = {
                open = false; files.chooseExport(profile)
            })
        }
    }
}

@Composable
internal fun AuthManagementPanel(state: AuthProfilesState, files: AuthFileActions, connected: Boolean, now: Instant) {
    val status = state.management
    val enabled = connected && state.busy == null && !state.switching
    var importing by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showFailures by rememberSaveable { mutableStateOf(false) }
    var showActiveFailure by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    fun date(value: Instant?) = value?.let { formatUsageCalendarTime(it, now, locale = locale) } ?: "—"
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.auth_manage_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { importing = true; selected = null }, enabled = enabled && status != null) {
                    Text(stringResource(R.string.auth_manage_import))
                }
            }
            Text(stringResource(R.string.auth_manage_schedule), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.auth_manage_last_check, date(status?.lastCheck)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.auth_manage_last_run, date(status?.lastRun)), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.auth_manage_next_check, date(status?.nextCheck)), style = MaterialTheme.typography.bodySmall)
            if (status?.lastCheck == null || status.lastCheck.plusSeconds(180).isBefore(now)) {
                Text(stringResource(R.string.auth_manage_stale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.auth_manage_active_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(when (status?.activeState) {
                "healthy" -> R.string.auth_manage_active_healthy
                "refreshed" -> R.string.auth_manage_active_refreshed
                "version_conflict" -> R.string.auth_manage_active_conflict
                "already_attempted_today" -> R.string.auth_manage_active_attempted
                "failed" -> R.string.auth_manage_active_failed
                else -> R.string.auth_manage_active_unknown
            }), style = MaterialTheme.typography.bodySmall)
            status?.activeDiagnostic?.let { diagnostic ->
                TextButton(onClick = { showActiveFailure = !showActiveFailure }) { Text(stringResource(R.string.questions_error_details)) }
                if (showActiveFailure) SelectionContainer {
                    Text(diagnostic, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                }
            }
            status?.current?.let { current ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.auth_manage_current_file), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    AuthProfileFileMenu(current, state, files, enabled)
                }
            }
            val failures = status?.entries.orEmpty().filter { it.result == "failed" }
            if (failures.isNotEmpty()) {
                TextButton(onClick = { showFailures = !showFailures }) {
                    Text(stringResource(R.string.auth_manage_auto_failed, failures.joinToString { it.profile.id }), color = MaterialTheme.colorScheme.error)
                }
                if (showFailures) SelectionContainer {
                    Text(failures.joinToString("\n") { "${it.profile.id}: ${it.diagnostic ?: it.result}" },
                        Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    state.notice?.let { notice ->
        Text(stringResource(when (notice) {
            "checked" -> R.string.auth_manage_checked
            "imported" -> R.string.auth_manage_imported
            else -> R.string.auth_manage_exported
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    if (state.notice == "checked") {
        val checked = (state.profiles + listOfNotNull(state.currentProfile, status?.current)).firstOrNull { it.id == state.checkedProfileId }
        Text(stringResource(when (checked?.accessTokenState) {
            "expired" -> R.string.auth_manage_token_expired
            "valid_by_timestamp" -> R.string.auth_manage_token_in_time
            else -> R.string.auth_manage_token_unknown
        }), style = MaterialTheme.typography.bodySmall)
    }
    if (state.busy?.substringBefore(':') in setOf("check", "import", "export")) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(when (state.busy?.substringBefore(':')) {
            "import" -> R.string.auth_manage_importing
            "export" -> R.string.auth_manage_exporting
            else -> R.string.auth_manage_checking
        }), style = MaterialTheme.typography.bodySmall)
    }
    if (importing) AlertDialog(onDismissRequest = { importing = false },
        title = { Text(stringResource(R.string.auth_manage_import)) },
        text = { Column {
            Text(stringResource(R.string.auth_manage_import_note))
            listOf("1", "2", "3").forEach { id ->
                val entry = status?.entries?.firstOrNull { it.profile.id == id }
                val selectable = entry?.active != true
                Row(Modifier.fillMaxWidth().clickable(enabled = selectable) { selected = id }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == id, enabled = selectable, onClick = { selected = id })
                    Text(stringResource(when {
                        entry?.active == true -> R.string.auth_manage_slot_active
                        entry != null -> R.string.auth_manage_slot_replace
                        else -> R.string.auth_manage_slot_new
                    }, id))
                }
            }
        } },
        confirmButton = { TextButton(enabled = enabled && selected != null && status?.entries?.none { it.profile.id == selected && it.active } == true,
            onClick = {
                val id = selected ?: return@TextButton
                val revision = status?.entries?.firstOrNull { it.profile.id == id }?.profile?.revision ?: "missing"
                importing = false; files.chooseImport(id, revision)
            }) { Text(stringResource(R.string.auth_manage_choose_file)) } },
        dismissButton = { TextButton(onClick = { importing = false }) { Text(stringResource(R.string.auth_profiles_cancel)) } })
}
