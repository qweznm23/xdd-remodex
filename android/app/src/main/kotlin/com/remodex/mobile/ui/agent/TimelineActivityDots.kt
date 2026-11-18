package com.remodex.mobile.ui.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Animation state is read only during drawing, never while composing the conversation. */
@Composable
internal fun TimelineActivityDots(color: Color) {
    // Pause the clock behind the full-width drawer without changing the row's geometry.
    val phase = if (com.remodex.mobile.ui.turn.LocalTimelineRenderingEnabled.current) {
        val transition = rememberInfiniteTransition(label = "timeline-activity")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing), RepeatMode.Restart),
            label = "timeline-activity-phase",
        )
    } else remember { mutableFloatStateOf(0f) }
    Canvas(Modifier.size(width = 28.dp, height = 14.dp)) {
        repeat(3) { index ->
            val pulse = (sin(phase.value - index * 0.7f) + 1f) / 2f
            drawCircle(
                color = color.copy(alpha = 0.35f + 0.55f * pulse),
                radius = 2.3.dp.toPx(),
                center = Offset((4 + index * 10).dp.toPx(), size.height / 2 - pulse * 2.dp.toPx()),
            )
        }
    }
}
