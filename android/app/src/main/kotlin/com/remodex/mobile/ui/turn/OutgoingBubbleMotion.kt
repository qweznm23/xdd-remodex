package com.remodex.mobile.ui.turn

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

private const val BubbleEnterMillis = 190

/** A local send owns one short time window. Scrolling back or restoring history cannot restart it. */
@Composable
internal fun outgoingBubbleMotion(startNanos: Long?): Modifier {
    if (startNanos == null) return Modifier
    val recent = remember(startNanos) {
        ValueAnimator.areAnimatorsEnabled() && (System.nanoTime() - startNanos) in 0..600_000_000L
    }
    if (!recent) return Modifier
    var played by rememberSaveable(startNanos) { mutableStateOf(false) }
    val playOnEntry = remember(startNanos) { !played }
    var finished by remember(startNanos) { mutableStateOf(false) }
    if (!playOnEntry || finished) return Modifier
    val progress = remember(startNanos) { Animatable(0f) }
    LaunchedEffect(progress) {
        played = true
        progress.animateTo(1f, tween(BubbleEnterMillis, easing = EaseOutCubic))
        finished = true
    }
    return Modifier.graphicsLayer {
        val remaining = 1f - progress.value
        alpha = 1f - 0.48f * remaining
        translationY = 7.dp.toPx() * remaining
        scaleX = 1f - 0.018f * remaining
        scaleY = scaleX
        transformOrigin = TransformOrigin(1f, 1f)
    }
}
