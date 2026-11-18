package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.ConversationMediaType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexImageAttachment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity

/** Inline placeholders use the painter's intrinsic size, ignoring an Image height constraint. */
private class CompactInlineImagePainter(private val delegate: Painter, private val edge: Float) : Painter() {
    override val intrinsicSize: Size
        get() {
            val original = delegate.intrinsicSize
            if (!original.width.isFinite() || !original.height.isFinite() || original.width <= 0 || original.height <= 0) return Size(edge, edge)
            val factor = minOf(edge * 1.67f / original.width, edge / original.height)
            return Size(original.width * factor, original.height * factor)
        }
    override fun DrawScope.onDraw() {
        with(delegate) { draw(size) }
    }
}

internal object ConversationMarkdownImages : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData {
        if (ConversationMediaType.isVideo(link)) {
            val open = rememberOpenConversationVideo(link)
            return ImageData(painter = rememberVideoCardPainter(link),
                contentDescription = stringResource(R.string.conversation_video_open) + ": " + ConversationMediaType.filename(link),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp,max = 100.dp).clickable(onClick = open),
                contentScale = ContentScale.Fit)
        }
        val state = rememberConversationImage(link, 512)
        val edge = with(LocalDensity.current) { 96.dp.toPx() }
        val compactPainter = remember(state.painter, edge) { CompactInlineImagePainter(state.painter, edge) }
        val open = rememberOpenConversationImage(CodexImageAttachment(id = link, thumbnailBase64JPEG = "", sourceURL = link))
        return ImageData(
            painter = compactPainter,
            contentDescription = stringResource(if (state.error != null) R.string.conversation_image_failed else R.string.conversation_image_preview),
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 112.dp).clickable {
                if (state.error != null) state.retry()
                open()
            },
            contentScale = ContentScale.Fit,
        )
    }
}
