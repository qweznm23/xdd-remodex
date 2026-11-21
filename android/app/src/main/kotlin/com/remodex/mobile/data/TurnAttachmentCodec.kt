package com.remodex.mobile.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import com.remodex.mobile.core.model.CodexImageAttachment
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal object TurnAttachmentCodec {
    private const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
    private const val THUMBNAIL_SIDE = 70
    private const val JPEG_QUALITY = 80

    fun attachmentFromHistorySource(sourceUrl: String?): CodexImageAttachment? {
        val normalizedSource = sourceUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val payloadDataUrl =
            if (normalizedSource.startsWith("data:image", ignoreCase = true)) {
                normalizedSource
            } else {
                null
            }
        val thumbnailBase64 =
            payloadDataUrl
                ?.let(::decodeDataUriImageData)
                ?.let(::makeThumbnailBase64JPEG)
                .orEmpty()
        return CodexImageAttachment(
            thumbnailBase64JPEG = thumbnailBase64,
            payloadDataURL = payloadDataUrl,
            sourceURL = normalizedSource,
        )
    }

    fun decodeDataUriImageData(dataUri: String): ByteArray? {
        val commaIndex = dataUri.indexOf(',')
        if (commaIndex <= 0) return null
        val metadata = dataUri.substring(0, commaIndex).lowercase()
        if (!metadata.startsWith("data:image") || !metadata.contains(";base64")) return null
        val base64Part = dataUri.substring(commaIndex + 1)
        if (base64Part.length > encodedBase64Limit(MAX_ATTACHMENT_BYTES)) return null
        return runCatching { Base64.decode(base64Part, Base64.DEFAULT) }.getOrNull()
            ?.takeIf { it.size <= MAX_ATTACHMENT_BYTES }
    }

    private fun makeThumbnailBase64JPEG(imageData: ByteArray): String? {
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size) ?: return null
        val thumbnail = Bitmap.createBitmap(THUMBNAIL_SIDE, THUMBNAIL_SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(thumbnail)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val scale =
            max(
                THUMBNAIL_SIDE.toFloat() / bitmap.width.toFloat(),
                THUMBNAIL_SIDE.toFloat() / bitmap.height.toFloat(),
            )
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val left = ((THUMBNAIL_SIDE - scaledWidth) / 2f).roundToInt()
        val top = ((THUMBNAIL_SIDE - scaledHeight) / 2f).roundToInt()
        val destination = Rect(left, top, left + scaledWidth.roundToInt(), top + scaledHeight.roundToInt())
        canvas.drawBitmap(bitmap, null, destination, paint)
        bitmap.recycle()
        val jpegData =
            thumbnail.compressToJpeg(JPEG_QUALITY).also {
                thumbnail.recycle()
            } ?: return null
        return Base64.encodeToString(jpegData, Base64.NO_WRAP)
    }

    private fun Bitmap.compressToJpeg(quality: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        if (!compress(Bitmap.CompressFormat.JPEG, quality, out)) {
            return null
        }
        return out.toByteArray()
    }

    private fun encodedBase64Limit(decodedBytes: Int): Int =
        ((decodedBytes + 2) / 3) * 4 + 128

}
