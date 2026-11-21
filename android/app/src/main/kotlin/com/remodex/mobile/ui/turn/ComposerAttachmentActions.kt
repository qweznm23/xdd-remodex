package com.remodex.mobile.ui.turn

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexImageAttachment
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.data.TurnFileAttachmentCodec
import com.remodex.mobile.services.FileTransferFailure
import com.remodex.mobile.services.MAX_FILE_UPLOAD_BYTES
import com.remodex.mobile.services.WorkspaceFileUploadService
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val MAX_COMPOSER_ATTACHMENTS = 4

class AttachmentCameraFileProvider : FileProvider()

internal class ComposerAttachmentActions(
    val pickImages: () -> Unit,
    val pickFiles: () -> Unit,
    val takePhoto: () -> Unit,
    val remainingSlots: () -> Int,
    val addShared: (Uri, String?) -> Unit,
    val retry: (String) -> Unit,
    val remove: (String) -> Unit,
)

@Composable
internal fun rememberComposerAttachmentActions(
    repository: CodexRepository,
    attachments: MutableState<List<TurnComposerAttachment>>,
    blockedReason: String? = null,
    onError: (String?) -> Unit,
): ComposerAttachmentActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val report by rememberUpdatedState(onError)
    val limit = stringResource(R.string.turn_attachment_limit, MAX_COMPOSER_ATTACHMENTS)
    val overflow = stringResource(R.string.turn_attachment_overflow, MAX_COMPOSER_ATTACHMENTS)
    val tooLarge = stringResource(R.string.turn_attachment_file_too_large, MAX_FILE_UPLOAD_BYTES / (1024 * 1024))
    val noCamera = stringResource(R.string.turn_attachment_camera_unavailable)
    val denied = stringResource(R.string.turn_attachment_camera_permission_denied)
    val service = remember(repository) { WorkspaceFileUploadService(repository) }
    val uploads = remember(attachments, service) {
        AttachmentUploadRetryController(scope,
            onState = { id, state ->
                attachments.value = attachments.value.map { if (it.id == id) it.copy(state = state) else it }
            },
            onFailure = { id, attempt, failure ->
                val detail = (failure as? FileTransferFailure)?.let { "stage=${it.stage} code=${it.code}" }
                    ?: "stage=read-or-upload category=unknown"
                Log.e("RemodexUpload", "Attachment failed $detail attachment=$id attempt=$attempt", failure)
                report(failure.localizedMessage ?: failure.javaClass.name)
            })
    }
    DisposableEffect(uploads) { onDispose { uploads.clear() } }
    val ids = attachments.value.map { it.id }
    LaunchedEffect(uploads, ids) { uploads.retain(attachments.value.mapTo(hashSetOf()) { it.id }) }

    fun remaining() = (MAX_COMPOSER_ATTACHMENTS - attachments.value.size).coerceAtLeast(0)
    fun allowed(): Boolean {
        val reason = blockedReason ?: limit.takeIf { remaining() == 0 }
        if (reason != null) report(reason)
        return reason == null
    }
    fun addSlot() = UUID.randomUUID().toString().also { attachments.value = attachments.value.withLoadingAttachment(it) }
    fun addUri(uri: Uri, imageOnly: Boolean, sharedMime: String?, releaseSource: () -> Unit = {}) {
        uploads.add(addSlot(), releaseSource) { progress ->
            val mime = withContext(Dispatchers.IO) { context.contentResolver.getType(uri) ?: sharedMime }
            val file = TurnFileAttachmentCodec.uploadAttachment(context, uri, service, tooLarge, mime) { bytes, total ->
                withContext(Dispatchers.Main) { progress(bytes, total) }
            }
            if (imageOnly || mime?.startsWith("image/") == true) {
                val path = requireNotNull(file.remotePath)
                val image = CodexImageAttachment(thumbnailBase64JPEG = "", sourceURL = path, mimeType = mime, remotePath = path)
                TurnComposerAttachmentState.ReadyImage(image)
            } else {
                TurnComposerAttachmentState.ReadyFile(file)
            }
        }
    }
    fun accept(uris: List<Uri>, imageOnly: Boolean) {
        if (uris.isEmpty() || !allowed()) return
        val accepted = uris.take(remaining())
        if (accepted.size < uris.size) report(overflow)
        accepted.forEach { addUri(it, imageOnly, null) }
    }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { accept(it, true) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { accept(it, false) }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val authority = "${context.packageName}.attachment-camera"
    fun deleteCapture(file: File) {
        runCatching { if (file.exists() && !file.delete()) throw IOException("Cannot remove temporary attachment capture") }
            .onFailure { Log.w("RemodexUpload", "stage=camera_capture_cleanup", it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = pendingCapture?.let(::File) ?: return@rememberLauncherForActivityResult
        pendingCapture = null
        if (captured && allowed()) {
            val uri = try { FileProvider.getUriForFile(context, authority, file) }
            catch (failure: IllegalArgumentException) {
                Log.e("RemodexUpload", "stage=camera_capture_uri", failure)
                report(failure.localizedMessage ?: failure.javaClass.name)
                deleteCapture(file)
                return@rememberLauncherForActivityResult
            }
            addUri(uri, true, "image/jpeg") { deleteCapture(file) }
        } else deleteCapture(file)
    }
    fun launchCamera() {
        if (pendingCapture != null) return
        var created: File? = null
        try {
            val directory = File(context.cacheDir, "attachment-capture")
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create camera attachment directory")
            val file = File.createTempFile("photo-", ".jpg", directory)
            created = file
            pendingCapture = file.absolutePath
            camera.launch(FileProvider.getUriForFile(context, authority, file))
        } catch (failure: Exception) {
            pendingCapture = null
            created?.let(::deleteCapture)
            Log.w("RemodexUpload", "stage=camera_launch", failure)
            report(if (failure is ActivityNotFoundException) noCamera else failure.localizedMessage ?: failure.javaClass.name)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) report(denied) else if (allowed()) launchCamera()
    }
    return ComposerAttachmentActions(
        pickImages = { if (allowed()) photos.launch("image/*") },
        pickFiles = { if (allowed()) documents.launch(arrayOf("*/*")) },
        takePhoto = {
            if (allowed()) {
                when {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> launchCamera()
                    else -> permission.launch(Manifest.permission.CAMERA)
                }
            }
        },
        remainingSlots = ::remaining,
        addShared = { uri, mime -> addUri(uri, false, mime) },
        retry = { report(null); uploads.retry(it) },
        remove = { id ->
            uploads.remove(id)
            attachments.value = attachments.value.filterNot { it.id == id }
        },
    )
}
