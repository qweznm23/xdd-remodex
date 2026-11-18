package com.remodex.mobile.ui.turn

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.ConversationMediaType

/** Markdown reserves inline media space from painter size, so a video needs its own card-sized painter. */
@Composable
internal fun rememberVideoCardPainter(source: String): Painter {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val icon = rememberVectorPainter(Icons.Default.PlayCircle)
    val measurer = rememberTextMeasurer()
    val title = stringResource(R.string.conversation_video_title)
    val name = ConversationMediaType.filename(source)
    val unit = with(density) { 1.dp.toPx() }
    val size = Size(288 * unit,96 * unit)
    val constraints = Constraints(maxWidth = (200 * unit).toInt())
    val titleLayout = measurer.measure(AnnotatedString(title),MaterialTheme.typography.labelMedium.copy(color = colors.onSurfaceVariant),maxLines = 1,constraints = constraints)
    val nameLayout = measurer.measure(AnnotatedString(name),MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),maxLines = 1,overflow = TextOverflow.Ellipsis,constraints = constraints)
    return remember(icon,size,titleLayout,nameLayout,colors) {
        object: Painter() {
            override val intrinsicSize = size
            override fun DrawScope.onDraw() {
                scale(this.size.width / intrinsicSize.width,this.size.height / intrinsicSize.height,pivot = Offset.Zero) {
                    drawRoundRect(colors.surfaceVariant,size = intrinsicSize,cornerRadius = CornerRadius(12 * unit))
                    translate(16 * unit,28 * unit) { with(icon) { draw(Size(40 * unit,40 * unit),colorFilter = ColorFilter.tint(colors.primary)) } }
                    drawText(titleLayout,topLeft = Offset(72 * unit,24 * unit))
                    drawText(nameLayout,topLeft = Offset(72 * unit,48 * unit))
                }
            }
        }
    }
}
