package com.remodex.mobile.ui.turn

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.LocalChooseShareThread
import com.remodex.mobile.LocalShareInbox
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.R
import com.remodex.mobile.SharedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun IncomingDraftShare(
    repository: CodexRepository, deviceId: String?, targetId: String, title: String,
    attachments: ComposerAttachmentActions, enabled: Boolean, isCurrentTarget: () -> Boolean = { true },
    appendText: (String) -> Unit,
) {
    val inbox = LocalShareInbox.current ?: return
    val item = inbox.first ?: return
    if (!LocalConversationVisible.current) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val stage by lifecycle.currentStateFlow.collectAsState()
    if (stage != Lifecycle.State.RESUMED) return

    key(item.id, deviceId, targetId) {
        val devices by repository.trustedDevices.collectAsStateWithLifecycle()
        SharedDraftDialog(item, title, attachments.remainingSlots(),
            deviceName = devices.firstOrNull { it.macDeviceId == deviceId }?.displayName,
            enabled = enabled, onCancel = { inbox.take(item.id) }, onChooseThread = LocalChooseShareThread.current,
            onAdd = { selected ->
                if (enabled && lifecycle.currentState == Lifecycle.State.RESUMED && isCurrentTarget() &&
                    repository.isSessionReady.value && repository.currentTrustedMacDeviceId.value == deviceId &&
                    selected.size <= attachments.remainingSlots() && inbox.take(item.id) != null) {
                    if (item.text.isNotBlank()) appendText(item.text)
                    selected.forEach { attachments.addShared(Uri.parse(it), item.mime) }
                }
            })
    }
}

@Composable
internal fun SharedDraftDialog(item: SharedContent, threadTitle: String, slots: Int, deviceName: String?, enabled: Boolean,
    onCancel: () -> Unit, onChooseThread: () -> Unit, onAdd: (List<String>) -> Unit) {
    var selected by rememberSaveable(item.id) { mutableStateOf(ArrayList(item.uris.take(slots))) }
    val context = LocalContext.current
    RemodexAlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.share_to_thread, threadTitle)) },
        text = {
            Column {
                if (item.uris.isNotEmpty()) Text(stringResource(R.string.share_prepare_files,
                    deviceName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.share_connected_computer)),
                    style = MaterialTheme.typography.bodySmall)
                if (item.uris.size > slots) Text(stringResource(R.string.share_available_slots, slots))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    if (item.text.isNotBlank()) item { Text(item.text, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    items(item.uris, key = { it }) { raw ->
                        val uri = remember(raw) { Uri.parse(raw) }
                        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: stringResource(R.string.share_file)
                        val label by produceState(fallback, raw) {
                            value = withContext(Dispatchers.IO) {
                                try {
                                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                        if (index >= 0 && it.moveToFirst()) it.getString(index) else null
                                    } ?: fallback
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) {
                                    android.util.Log.w("RemodexShare", "stage=read_display_name; using URI label", error)
                                    fallback
                                }
                            }
                        }
                        val checked = raw in selected
                        Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox,
                            enabled = checked || selected.size < slots, onValueChange = {
                                selected = ArrayList(if (it) selected + raw else selected - raw)
                            }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(label, Modifier.padding(start = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                TextButton(onClick = onChooseThread) { Text(stringResource(R.string.share_change_thread)) }
            }
        },
        confirmButton = { TextButton(enabled = enabled && selected.size <= slots &&
            (selected.isNotEmpty() || (item.uris.isEmpty() && item.text.isNotBlank())),
            onClick = { onAdd(item.uris.filter { it in selected }) }) { Text(stringResource(R.string.share_add_draft)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.auth_profiles_cancel)) } })
}
