package com.remodex.mobile.ui.turn

import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import com.remodex.mobile.ui.theme.LocalSheetClose
import com.remodex.mobile.data.TurnFileAttachmentCodec
import android.text.format.Formatter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import android.net.Uri
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
@OptIn(ExperimentalMaterial3Api::class)
internal fun SharedDraftDialog(item: SharedContent, threadTitle: String, slots: Int, deviceName: String?, enabled: Boolean,
    onCancel: () -> Unit, onChooseThread: () -> Unit, onAdd: (List<String>) -> Unit) {
    var selected by rememberSaveable(item.id) { mutableStateOf(ArrayList(item.uris.take(slots))) }
    var showFullText by rememberSaveable(item.id) { mutableStateOf(false) }
    var textOverflows by remember(item.id) { mutableStateOf(false) }
    val hasFiles = item.uris.isNotEmpty()
    val hasText = item.text.isNotBlank()

    RemodexModalBottomSheet(onDismissRequest = onCancel, sheetGesturesEnabled = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.share_review), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = LocalSheetClose.current) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.auth_profiles_cancel))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(stringResource(R.string.share_destination), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(threadTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = onChooseThread) { Text(stringResource(R.string.share_change_thread_short)) }
                        }
                    }
                }
                if (hasFiles) {
                    item {
                        Text(stringResource(R.string.share_file_selection, selected.size, item.uris.size),
                            Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
                        if (item.uris.size > slots) Text(stringResource(R.string.share_available_slots, slots),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(item.uris, key = { it }) { raw ->
                        val checked = raw in selected
                        SharedFileRow(raw, item.mime, checked, checked || selected.size < slots) { include ->
                            selected = ArrayList(if (include) selected + raw else selected - raw)
                        }
                    }
                    item {
                        Text(stringResource(R.string.share_prepare_files,
                            deviceName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.share_connected_computer)),
                            Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (hasText) item {
                    Text(stringResource(if (hasFiles) R.string.share_caption else R.string.share_text),
                        Modifier.padding(top = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
                    Text(item.text, style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (showFullText) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        onTextLayout = { if (!showFullText) textOverflows = it.hasVisualOverflow })
                    if (textOverflows || showFullText) TextButton(onClick = { showFullText = !showFullText }) {
                        Text(stringResource(if (showFullText) R.string.share_text_less else R.string.share_text_more))
                    }
                }
            }
            Button(onClick = { onAdd(item.uris.filter { it in selected }) },
                enabled = enabled && selected.size <= slots && (selected.isNotEmpty() || (!hasFiles && hasText)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text(stringResource(R.string.share_add_draft)) }
        }
    }
}

@Composable
private fun SharedFileRow(raw: String, sharedMime: String?, checked: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    val context = LocalContext.current
    val uri = remember(raw) { Uri.parse(raw) }
    val metadata by produceState<TurnFileAttachmentCodec.Metadata?>(null, uri, context) {
        value = withContext(Dispatchers.IO) {
            try {
                TurnFileAttachmentCodec.readMetadata(context.contentResolver, uri)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                android.util.Log.w("RemodexShare", "stage=read_share_metadata; showing fallback", error)
                null
            }
        }
    }
    val uriName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { '.' in it }
    val name = metadata?.name?.takeIf { it.isNotBlank() } ?: uriName ?: stringResource(R.string.share_file)
    val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
    val type = extension?.uppercase(java.util.Locale.ROOT)
        ?: sharedMime?.takeIf { '*' !in it && it != "application/octet-stream" }
    val detail = listOfNotNull(type, metadata?.size?.let { Formatter.formatShortFileSize(context, it) }).joinToString(" · ")
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onSelect)
        .heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Description, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}
