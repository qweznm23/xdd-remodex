package com.remodex.mobile.ui.mydevices

import android.text.format.Formatter
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.remodex.mobile.BuildConfig
import com.remodex.mobile.R
import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.services.HostSystemInfo
import com.remodex.mobile.services.readHostSystemInfo
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostSystemInfoSheet(repository: CodexRepository, deviceId: String, cwd: String?, onDismiss: () -> Unit) = key(repository, deviceId, cwd) {
    val connected by repository.relayMacDeviceId.collectAsStateWithLifecycle()
    val ready by repository.isSessionReady.collectAsStateWithLifecycle()
    val switching by repository.switchingDeviceId.collectAsStateWithLifecycle()
    val online = connected == deviceId && ready && switching == null
    var snapshot by remember { mutableStateOf<HostSystemInfo?>(null) }
    var failure by remember { mutableStateOf<Exception?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(online, expanded, refresh, lifecycle) {
        if (!online) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do {
                try {
                    val data = withTimeoutOrNull(8_000) { repository.readHostSystemInfo(cwd) }
                        ?: throw IOException("Host sample timed out (8 s)")
                    if (repository.relayMacDeviceId.value != deviceId || repository.switchingDeviceId.value != null) return@repeatOnLifecycle
                    if (data.disk.error != snapshot?.disk?.error && data.disk.error != null)
                        Log.w("RemodexHost", "stage=statfs diagnostic=${data.disk.error}")
                    snapshot = data
                    failure = null
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    Log.e("RemodexHost", "stage=host_system_info data=${(error as? CodexServiceError.RpcFailure)?.rpcError?.data}", error)
                    failure = error
                    return@repeatOnLifecycle
                }
                if (expanded) delay(2500)
            } while (expanded)
        }
    }
    val disclosure = stringResource(if (expanded) R.string.session_info_expanded else R.string.session_info_collapsed)
    RemodexModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.host_system_info), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { refresh++ }, enabled = online) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.host_info_refresh))
                }
            }
            val data = snapshot
            if (!online) Text(stringResource(R.string.host_info_offline), style = MaterialTheme.typography.bodySmall)
            if (online) failure?.let { error ->
                val rpc = (error as? CodexServiceError.RpcFailure)?.rpcError
                Text(if (rpc?.code == -32601) stringResource(R.string.host_info_update_bridge) else error.message ?: error.javaClass.simpleName,
                    color = MaterialTheme.colorScheme.error)
            }
            if (data == null && online && failure == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (data != null) LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(data.hostname, style = MaterialTheme.typography.titleMedium)
                    Text("${data.system} ${data.release} · ${data.arch}", style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.host_info_versions, BuildConfig.VERSION_NAME, data.bridgeVersion), style = MaterialTheme.typography.bodySmall)
                }
                item {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                        .semantics { stateDescription = disclosure }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.host_info_cpu, data.cpu.cores.size), Modifier.weight(1f))
                        Text(data.cpu.percent?.let { "$it%" } ?: "—")
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                }
                if (expanded) {
                    val rows = data.cpu.cores.chunked(2)
                    items(rows, key = { it.first().id }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            row.forEach { core ->
                                Column(Modifier.weight(1f)) {
                                    Text("CPU ${core.id}   ${core.percent?.let { "$it%" } ?: "—"}", style = MaterialTheme.typography.labelMedium)
                                    core.percent?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                item {
                    ResourceLine(stringResource(R.string.host_info_memory), data.memoryTotal - data.memoryFree, data.memoryTotal, useGiB = true)
                    val disk = data.disk
                    disk.total?.let { total ->
                        if (disk.used != null) {
                            ResourceLine(stringResource(R.string.host_info_disk), disk.used, total)
                        } else if (disk.available != null) {
                            // Older bridges only report space available to ordinary users, excluding reserved blocks.
                            ResourceLine(stringResource(R.string.host_info_disk_estimated), total - disk.available, total)
                        }
                    }
                    data.disk.error?.let { Text(it["message"]?.jsonPrimitive?.content.orEmpty(), color = MaterialTheme.colorScheme.error) }
                    Text(data.disk.directory, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.host_info_sampled, DateFormat.getTimeInstance().format(Date(data.sampledAt))),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ResourceLine(label: String, used: Long, total: Long, useGiB: Boolean = false) {
    val context = LocalContext.current
    if (total <= 0 || used !in 0..total) {
        Text("$label　—", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val fraction = used.toDouble() / total
    val percentFormat = remember(context.resources.configuration.locales[0]) {
        NumberFormat.getPercentInstance(context.resources.configuration.locales[0])
    }
    val capacity = if (useGiB) {
        stringResource(R.string.host_info_memory_capacity_gib, label, percentFormat.format(fraction),
            used / BYTES_PER_GIB, total / BYTES_PER_GIB)
    } else {
        stringResource(R.string.host_info_capacity, label, percentFormat.format(fraction),
            Formatter.formatShortFileSize(context, used), Formatter.formatShortFileSize(context, total))
    }
    Text(capacity, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
    LinearProgressIndicator(progress = { fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
}

private const val BYTES_PER_GIB = 1024.0 * 1024 * 1024
