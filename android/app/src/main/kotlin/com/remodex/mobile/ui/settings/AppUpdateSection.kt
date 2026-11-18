package com.remodex.mobile.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.AppContainer
import com.remodex.mobile.BuildConfig
import com.remodex.mobile.R
import com.remodex.mobile.update.UpdatePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun AppUpdateSection() {
    val context = LocalContext.current
    val updater = AppContainer.appUpdates
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSource by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf(updater.source) }
    var installing by remember { mutableStateOf(false) }
    var permissionNotice by remember { mutableStateOf(false) }
    fun install() {
        if (installing) return
        scope.launch {
            installing = true
            try { updater.installIntent()?.let(context::startActivity)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { updater.installFailed(e)
            } finally { installing = false }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) {
            permissionNotice = false
            install()
        } else { permissionNotice = true }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.app_update_current, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { updater.check(updater.source) }, enabled = !state.busy && !installing) {
                Text(stringResource(R.string.app_update_check))
            }
            TextButton(onClick = { source = updater.source; showSource = true }, enabled = !state.busy && !installing) {
                Text(stringResource(R.string.app_update_source))
            }
        }
        when (state.phase) {
            UpdatePhase.CHECKING -> Text(stringResource(R.string.app_update_checking))
            UpdatePhase.LATEST -> Text(stringResource(R.string.app_update_latest))
            UpdatePhase.VERIFYING -> Text(stringResource(R.string.app_update_verifying))
            UpdatePhase.READY -> Text(stringResource(R.string.app_update_ready))
            UpdatePhase.CONNECTING -> Text(stringResource(R.string.app_update_connecting))
            UpdatePhase.CANCELLING -> Text(stringResource(R.string.app_update_cancelling))
            else -> Unit
        }
        state.update?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }?.let { update ->
            Text(stringResource(R.string.app_update_new, update.versionName, String.format(Locale.ROOT, "%.1f", update.sizeBytes / 1048576.0)))
            if (update.notes.isNotBlank()) Text(update.notes, style = MaterialTheme.typography.bodySmall)
            if (state.phase in setOf(UpdatePhase.CONNECTING, UpdatePhase.DOWNLOADING, UpdatePhase.CANCELLING)) {
                LinearProgressIndicator(progress = { (state.bytes.toFloat() / state.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.app_update_progress, String.format(Locale.ROOT, "%.1f", state.bytes / 1048576.0), String.format(Locale.ROOT, "%.1f", state.total / 1048576.0)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.app_update_keep_app_open), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = updater::cancel, enabled = state.phase != UpdatePhase.CANCELLING) { Text(stringResource(R.string.app_update_cancel)) }
            }
            if (state.phase == UpdatePhase.AVAILABLE || state.phase == UpdatePhase.ERROR) {
                Button(onClick = updater::download) { Text(stringResource(R.string.app_update_download)) }
            }
            if (state.phase == UpdatePhase.READY) {
                Button(enabled = !installing, onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) install()
                    else {
                        try { permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        } catch (e: Exception) { updater.installFailed(e) }
                    }
                }) { Text(stringResource(R.string.app_update_install)) }
            }
        }
        if (permissionNotice) Text(stringResource(R.string.app_update_permission), style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (showSource) AlertDialog(
        onDismissRequest = { showSource = false },
        title = { Text(stringResource(R.string.app_update_source)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(stringResource(R.string.app_update_effective_source, updater.source), style = MaterialTheme.typography.bodySmall); OutlinedTextField(value = source, onValueChange = { source = it }, label = { Text(stringResource(R.string.app_update_source_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) } },
        confirmButton = { TextButton(onClick = { showSource = false; updater.check(source, saveSource = true) }) { Text(stringResource(R.string.app_update_save_check)) } },
        dismissButton = { TextButton(onClick = { showSource = false; updater.followConnectionSource() }) { Text(stringResource(R.string.app_update_follow_connection)) } },
    )
}
