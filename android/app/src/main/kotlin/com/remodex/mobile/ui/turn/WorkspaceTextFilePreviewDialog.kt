package com.remodex.mobile.ui.turn

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.services.*
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local links use preview when available and otherwise enter the download flow. */
data class WorkspaceTextFilePreviewRequest(val path: String, val cwd: String?, val downloadOnly: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTextFilePreviewDialog(request: WorkspaceTextFilePreviewRequest, service: WorkspaceTextFileService, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reloadKey by remember(request) { mutableStateOf(0) }
    var videoPreview by remember(request) { mutableStateOf(false) }
    var info by remember(request) { mutableStateOf<WorkspaceFileInfo?>(null) }
    var pageStarts by remember(request) { mutableStateOf(listOf(0L)) }
    var pageIndex by remember(request) { mutableStateOf(0) }
    var textOffset by remember(request) { mutableStateOf(0L) }
    var textPageLoading by remember(request) { mutableStateOf(false) }
    var text by remember(request) { mutableStateOf<String?>(null) }
    var image by remember(request) { mutableStateOf<ImageBitmap?>(null) }
    var errorMessage by remember(request) { mutableStateOf<String?>(null) }
    var loading by remember(request) { mutableStateOf(true) }
    var downloading by remember(request) { mutableStateOf(false) }
    var downloadJob by remember(request) { mutableStateOf<Job?>(null) }
    var downloadedBytes by remember(request) { mutableStateOf(0L) }
    var savedUri by remember(request) { mutableStateOf<Uri?>(null) }
    var cancelled by remember(request) { mutableStateOf(false) }
    var autoDownload by remember(request) { mutableStateOf(true) }

    fun fail(operation: String, error: Exception) {
        recordFileTransferError(context, operation, request.path, downloadedBytes, error)
        errorMessage = error.message ?: error.javaClass.name
    }
    fun startDownload(destination: Uri?) {
        val file = info ?: return
        if (downloading) return
        downloading = true
        cancelled = false
        errorMessage = null
        savedUri = null
        downloadedBytes = 0
        downloadJob = scope.launch {
            try {
                savedUri = saveWorkspaceFile(context, service.transfers, file, request.cwd, destination) { downloadedBytes = it }
            } catch (error: CancellationException) {
                if (error.suppressed.isNotEmpty()) {
                    recordFileTransferError(context, "cancel_download", request.path, downloadedBytes, error)
                    cancelled = false
                    errorMessage = error.suppressed.joinToString("; ") { it.message ?: it.javaClass.name }
                }
                throw error
            } catch (error: Exception) { fail("download", error)
            } finally { downloading = false }
        }
    }
    val chooseDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) startDownload(uri) else cancelled = true
    }
    fun download() {
        if (Build.VERSION.SDK_INT >= 29) startDownload(null)
        else info?.let { chooseDestination.launch(it.fileName) }
    }
    fun openSaved() {
        val uri = savedUri ?: return
        val file = info ?: return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (error: Exception) { fail("open_download", error) }
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) openSaved()
    }
    DisposableEffect(request) { onDispose { downloadJob?.cancel() } }

    LaunchedEffect(request, reloadKey) {
        loading = true
        errorMessage = null
        text = null
        image = null
        try {
            val file = service.transfers.info(request.path, request.cwd)
            info = file
            if (!request.downloadOnly && file.mimeType.startsWith("video/")) {
                videoPreview = true
                return@LaunchedEffect
            }
            when (file.previewKind) {
                "text" -> if (file.binaryStream) {
                    val page = service.transfers.textPage(file,request.cwd,0)
                    text = page.text; textOffset = page.nextOffset; pageStarts = listOf(0L); pageIndex = 0
                } else { text = service.readPreview(file.path, request.cwd).content; textOffset = file.byteLength }
                "image" -> {
                    val bytes = if (file.binaryStream) {
                        val repository = com.remodex.mobile.AppContainer.codexRepository
                        val cache = com.remodex.mobile.AppContainer.imagePreviews
                        val key = cache.key(repository.relayMacDeviceId.value ?: repository.fileTransferBaseUrl.orEmpty(),request.cwd,file.path,1600)
                        val cached = cache.load(key) { service.transfers.imagePreview(file.path,request.cwd,1600) }
                        withContext(Dispatchers.IO) { cached.readBytes() }
                    } else service.transfers.image(file, request.cwd)
                    image = withContext(Dispatchers.IO) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw FileTransferFailure("preview", "invalid_image", "Cannot decode image preview")
                        val options = BitmapFactory.Options()
                        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize.coerceAtLeast(1) > 1600) options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                        (BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            ?: throw FileTransferFailure("preview", "invalid_image", "Cannot decode image preview")).asImageBitmap()
                    }
                }
                else -> if (autoDownload) { autoDownload = false; download() }
            }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { fail("preview", error)
        } finally { loading = false }
    }

    if (videoPreview) {
        ConversationVideoPlayer(request.path,request.cwd,onDismiss)
        return
    }
    RemodexModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = true) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 18.dp).padding(bottom = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(info?.fileName ?: request.path.substringAfterLast('/'), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.turn_preview_close)) }
            }
            info?.let { file ->
                Text(file.path, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
                Text(stringResource(R.string.file_download_size, file.byteLength / 1048576.0), style = MaterialTheme.typography.labelSmall)
                if (downloading) {
                    LinearProgressIndicator(progress = { (downloadedBytes.toFloat() / file.byteLength.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.file_download_progress, downloadedBytes / 1048576.0, file.byteLength / 1048576.0))
                    Text(stringResource(R.string.file_download_keep_open), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { cancelled = true; downloadJob?.cancel() }) { Text(stringResource(R.string.app_update_cancel)) }
                } else if (savedUri != null) {
                    Text(stringResource(if (Build.VERSION.SDK_INT >= 29) R.string.file_download_saved else R.string.file_download_saved_selected))
                    Button(onClick = {
                        if (file.mimeType == "application/vnd.android.package-archive" && !context.packageManager.canRequestPackageInstalls()) {
                            try { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                            catch (error: Exception) { fail("install_permission", error) }
                        } else openSaved()
                    }) { Text(stringResource(R.string.file_download_open)) }
                } else {
                    if (file.previewKind == "none") Text(stringResource(R.string.file_download_no_preview))
                    if (cancelled) Text(stringResource(R.string.file_download_cancelled))
                    Button(onClick = ::download, enabled = !loading) { Text(stringResource(R.string.file_download_action)) }
                }
            }
            errorMessage?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.heightIn(max = 130.dp).verticalScroll(rememberScrollState()))
                TextButton(enabled = !downloading, onClick = { autoDownload = true; reloadKey++ }) { Text(stringResource(R.string.workspace_text_preview_retry)) }
            }
            if (loading) Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), Alignment.Center) { CircularProgressIndicator() }
            text?.let { content ->
                val file = info
                if (file != null && file.binaryStream && (pageIndex > 0 || textOffset < file.byteLength)) {
                    fun loadPage(offset: Long, index: Int) {
                        textPageLoading = true
                        scope.launch {
                            try {
                                val page = service.transfers.textPage(file,request.cwd,offset)
                                if (index >= pageStarts.size) pageStarts = pageStarts + offset
                                pageIndex = index; text = page.text; textOffset = page.nextOffset
                            } catch(cancelled: CancellationException) { throw cancelled }
                            catch(failure: Exception) { fail("preview_page",failure) }
                            finally { textPageLoading = false }
                        }
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = !textPageLoading && pageIndex > 0,onClick = { loadPage(pageStarts[pageIndex-1],pageIndex-1) }) { Text(stringResource(R.string.file_preview_previous_page)) }
                        TextButton(enabled = !textPageLoading && textOffset < file.byteLength,onClick = { loadPage(textOffset,pageIndex+1) }) { Text(stringResource(R.string.file_preview_next_page)) }
                    }
                }
                Surface(Modifier.fillMaxWidth().weight(1f), shape = MaterialTheme.shapes.medium) {
                    key(pageIndex) { SelectionContainer {
                        Text(content, modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    } }
                }
            }
            image?.let { bitmap ->
                ZoomablePreviewImage(
                    onDismiss = onDismiss,
                    painter = remember(bitmap) { BitmapPainter(bitmap) },
                    sourceKey = "${request.cwd}:${request.path}",
                    contentDescription = info?.fileName,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}
