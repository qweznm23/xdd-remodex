package com.remodex.mobile.ui.turn

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.services.*
import com.remodex.mobile.ui.theme.CodeFontFamily
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ArchivePreview(
    file: WorkspaceFileInfo,
    loadPage: suspend (Long) -> ArchivePage,
    loadMember: suspend (ArchiveMember) -> ByteArray,
) {
    val context = LocalContext.current
    var offset by remember { mutableLongStateOf(0L) }
    var attempt by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<ArchivePage?>(null) }
    var selected by remember { mutableStateOf<ArchiveMember?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(offset, attempt) {
        failure = null
        page = null
        try { page = loadPage(offset) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            recordFileTransferError(context, "archive_list_offset_$offset", file.path, 0, error)
            failure = error
        }
    }
    val directory = page
    when {
        failure != null -> ArchiveError(failure!!) { attempt++ }
        directory == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        directory.total == 0L -> Text(stringResource(R.string.zip_empty))
        else -> Column {
            Text(stringResource(R.string.zip_directory_summary, directory.total, Formatter.formatShortFileSize(context, file.byteLength)),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (offset > 0 || directory.nextOffset < directory.total) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = offset > 0, onClick = { offset = (offset - 64).coerceAtLeast(0) }) {
                        Text(stringResource(R.string.file_preview_previous_page))
                    }
                    TextButton(enabled = directory.nextOffset < directory.total, onClick = { offset = directory.nextOffset }) {
                        Text(stringResource(R.string.file_preview_next_page))
                    }
                }
            }
            key(offset) { LazyColumn {
                items(directory.members, key = { it.index }) { member ->
                    val canOpen = member.kind == "image" || member.kind == "text"
                    val path = member.name.trimEnd('/')
                    val parent = path.substringBeforeLast('/', "")
                    val size = Formatter.formatShortFileSize(context, member.size)
                    val detail = when {
                        member.directory -> stringResource(R.string.zip_folder)
                        canOpen -> size
                        else -> stringResource(R.string.zip_no_preview, size)
                    }
                    ListItem(
                        modifier = if (canOpen) Modifier.clickable { selected = member } else Modifier,
                        headlineContent = { Text(path.substringAfterLast('/'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(listOf(parent, detail).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(when {
                            member.directory -> Icons.Outlined.Folder
                            member.kind == "image" -> Icons.Outlined.Image
                            else -> Icons.Outlined.Description
                        }, contentDescription = null) },
                        trailingContent = if (canOpen) ({ Icon(Icons.Outlined.ChevronRight, null) }) else null,
                    )
                }
            } }
        }
    }
    selected?.let { entry ->
        key(entry.index) { ArchiveMemberDialog(file, entry, loadMember) { selected = null } }
    }
}

@Composable
private fun ArchiveError(error: Throwable, retry: () -> Unit) {
    Column(Modifier.padding(12.dp)) {
        Text(error.message ?: error.javaClass.simpleName, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = retry) { Text(stringResource(R.string.workspace_text_preview_retry)) }
    }
}

@Composable
private fun ArchiveMemberDialog(
    file: WorkspaceFileInfo, entry: ArchiveMember, loadMember: suspend (ArchiveMember) -> ByteArray, onDismiss: () -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var imageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    val context = LocalContext.current
    LaunchedEffect(attempt) {
        failure = null
        imageBytes = null
        try {
            val bytes = loadMember(entry)
            if (entry.kind == "image") imageBytes = bytes
            else text = withContext(Dispatchers.Default) {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            recordFileTransferError(context, "archive_entry_${entry.index}", file.path, 0, error)
            failure = error
        }
    }
    ImagePreviewDialog(entry.name.substringAfterLast('/'), onDismiss, actions = {}) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val image = imageBytes
            val content = text
            when {
                failure != null -> ArchiveError(failure!!) { attempt++ }
                image != null -> EncodedImagePreview(image, "${file.revision}:${entry.index}", entry.name, onDismiss) { error ->
                    recordFileTransferError(context, "archive_image_decode_${entry.index}", file.path, image.size.toLong(), error)
                    failure = error
                }
                content != null -> SelectionContainer(Modifier.fillMaxSize()) {
                    Text(content, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFontFamily))
                }
                else -> CircularProgressIndicator()
            }
        }
    }
}
