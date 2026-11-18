package com.remodex.mobile.ui.turn

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import kotlin.math.abs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R

@Composable
internal fun ZoomablePreviewImage(
    painter: Painter,
    sourceKey: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onDismiss: (() -> Unit)? = null,
    pagingEnabled: Boolean = false,
    active: Boolean = true,
    onZoomChanged: (Boolean) -> Unit = {},
) {
    var viewport by remember(sourceKey) { mutableStateOf(IntSize.Zero) }
    var transform by remember(sourceKey, viewport) { mutableStateOf(ImagePreviewTransform()) }
    val currentOnZoomChanged by androidx.compose.runtime.rememberUpdatedState(onZoomChanged)
    var dismissDrag by remember(sourceKey) { mutableStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(active) {
        if (!active) { transform = ImagePreviewTransform(); dismissDrag = 0f }
    }
    androidx.compose.runtime.LaunchedEffect(active, transform.scale > 1.01f) {
        if (active) currentOnZoomChanged(transform.scale > 1.01f)
    }
    val imageSize = painter.intrinsicSize
    val imageWidth = if (imageSize.isSpecified) imageSize.width else Float.NaN
    val imageHeight = if (imageSize.isSpecified) imageSize.height else Float.NaN
    val hint = stringResource(R.string.image_preview_gestures)
    Box(modifier.clipToBounds().onSizeChanged { viewport = it }) {
        Image(
            painter = painter,
            contentDescription = contentDescription ?: hint,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .semantics { stateDescription = "${(transform.scale * 100).toInt()}%" }
                .pointerInput(sourceKey, viewport, imageSize) {
                    detectTapGestures(onTap = { onDismiss?.invoke() }, onDoubleTap = { point ->
                        transform = if (transform.scale > 1f) ImagePreviewTransform() else transform.transform(
                            viewport.width.toFloat(), viewport.height.toFloat(), imageWidth, imageHeight,
                            point.x, point.y, 3f, 0f, 0f,
                        )
                    })
                }
                .pointerInput(sourceKey, viewport, imageSize, pagingEnabled) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var multiple = false
                        var vertical = 0f
                        var horizontal = 0f
                        var accumulatedZoom = 1f
                        val canDismiss = transform.scale <= 1.01f && onDismiss != null
                        var verticalGesture: Boolean? = null
                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            accumulatedZoom *= zoom
                            multiple = multiple || event.changes.count { it.pressed } > 1
                            vertical += pan.y; horizontal += pan.x
                            if (abs(vertical) > viewConfiguration.touchSlop || abs(horizontal) > viewConfiguration.touchSlop || abs(accumulatedZoom - 1f) * minOf(viewport.width,viewport.height) / 2 > viewConfiguration.touchSlop) moved = true
                            if (event.changes.any { it.isConsumed }) break
                            if (pagingEnabled && !multiple && transform.scale <= 1.01f) {
                                if (verticalGesture == null && moved) verticalGesture = abs(vertical) > abs(horizontal)
                                // Lock the gesture's initial direction: horizontal goes to the
                                // pager; vertical remains here for pull-down dismissal.
                                if (verticalGesture != true) continue
                            }
                            if (moved) {
                                if (canDismiss && !multiple && transform.scale <= 1.01f) dismissDrag = vertical.coerceAtLeast(0f)
                                else if (centroid.isSpecified) {
                                    dismissDrag = 0f
                                    transform = transform.transform(viewport.width.toFloat(),viewport.height.toFloat(),imageWidth,imageHeight,centroid.x,centroid.y,zoom,pan.x,pan.y)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (canDismiss && !multiple && (!pagingEnabled || verticalGesture == true) && vertical > 100.dp.toPx() && vertical > abs(horizontal)) onDismiss?.invoke()
                        dismissDrag = 0f
                    }
                }
                .graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.x
                    translationY = transform.y + dismissDrag
                },
        )
        if (transform.scale > 1f) {
            TextButton(onClick = { transform = ImagePreviewTransform() }, modifier = Modifier.align(Alignment.BottomCenter)) {
                Text(stringResource(R.string.image_preview_reset, (transform.scale * 100).toInt()))
            }
        }
    }
}
