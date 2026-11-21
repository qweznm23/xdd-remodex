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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
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
import java.util.Locale

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
    val candidate = state.update?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    val hasSource = updater.source.isNotBlank()
    LaunchedEffect(updater, updater.source, state.phase == UpdatePhase.IDLE) {
        if (updater.state.value.phase == UpdatePhase.IDLE && hasSource) updater.check(updater.source)
    }
    val phase = state.phase
    val installStage = phase == UpdatePhase.READY || state.retry == UpdateRetry.INSTALL
    if (showSource) {
        var follow by rememberSaveable { mutableStateOf(updater.customSource == null) }
        var draft by rememberSaveable { mutableStateOf(updater.customSource ?: updater.pairedSource) }
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
    val status = stringResource(updateTitle(phase, state.retry))
    val bodyHeight = minOf(248.dp, androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.42f)
    RemodexAlertDialog(
        modifier = Modifier.fillMaxWidth(),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_update_title)) },
        text = {
            Column(Modifier.fillMaxWidth().height(bodyHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.app_update_current, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                candidate?.let { update ->
                    Text(stringResource(R.string.app_update_new, update.versionName, String.format(Locale.ROOT, "%.1f", update.sizeBytes / 1048576.0)),
                        style = MaterialTheme.typography.titleSmall)
                }
                // Only this element observes the download counter.
                UpdateStatusLine(live, status)
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (downloading && state.total > 0) {
                        LinearProgressIndicator(progress = { (live.value.bytes.toFloat() / state.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth())
                    } else if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (!hasSource && phase == UpdatePhase.IDLE) Text(stringResource(R.string.update_no_connection))
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state.permissionDenied && installStage) Text(stringResource(R.string.app_update_permission), style = MaterialTheme.typography.bodySmall)
                candidate?.notes?.takeIf(String::isNotBlank)?.let { notes ->
                    Text(stringResource(R.string.update_release_notes), style = MaterialTheme.typography.labelLarge)
                    Text(notes, style = MaterialTheme.typography.bodyMedium)
                }
                if (hasSource) {
                    TextButton(enabled = !state.busy && !installing, onClick = { showSource = true }) {
                        Text(stringResource(R.string.app_update_source))
                    }
                }
                if (downloading) Text(stringResource(R.string.app_update_keep_app_open), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.turn_preview_close)) }
                val action = when {
                    downloading -> R.string.app_update_cancel
                    phase == UpdatePhase.CANCELLING -> R.string.app_update_cancelling
                    phase == UpdatePhase.VERIFYING -> R.string.app_update_download
                    phase == UpdatePhase.CHECKING -> R.string.app_update_check
                    installStage -> if (allowInstall) R.string.app_update_install else R.string.update_authorize_install
                    state.retry == UpdateRetry.DOWNLOAD -> R.string.update_retry_download
                    phase == UpdatePhase.AVAILABLE -> R.string.app_update_download
                    !hasSource -> R.string.update_setup
                    phase == UpdatePhase.ERROR || phase == UpdatePhase.LATEST -> R.string.update_retry_check
                    else -> R.string.app_update_check
                }
                Button(modifier = Modifier.weight(1.5f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    enabled = !installing && (!state.busy || downloading), onClick = {
                    when {
                        downloading -> updater.cancel()
                        installStage -> updater.requestInstaller()
                        phase == UpdatePhase.AVAILABLE || state.retry == UpdateRetry.DOWNLOAD -> updater.download()
                        hasSource -> updater.check(updater.source)
                        else -> showSource = true
                    }
                }) {
                    val labelHeight = with(LocalDensity.current) { MaterialTheme.typography.labelLarge.lineHeight.toDp() * 2 }
                    Box(Modifier.heightIn(min = labelHeight), contentAlignment = Alignment.Center) {
                        Text(stringResource(action), textAlign = TextAlign.Center)
                    }
                }
            }
        },
    )
}

@Composable
private fun UpdateStatusLine(live: State<com.remodex.mobile.update.AppUpdateState>, status: String) {
    val state = live.value
    val label = if (state.phase == UpdatePhase.DOWNLOADING && state.total > 0) {
        stringResource(R.string.app_update_progress, String.format(Locale.ROOT, "%.1f", state.bytes / 1048576.0),
            String.format(Locale.ROOT, "%.1f", state.total / 1048576.0))
    } else status
    Text(label, style = MaterialTheme.typography.bodyMedium)
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
