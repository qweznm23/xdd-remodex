package com.remodex.mobile.ui.sidebar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.remodex.mobile.R

private val StaticRunRotation: State<Float> = mutableFloatStateOf(0f)
private val LocalRunRotation = compositionLocalOf { StaticRunRotation }

/** One clock for the visible list; animation changes invalidate drawing only. */
@Composable
internal fun SidebarRunAnimation(enabled: Boolean, content: @Composable () -> Unit) {
    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    val rotation = remember { Animatable(0f) }
    val animate = enabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(animate) {
        if (animate) {
            // Cancellation freezes the current angle. Resume from there without jumping.
            rotation.snapTo(rotation.value % 360f)
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1_200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
        }
    }
    CompositionLocalProvider(LocalRunRotation provides rotation.asState(), content = content)
}

@Composable
fun SidebarThreadRunBadge(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.turn_timeline_command_running)
    val rotation = LocalRunRotation.current
    CircularProgressIndicator(
        progress = { 250f / 360f },
        modifier = modifier.size(20.dp).padding(3.dp)
            .graphicsLayer { rotationZ = rotation.value }
            .semantics { contentDescription = description },
        color = color,
        trackColor = Color.Transparent,
        strokeWidth = 2.dp,
    )
}
