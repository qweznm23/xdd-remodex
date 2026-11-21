package com.remodex.mobile.ui.settings

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.remodex.mobile.R
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.data.CodexRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MacBridgeControls(repository: CodexRepository, device: String?, ready: Boolean) {
    if (ready && device != null) key(repository, device) { ConnectedMacControls(repository, device) }
}

@Composable
private fun ConnectedMacControls(repository: CodexRepository, device: String) {
    val scope = rememberCoroutineScope()
    var awake by remember { mutableStateOf<Boolean?>(null) }
    var installed by remember { mutableStateOf<String?>(null) }
    var canUpdate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    suspend fun request(method: String, params: Map<String, JSONValue> = emptyMap()) {
        if (busy || repository.currentTrustedMacDeviceId.value != device) return
        busy = true
        error = null
        outcome = 0
        try {
            val result = repository.sendRequest(method, JSONValue.Obj(params)).result?.objectValue
            check(result != null && result["success"]?.boolValue == true) { "$method missing success" }
            if (method == "desktop/bridge/updateAndRestart") {
                outcome = when {
                    result["restartScheduled"]?.boolValue == true -> R.string.mac_update_scheduled
                    result["upToDate"]?.boolValue == true -> R.string.mac_update_latest
                    else -> error("$method missing restart status")
                }
            } else {
                awake = requireNotNull(result["preferences"]?.objectValue?.get("keepMacAwake")?.boolValue)
                installed = result["installedXddVersion"]?.stringValue
                canUpdate = result["xddPackageUpdateSupported"]?.boolValue == true
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            android.util.Log.e("RemodexMac", "stage=$method", failure)
            error = failure.message ?: failure.javaClass.name
        } finally { busy = false }
    }
    LaunchedEffect(Unit) { request("desktop/preferences/read") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.mac_keep_awake), Modifier.weight(1f))
        Switch(checked = awake == true, enabled = !busy && awake != null, onCheckedChange = {
            scope.launch { request("desktop/preferences/update", mapOf("keepMacAwake" to JSONValue.Bool(it))) }
        })
    }
    installed?.let { Text("XDD $it", style = MaterialTheme.typography.bodyMedium) }
    if (canUpdate) TextButton(enabled = !busy, onClick = { confirm = true }) { Text(stringResource(R.string.mac_bridge_update)) }
    else if (awake != null) Text(stringResource(R.string.mac_update_managed), style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator()
    if (outcome != 0) Text(stringResource(outcome))
    error?.let { Text(it, color = MaterialTheme.colorScheme.error)
        TextButton(enabled = !busy, onClick = { scope.launch { request("desktop/preferences/read") } }) { Text(stringResource(R.string.goal_retry)) }
    }
    if (confirm) RemodexAlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.mac_bridge_update)) }, text = { Text(stringResource(R.string.mac_update_confirm)) },
        confirmButton = { TextButton(onClick = { confirm = false; scope.launch { request("desktop/bridge/updateAndRestart") } }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } })
}

