package com.remodex.mobile.ui.turn

import kotlin.math.min

internal data class ImagePreviewTransform(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    fun transform(
        viewportWidth: Float, viewportHeight: Float, imageWidth: Float, imageHeight: Float,
        centroidX: Float, centroidY: Float, zoom: Float, panX: Float, panY: Float,
    ): ImagePreviewTransform {
        if (viewportWidth <= 0 || viewportHeight <= 0 || !zoom.isFinite() || zoom <= 0) return this
        val nextScale = (scale * zoom).coerceIn(1f, 8f)
        if (nextScale == 1f) return ImagePreviewTransform()
        val ratio = nextScale / scale
        val validImage = imageWidth.isFinite() && imageHeight.isFinite() && imageWidth > 0 && imageHeight > 0
        val fit = if (validImage) min(viewportWidth / imageWidth, viewportHeight / imageHeight) else 1f
        val width = if (validImage) imageWidth * fit else viewportWidth
        val height = if (validImage) imageHeight * fit else viewportHeight
        val maxX = ((width * nextScale - viewportWidth) / 2).coerceAtLeast(0f)
        val maxY = ((height * nextScale - viewportHeight) / 2).coerceAtLeast(0f)
        return ImagePreviewTransform(
            nextScale,
            if (maxX == 0f) 0f else (x * ratio + (centroidX - viewportWidth / 2) * (1 - ratio) + panX).coerceIn(-maxX, maxX),
            if (maxY == 0f) 0f else (y * ratio + (centroidY - viewportHeight / 2) * (1 - ratio) + panY).coerceIn(-maxY, maxY),
        )
    }
}
