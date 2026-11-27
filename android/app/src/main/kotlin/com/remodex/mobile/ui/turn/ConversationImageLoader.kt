package com.remodex.mobile.ui.turn

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.remodex.mobile.data.TurnAttachmentCodec
import com.remodex.mobile.services.WorkspaceFileTransferService
import com.remodex.mobile.AppContainer
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import com.remodex.mobile.ui.LocalCodexRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.TimeoutException

private data class CachedConversationImage(val file: java.io.File, val modified: Long = file.lastModified())

internal data class ConversationImageState(
    val painter: Painter,
    val loading: Boolean,
    val error: Throwable?,
    val retry: () -> Unit,
    val waitingForConnection: Boolean,
    val hasImage: Boolean,
)

/** Workspace files travel over the paired connection; HTTP images use the phone's image loader. */
@Composable
internal fun rememberConversationImage(source: String, dimension: Long = 1600, fallbackDataUrl: String? = null, originalGif: Boolean = false): ConversationImageState {
    val repository = LocalCodexRepository.current
    val reconnect = com.remodex.mobile.ui.LocalReconnectForMedia.current
    val service = remember(repository) { WorkspaceFileTransferService(repository) }
    val device by repository.relayMacDeviceId.collectAsState()
    val context = LocalContext.current
    val cwd = LocalThreadGitProjectRoot.current
    val ready by repository.isSessionReady.collectAsState()
    var attempt by remember(source, cwd, dimension, device, repository) { mutableIntStateOf(0) }
    var model by remember(source, cwd, dimension, device, repository) { mutableStateOf<Any?>(null) }
    var error by remember(source, cwd, dimension, device, repository) { mutableStateOf<Throwable?>(null) }
    var loading by remember(source, cwd, dimension, device, repository) { mutableStateOf(true) }
    var waitingForConnection by remember(source, cwd, dimension, device, repository) { mutableStateOf(false) }
    LaunchedEffect(source, cwd, dimension, attempt, ready, device, repository, fallbackDataUrl, originalGif) {
        error = null
        loading = model == null
        waitingForConnection = false
        try {
            when (val resolved = conversationImageSource(source)) {
                is ConversationImageSource.Remote -> model = resolved.url
                is ConversationImageSource.Inline -> model = withContext(Dispatchers.IO) { decodeImageData(resolved.dataUrl) }
                is ConversationImageSource.Workspace -> {
                    if (originalGif) {
                        if (!ready) waitingForConnection = model == null
                        else if (model !is ByteArray || attempt > 0) {
                            model = service.gifOriginal(resolved.path, resolved.previewCwd(cwd))
                        }
                        return@LaunchedEffect
                    }
                    val cache = AppContainer.imagePreviews
                    val cacheDevice = device ?: repository.fileTransferBaseUrl.orEmpty()
                    val cacheKey = cache.key(cacheDevice, cwd, resolved.path, dimension)
                    val cached = cache.cachedPreview(cacheDevice, cwd, resolved.path, dimension)
                    if (cached != null) model = CachedConversationImage(cached)
                    else if (model == null && fallbackDataUrl != null) {
                        try {
                            model = withContext(Dispatchers.IO) { decodeImageData(fallbackDataUrl) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            Log.e("RemodexImage", "Invalid fallback thumbnail stage=image_fallback_decode attempt=${attempt + 1}", failure)
                        }
                    }
                    loading = model == null
                    if (!ready) {
                        // Connection recovery belongs to the root coordinator. Do not report
                        // a failed image request when no request has actually been sent.
                        waitingForConnection = true
                        return@LaunchedEffect
                    }
                    val fresh = withTimeoutOrNull(45_000) {
                        cache.load(cacheKey, force = attempt > 0) { service.imagePreview(resolved.path, resolved.previewCwd(cwd), dimension) }
                    } ?: throw TimeoutException("Image preview timed out stage=image_preview_request timeoutMs=45000")
                    currentCoroutineContext().ensureActive()
                    model = CachedConversationImage(fresh)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            try { currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) { cancelled.addSuppressed(failure); throw cancelled }
            if (model == null) error = failure
            Log.e("RemodexImage", "Image load failed stage=image_source_or_preview attempt=${attempt + 1}", failure)
        } finally {
            if (currentCoroutineContext().isActive) loading = false
        }
    }
    val broken = rememberVectorPainter(Icons.Default.BrokenImage)
    val offline = rememberVectorPainter(Icons.Default.CloudOff)
    // Recreate the painter on explicit retry, including failed network requests.
    val imageModel = remember(model, device, dimension) {
        val cached = model as? CachedConversationImage
        ImageRequest.Builder(context).data(cached?.file ?: model).apply {
            size(dimension.toInt(), dimension.toInt())
            if (cached != null) memoryCacheKey("$device:${cached.file.path}:${cached.modified}")
            if (dimension == 512L) {
                decoderFactory(thumbnailDecoder)
                memoryCacheKeyExtra("remodex-static-thumbnail", "true")
            }
        }.build()
    }
    val painter = key(source, device, attempt) {
        if (model == null) null else rememberAsyncImagePainter(model = imageModel, error = broken, placeholder = broken)
    }
    val painterState = painter?.state?.collectAsState()?.value
    // Keep the successful bitmap while a cached thumbnail is upgraded or refreshed.
    // AsyncImagePainter itself is mutable and cannot serve as the saved bitmap.
    var displayedPainter by remember(source, cwd, dimension, device, repository) { mutableStateOf<Painter?>(null) }
    val successfulPainter = (painterState as? AsyncImagePainter.State.Success)?.painter
    SideEffect { if (successfulPainter != null) displayedPainter = successfulPainter }
    val visiblePainter = successfulPainter ?: displayedPainter
    val decodeError = (painterState as? AsyncImagePainter.State.Error)?.result?.throwable
    LaunchedEffect(decodeError) {
        decodeError?.let { Log.e("RemodexImage", "Image load failed stage=image_fetch_or_decode attempt=${attempt + 1}", it) }
    }
    return ConversationImageState(
        painter = visiblePainter ?: if (waitingForConnection && model == null) offline else broken,
        loading = visiblePainter == null && (loading || (model != null && error == null && (painterState == null || painterState is AsyncImagePainter.State.Empty || painterState is AsyncImagePainter.State.Loading))),
        error = if (visiblePainter == null) error ?: decodeError else null,
        retry = {
            if (waitingForConnection && !repository.isSessionReady.value) reconnect?.invoke()
            attempt++
        },
        waitingForConnection = waitingForConnection,
        hasImage = visiblePainter != null,
    )
}

private val thumbnailDecoder = coil3.decode.BitmapFactoryDecoder.Factory()

private fun decodeImageData(dataUrl: String): ByteArray =
    TurnAttachmentCodec.decodeDataUriImageData(dataUrl)
        ?: throw IOException("Invalid image data URL stage=image_data_decode")
