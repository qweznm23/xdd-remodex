package com.remodex.mobile.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

// A detail page covers the parent. Only the parent travels at parallax speed.
internal object RemodexPageMotion {
    private val settle = CubicBezierEasing(0.16f, 0.76f, 0.24f, 1f)

    fun enter(back: Boolean, direction: Int): EnterTransition =
        slideInHorizontally(tween(300, easing = settle)) { width ->
            direction * if (back) -width * 3 / 10 else width
        }

    fun exit(back: Boolean, direction: Int): ExitTransition =
        slideOutHorizontally(tween(300, easing = settle)) { width ->
            direction * if (back) width else -width * 3 / 10
        }
}

@Composable
internal fun <T> RemodexPageStack(
    page: T,
    depth: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    AnimatedContent(
        targetState = page,
        modifier = modifier.clipToBounds(),
        transitionSpec = {
            val back = depth(targetState) < depth(initialState)
            (RemodexPageMotion.enter(back, direction) togetherWith RemodexPageMotion.exit(back, direction)).apply {
                targetContentZIndex = depth(targetState).toFloat()
            }
        },
        label = "Page stack",
    ) { destination ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.conversationColors().reading)) {
            content(destination)
        }
    }
}

/** Camera and terminal pages have exclusive owners. Move only the newly attached surface. */
@Composable
internal fun <T> Modifier.singleOwnerPageEntry(page: T, isBack: (T, T) -> Boolean): Modifier {
    val previous = remember { mutableStateOf(page) }
    val offset = remember(page) {
        val start = when {
            previous.value == page -> 0f
            isBack(previous.value, page) -> -40f
            else -> 64f
        }
        Animatable(start)
    }
    SideEffect { previous.value = page }
    LaunchedEffect(offset) {
        if (offset.value != 0f) offset.animateTo(0f, tween(270, easing = CubicBezierEasing(0.16f, 0.76f, 0.24f, 1f)))
    }
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    return graphicsLayer { translationX = sign * offset.value.dp.toPx() }
}

/** Shared timing for native sheet travel and anchored menus. Keep the backdrop quieter than the moving surface. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal object RemodexPopupMotion : MotionScheme {
    private val settle = CubicBezierEasing(0.18f, 0.82f, 0.24f, 1f)
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = tween(280, easing = settle)
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = tween(205, easing = settle)
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = tween(320, easing = settle)
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(170)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(115)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(200)
}
