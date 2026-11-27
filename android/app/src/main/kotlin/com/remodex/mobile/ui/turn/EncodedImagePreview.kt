package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest

/** Keep the encoded source so the decoder can retain GIF frame timing and loop count. */
@Composable
internal fun EncodedImagePreview(
    bytes: ByteArray,
    sourceKey: String,
    title: String?,
    onDismiss: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val request = remember(bytes, context) { ImageRequest.Builder(context).data(bytes).size(1600, 1600).build() }
    val painter = rememberAsyncImagePainter(request)
    val state by painter.state.collectAsState()
    val error = (state as? AsyncImagePainter.State.Error)?.result?.throwable
    LaunchedEffect(error) { if (error != null) onFailure(error) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            is AsyncImagePainter.State.Success -> ZoomablePreviewImage(painter, sourceKey, Modifier.fillMaxSize(), title, onDismiss)
            is AsyncImagePainter.State.Error -> Unit
            else -> CircularProgressIndicator()
        }
    }
}
