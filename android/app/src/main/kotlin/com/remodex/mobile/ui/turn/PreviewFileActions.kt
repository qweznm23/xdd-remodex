package com.remodex.mobile.ui.turn

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.remodex.mobile.AppContainer
import com.remodex.mobile.R
import com.remodex.mobile.core.model.ConversationMediaType
import com.remodex.mobile.data.TurnAttachmentCodec
import com.remodex.mobile.services.*
import com.remodex.mobile.ui.LocalCodexRepository
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.*
import okhttp3.Request

internal class PreviewOriginal(val name: String, val mime: String, val write: suspend (OutputStream) -> Unit)
private enum class ExportAction { Download, Share }

private suspend fun prepareShare(context: Context, file: PreviewOriginal): Uri = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, "shared-attachments").apply { mkdirs() }
    // Keep successful shares readable after the chooser or preview closes.
    val expiry = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(1)
    dir.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { it.delete() }
    val extension = file.name.substringAfterLast('.', "").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,12}")) }
    val copy = File.createTempFile("attachment-", extension?.let { ".$it" } ?: ".bin", dir)
    try {
        copy.outputStream().use { file.write(it) }
        currentCoroutineContext().ensureActive()
        FileProvider.getUriForFile(context, "${context.packageName}.attachment-camera", copy, file.name)
    } catch (error: Throwable) {
        if (!copy.delete() && copy.exists()) error.addSuppressed(FileTransferFailure("share_cleanup", "delete_failed", "Cannot remove incomplete share"))
        throw error
    }
}

private fun sharePreview(context: Context, uri: Uri, file: PreviewOriginal) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = file.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** The caller keys this group to the displayed attachment so leaving it cancels its transfer. */
@Composable
internal fun PreviewFileActions(
    resolve: suspend () -> PreviewOriginal,
    existingDownload: Uri? = null,
    shareOnly: Boolean = false,
    thumbnailOnly: Boolean = false,
    enabled: Boolean = true,
    beforeAction: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeAction by remember { mutableStateOf<ExportAction?>(null) }
    val busy = activeAction != null
    var downloaded by remember { mutableStateOf<Uri?>(null) }
    var shared by remember { mutableStateOf<Uri?>(null) }

    fun transfer(action: ExportAction, destination: Uri? = null) {
        if (activeAction != null) return
        activeAction = action
        beforeAction()
        scope.launch {
            var stage = "attachment_metadata"
            var name = "attachment"
            try {
                val file = resolve()
                name = file.name
                if (action == ExportAction.Share) {
                    stage = "share_prepare"
                    val uri = existingDownload ?: downloaded ?: shared ?: prepareShare(context, file).also { shared = it }
                    currentCoroutineContext().ensureActive()
                    stage = "share_chooser"
                    sharePreview(context, uri, file)
                } else {
                    stage = "download"
                    downloaded = savePreviewFile(context, name, file.mime, destination, file.write)
                    Toast.makeText(context, if (destination == null) R.string.file_download_saved else R.string.file_download_saved_selected, Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                if (cancelled.suppressed.isNotEmpty()) recordFileTransferError(context, stage, name, 0, cancelled)
                throw cancelled
            } catch (error: Exception) {
                recordFileTransferError(context, stage, name, 0, error)
                Toast.makeText(context, error.localizedMessage ?: error.javaClass.simpleName, Toast.LENGTH_LONG).show()
            } finally { activeAction = null }
        }
    }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { transfer(ExportAction.Download, it) }
    }
    if (!shareOnly) IconButton(enabled = enabled && !busy, onClick = {
        if (Build.VERSION.SDK_INT >= 29) transfer(ExportAction.Download)
        else if (!busy) {
            activeAction = ExportAction.Download
            scope.launch {
                try {
                    val file = resolve()
                    destination.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(file.mime).putExtra(Intent.EXTRA_TITLE, file.name))
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    recordFileTransferError(context, "choose_download", "attachment", 0, error)
                    Toast.makeText(context, error.localizedMessage ?: error.javaClass.simpleName, Toast.LENGTH_LONG).show()
                } finally { activeAction = null }
            }
        }
    }) {
        if (activeAction == ExportAction.Download) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.Download, stringResource(if (thumbnailOnly) R.string.file_download_thumbnail else R.string.file_download_action))
    }
    IconButton(enabled = enabled && !busy, onClick = { transfer(ExportAction.Share) }) {
        if (activeAction == ExportAction.Share) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.Share, stringResource(if (thumbnailOnly) R.string.file_share_thumbnail else R.string.file_share_action))
    }
}

@Composable
internal fun MediaFileActions(source: String, cwd: String?, mimeHint: String? = null, thumbnailOnly: Boolean = false, shareOnly: Boolean = false, beforeAction: () -> Unit = {}) {
    if (source.isBlank()) return
    val repository = LocalCodexRepository.current
    val device by repository.relayMacDeviceId.collectAsState()
    val transfer = remember(repository) { WorkspaceFileTransferService(repository) }
    key(source, cwd, device) {
        var resolved by remember { mutableStateOf<PreviewOriginal?>(null) }
        PreviewFileActions(resolve = {
            resolved ?: resolveMediaOriginal(source, cwd, mimeHint, transfer).also { resolved = it }
        }, thumbnailOnly = thumbnailOnly, shareOnly = shareOnly, beforeAction = beforeAction)
    }
}

private suspend fun resolveMediaOriginal(source: String, cwd: String?, hint: String?, transfer: WorkspaceFileTransferService): PreviewOriginal {
    return when (val media = conversationImageSource(source)) {
        is ConversationImageSource.Workspace -> {
            val directory = media.previewCwd(cwd)
            val info = transfer.info(media.path, directory)
            PreviewOriginal(info.fileName, info.mimeType) { transfer.copyTo(info, directory, it) {} }
        }
        is ConversationImageSource.Inline -> {
            val mime = media.dataUrl.substringBefore(';').substringAfter(':').lowercase()
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
            PreviewOriginal("image.$extension", mime) { output ->
                withContext(Dispatchers.IO) {
                    val bytes = TurnAttachmentCodec.decodeDataUriImageData(media.dataUrl)
                        ?: throw FileTransferFailure("export_inline", "invalid_image", "Invalid inline image")
                    output.write(bytes)
                }
            }
        }
        is ConversationImageSource.Remote -> {
            val name = ConversationMediaType.filename(media.url).filterNot { it.isISOControl() }.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
            val mime = hint ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
            PreviewOriginal(name, mime) { output ->
                withContext(Dispatchers.IO) {
                    val call = AppContainer.httpCallClient.newCall(Request.Builder().url(media.url).build())
                    call.timeout().clearTimeout()
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw FileTransferFailure("export_http", "http_${response.code}", "HTTP ${response.code}: ${response.message}")
                        val body = response.body ?: throw FileTransferFailure("export_http", "empty_response", "Missing file content")
                        val input = body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
    }
}
