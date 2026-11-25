package com.remodex.mobile.ui.settings

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import com.remodex.mobile.update.UpdateRetry
import com.remodex.mobile.update.AppUpdateFailure
import com.remodex.mobile.update.updateHttpUrl
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import com.remodex.mobile.update.AppUpdateState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.AppContainer
import com.remodex.mobile.BuildConfig
import com.remodex.mobile.R
import com.remodex.mobile.update.UpdatePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Owned by the activity so closing the dialog does not interrupt a requested installation. */
@Composable
internal fun AppUpdateInstallerHost() {
    val context = LocalContext.current
    val updater = AppContainer.appUpdates
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed by lifecycle.currentStateAsState()
    val requestFlow = remember(updater) {
        updater.state.map { if (it.launchInstaller) it.update else null }.distinctUntilChanged()
    }
    val request by requestFlow.collectAsStateWithLifecycle(initialValue = null)
    var permissionFor by rememberSaveable { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val expected = permissionFor
        permissionFor = null
        val update = updater.state.value.update
        if (update != null && expected == update.sha256) {
            if (context.packageManager.canRequestPackageInstalls()) updater.requestInstaller()
            else updater.finishInstallerRequest(update, denied = true)
        }
    }
    LaunchedEffect(request, resumed) {
        val update = request ?: return@LaunchedEffect
        if (resumed != Lifecycle.State.RESUMED || !updater.state.value.launchInstaller) return@LaunchedEffect
        try {
            if (context.packageManager.canRequestPackageInstalls()) {
                val intent = updater.installIntent() ?: return@LaunchedEffect
                if (lifecycle.currentState != Lifecycle.State.RESUMED || updater.state.value.update != update) return@LaunchedEffect
                context.startActivity(intent)
            } else {
                permissionFor = update.sha256
                permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }
            updater.finishInstallerRequest(update)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            updater.installFailed(error)
        }
    }
}

@Composable
internal fun AppUpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val updater = AppContainer.appUpdates
    val live = updater.state.collectAsStateWithLifecycle()
    val state by remember(live) { derivedStateOf { live.value.copy(bytes = 0) } }
    var showSource by rememberSaveable { mutableStateOf(false) }
    val allowInstall = context.packageManager.canRequestPackageInstalls()
    val installing = state.launchInstaller
    val candidate = state.newerRelease
    val hasSource = updater.source.isNotBlank()
    LaunchedEffect(updater, updater.source, state.phase == UpdatePhase.IDLE) {
        if (updater.state.value.phase == UpdatePhase.IDLE && hasSource) updater.check(updater.source)
    }
    val phase = state.phase
    val installStage = phase == UpdatePhase.READY || state.retry == UpdateRetry.INSTALL
    if (showSource) {
        var follow by rememberSaveable { mutableStateOf(updater.customSource == null) }
        var draft by rememberSaveable { mutableStateOf(updater.savedSource ?: updater.pairedSource) }
        val valid = remember(draft) {
            try { updateHttpUrl(draft); true } catch (_: AppUpdateFailure) { false }
        }
        RemodexAlertDialog(onDismissRequest = { showSource = false }, title = { Text(stringResource(R.string.app_update_source)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(true, false).forEach { automatic ->
                    Row(Modifier.fillMaxWidth().selectable(follow == automatic, role = Role.RadioButton) { follow = automatic }
                        .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = follow == automatic, onClick = null)
                        Text(stringResource(if (automatic) R.string.app_update_follow_connection else R.string.update_custom_source),
                            Modifier.padding(start = 10.dp))
                    }
                }
                if (follow) Text(updater.pairedSource.ifBlank { stringResource(R.string.update_no_connection) }, style = MaterialTheme.typography.bodySmall)
                else OutlinedTextField(draft, { draft = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.app_update_source_hint)) }, isError = draft.isNotBlank() && !valid,
                    supportingText = if (draft.isNotBlank() && !valid) { { Text(stringResource(R.string.update_invalid_source)) } } else null)
            } },
            confirmButton = { TextButton(onClick = {
                showSource = false
                if (follow) updater.followConnectionSource() else updater.check(draft, saveSource = true)
            }, enabled = if (follow) updater.pairedSource.isNotBlank() else valid) { Text(stringResource(R.string.app_update_save_check)) } },
            dismissButton = { TextButton(onClick = { showSource = false }) { Text(stringResource(android.R.string.cancel)) } })
        return
    }
    val downloading = phase == UpdatePhase.CONNECTING || phase == UpdatePhase.DOWNLOADING
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val error = state.error
    var showError by rememberSaveable(error) { mutableStateOf(false) }
    val bodyLimit = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.45f
    val title = when {
        installing -> R.string.update_opening_installer
        phase == UpdatePhase.LATEST -> R.string.update_latest_title
        else -> updateTitle(phase, state.retry)
    }

    RemodexAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title), style = typography.titleLarge) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = bodyLimit).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (candidate != null) {
                            Text(candidate.versionName, style = typography.titleMedium, color = colors.onSurface)
                            if (phase == UpdatePhase.DOWNLOADING && state.total > 0) {
                                UpdateTransferLabel(live)
                            } else {
                                Text(stringResource(R.string.update_installed_size, BuildConfig.VERSION_NAME,
                                    android.text.format.Formatter.formatShortFileSize(context, candidate.sizeBytes)),
                                    style = typography.bodySmall)
                            }
                        } else {
                            Text(stringResource(R.string.app_update_current, BuildConfig.VERSION_NAME),
                                style = typography.bodyMedium)
                        }
                    }
                    if (hasSource || candidate != null) {
                        IconButton(enabled = !state.busy && !installing, onClick = { showSource = true }) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.app_update_source), Modifier.size(20.dp))
                        }
                    }
                }
                if (candidate != null || state.busy) {
                    // Replace the divider in place when downloading, without moving the release notes.
                    Box(Modifier.fillMaxWidth().height(4.dp), contentAlignment = Alignment.Center) {
                        when {
                            phase == UpdatePhase.DOWNLOADING && state.total > 0 -> LinearProgressIndicator(
                                progress = { (live.value.bytes.toFloat() / state.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth())
                            state.busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            !candidate?.notes.isNullOrBlank() -> HorizontalDivider(color = colors.outlineVariant)
                            else -> Unit
                        }
                    }
                }
                if (phase == UpdatePhase.IDLE && !hasSource) Text(stringResource(R.string.update_no_connection))
                if (state.permissionDenied && installStage) Text(stringResource(R.string.update_permission_hint))
                if (error != null) {
                    TextButton(onClick = { showError = !showError }) {
                        Text(stringResource(if (showError) R.string.turn_timeline_hide_details else R.string.questions_error_details))
                    }
                    if (showError) SelectionContainer { Text(error, style = typography.bodySmall, color = colors.error) }
                }
                candidate?.notes?.takeIf(String::isNotBlank)?.let { notes ->
                    Text(stringResource(R.string.update_release_notes), style = typography.labelLarge, color = colors.onSurface)
                    Text(notes, style = typography.bodyMedium)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (downloading) R.string.update_hide_download else R.string.turn_preview_close))
            }
        },
        confirmButton = {
            val check = { updater.check(updater.source) }
            val action: Pair<Int, () -> Unit>? = when {
                downloading -> R.string.app_update_cancel to updater::cancel
                installing || state.busy -> null
                installStage -> (if (allowInstall) R.string.app_update_install else R.string.update_authorize_install) to updater::requestInstaller
                state.retry == UpdateRetry.DOWNLOAD -> R.string.update_retry_download to updater::download
                phase == UpdatePhase.AVAILABLE -> R.string.update_download_install to updater::download
                !hasSource -> R.string.update_setup to { showSource = true }
                phase == UpdatePhase.LATEST -> R.string.update_check_again to check
                phase == UpdatePhase.ERROR -> R.string.update_retry_check to check
                else -> R.string.app_update_check to check
            }
            action?.let { (label, run) ->
                TextButton(onClick = run, modifier = Modifier.widthIn(min = 100.dp)) { Text(stringResource(label)) }
            }
        },
    )
}

@Composable
private fun UpdateTransferLabel(live: State<AppUpdateState>) {
    val state = live.value
    if (state.total <= 0) return
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val format = remember(locale) { java.text.NumberFormat.getPercentInstance(locale) }
    val fraction = (state.bytes.toDouble() / state.total).coerceIn(0.0, 1.0)
    Text(stringResource(R.string.update_transfer_summary, format.format(fraction),
        android.text.format.Formatter.formatShortFileSize(LocalContext.current, state.bytes),
        android.text.format.Formatter.formatShortFileSize(LocalContext.current, state.total)),
        style = MaterialTheme.typography.bodySmall)
}

internal fun updateTitle(phase: UpdatePhase, retry: UpdateRetry?): Int = when (phase) {
    UpdatePhase.IDLE -> R.string.app_update_title
    UpdatePhase.CHECKING -> R.string.app_update_checking
    UpdatePhase.LATEST -> R.string.app_update_latest
    UpdatePhase.AVAILABLE -> R.string.update_available
    UpdatePhase.CONNECTING -> R.string.app_update_connecting
    UpdatePhase.DOWNLOADING -> R.string.update_downloading
    UpdatePhase.CANCELLING -> R.string.app_update_cancelling
    UpdatePhase.VERIFYING -> R.string.app_update_verifying
    UpdatePhase.READY -> R.string.update_ready
    UpdatePhase.ERROR -> when (retry) {
        UpdateRetry.DOWNLOAD -> R.string.update_download_failed
        UpdateRetry.INSTALL -> R.string.update_install_failed
        else -> R.string.update_check_failed
    }
}
