package com.remodex.mobile.ui.settings

import com.remodex.mobile.ui.theme.RemodexDropdownMenu
import com.remodex.mobile.ui.theme.RemodexAlertDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.services.FileTransferFailure
import com.remodex.mobile.services.saveWorkspaceFile
import com.remodex.mobile.ui.turn.formatUsageCalendarTime
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AuthFileActions(val chooseImport: (String, String, String?) -> Unit, val chooseExport: (AuthProfile) -> Unit)

@Composable
internal fun rememberAuthFileActions(state: AuthProfilesState): AuthFileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Retain the exact selected revision across the system document picker.
    var importId by rememberSaveable { mutableStateOf<String?>(null) }
    var importRevision by rememberSaveable { mutableStateOf<String?>(null) }
    var importName by rememberSaveable { mutableStateOf<String?>(null) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportRevision by rememberSaveable { mutableStateOf<String?>(null) }
    val chooseImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = importId; val revision = importRevision
        val name = importName
        importId = null; importRevision = null; importName = null
        if (uri != null) {
            if (id == null || revision == null) state.reportFileFailure(FileTransferFailure("auth_import_selection", "selection_lost", "Choose the authorization slot again"), if (id != null && name == null) "import:$id" else "file_selection")
            else scope.launch {
                state.importFile(id, revision, name) {
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
            if (id == null || revision == null) state.reportFileFailure(FileTransferFailure("auth_export_selection", "selection_lost", "Choose the authorization file again"), id?.let { "export:$it" } ?: "file_selection")
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
    return AuthFileActions(chooseImport = { id, revision, name ->
        importId = id; importRevision = revision; importName = name
        try { chooseImport.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            importId = null; importRevision = null; importName = null
            state.reportFileFailure(FileTransferFailure("auth_import_picker", "picker_launch_failed",
                "Cannot open file picker", error), if (revision == "missing") "add:$id" else "import:$id")
        }
    }, chooseExport = { profile ->
        exportId = profile.id; exportRevision = profile.revision
        try { chooseExport.launch(if (profile.id == "current") "auth-current.json" else "auth-${profile.id}.json") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            exportId = null; exportRevision = null
            state.reportFileFailure(FileTransferFailure("auth_export_picker", "picker_launch_failed",
                "Cannot choose export destination", error), "export:${profile.id}")
        }
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
internal fun AuthProfileFileMenu(profile: AuthProfile, state: AuthProfilesState, files: AuthFileActions, enabled: Boolean, scope: kotlinx.coroutines.CoroutineScope, active: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<AuthProfile?>(null) }
    var removing by remember { mutableStateOf<AuthProfile?>(null) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.auth_manage_file))
        }
        RemodexDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (profile.id != "current" && state.management?.nextProfileId != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.auth_library_rename)) }, onClick = { open = false; edit = profile })
                if (!active) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.auth_library_replace)) }, onClick = {
                        open = false; files.chooseImport(profile.id, profile.revision, null)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.auth_library_remove)) }, onClick = { open = false; removing = profile })
                }
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.auth_manage_check)) }, onClick = {
                open = false; scope.launch { state.checkFile(profile) }
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.auth_manage_export)) }, onClick = {
                open = false; files.chooseExport(profile)
            })
        }
    }
    edit?.let { original ->
        ProfileNameDialog(R.string.auth_library_rename, original.displayName.orEmpty(), R.string.auth_library_save,
            { edit = null }) { name ->
                edit = null
                scope.launch { state.rename(original, name) }
            }
    }
    removing?.let { original ->
        RemodexAlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.auth_library_remove)) },
            text = { Text(stringResource(R.string.auth_library_remove_confirm, profileTitle(original))) },
            confirmButton = { TextButton(onClick = { removing = null; scope.launch { state.remove(original) } }) {
                Text(stringResource(R.string.auth_library_remove))
            } }, dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.auth_profiles_cancel)) } })
    }
}

@Composable
internal fun profileTitle(profile: AuthProfile) = profile.displayName?.takeIf { it.isNotBlank() }
    ?: profile.accountName?.takeIf { it.isNotBlank() }
    ?: profile.email?.takeIf { it.isNotBlank() }
    ?: stringResource(R.string.account_unnamed)

@Composable
internal fun ProfileAccountLine(profile: AuthProfile, queriedEmail: String? = null) {
    val identity = profile.accountIdentity(queriedEmail)
    Text(if (identity != null) stringResource(R.string.auth_profile_account_line, identity)
        else stringResource(R.string.auth_library_unknown),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ProfileNameDialog(title: Int, initial: String, confirm: Int, dismiss: () -> Unit, submit: (String) -> Unit) {
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    RemodexAlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(title)) },
        text = { OutlinedTextField(value = name, onValueChange = { if (it.codePointCount(0, it.length) <= 80) name = it },
            singleLine = true, label = { Text(stringResource(R.string.auth_library_name)) }) },
        confirmButton = { TextButton(onClick = { submit(name.trim()) }) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.auth_profiles_cancel)) } })
}

@Composable
internal fun AuthManagementPanel(state: AuthProfilesState, files: AuthFileActions, connected: Boolean, now: Instant, importing: Boolean, dismissImport: () -> Unit, scope: kotlinx.coroutines.CoroutineScope) {
    val status = state.management
    val enabled = connected && state.busy == null && !state.switching

    var selected by rememberSaveable(importing) { mutableStateOf<String?>(null) }
    var showActiveFailure by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    fun date(value: Instant?) = value?.let { formatUsageCalendarTime(it, now, locale = locale) } ?: "—"
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthActionFeedback(state, "add", enabled, scope)
            var showMaintenance by rememberSaveable { mutableStateOf(false) }
            Surface(onClick = { showMaintenance = !showMaintenance }, modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.auth_manage_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Icon(if (showMaintenance) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        stringResource(if (showMaintenance) R.string.auth_profiles_collapse else R.string.auth_manage_title))
                }
            }
            if (showMaintenance) {
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
                status?.activeReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                status?.activeDiagnostic?.let { diagnostic ->
                    TextButton(onClick = { showActiveFailure = !showActiveFailure }) { Text(stringResource(R.string.questions_error_details)) }
                    if (showActiveFailure) SelectionContainer {
                        Text(diagnostic, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    val newId = status?.nextProfileId
    if (importing && newId != null) ProfileNameDialog(R.string.auth_library_add, "", R.string.auth_manage_choose_file,
        { dismissImport() }) { name ->
            dismissImport()
            files.chooseImport(newId, "missing", name)
        }
    else if (importing) RemodexAlertDialog(onDismissRequest = { dismissImport() },
        title = { Text(stringResource(R.string.auth_manage_import)) },
        text = { Column {
            Text(stringResource(R.string.auth_manage_import_note))
            listOf("1", "2", "3").forEach { id ->
                val entry = status?.entries?.firstOrNull { it.profile.id == id }
                val selectable = entry?.active != true
                Row(Modifier.fillMaxWidth().selectable(selected = selected == id, enabled = selectable, role = Role.RadioButton) { selected = id }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == id, enabled = selectable, onClick = null)
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
                dismissImport(); files.chooseImport(id, revision, null)
            }) { Text(stringResource(R.string.auth_manage_choose_file)) } },
        dismissButton = { TextButton(onClick = { dismissImport() }) { Text(stringResource(R.string.auth_profiles_cancel)) } })
}

@Composable
internal fun AuthMaintenanceFailure(entry: AuthManagedEntry?) {
    if (entry?.result != "failed") return
    Text(stringResource(R.string.auth_recovery_maintenance), style = MaterialTheme.typography.bodySmall)
    var expanded by remember(entry) { mutableStateOf(false) }
    entry.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(R.string.auth_manage_auto_failed, entry.profile.id), color = MaterialTheme.colorScheme.error)
    }
    if (expanded) SelectionContainer {
        Text(entry.diagnostic ?: entry.result, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall)
    }
}


@Composable
internal fun SettingsFileRow(label: String, profile: AuthProfile, state: AuthProfilesState, files: AuthFileActions, enabled: Boolean, scope: kotlinx.coroutines.CoroutineScope) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        AuthProfileFileMenu(profile, state, files, enabled, scope, active = true)
    }
    AuthActionFeedback(state, "file:${profile.id}", enabled, scope)
}
