package com.remodex.mobile.ui.theme

import android.view.WindowManager.LayoutParams
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Expand from an anchor, optionally carrying its existing badge into the transition. */
@Composable
internal fun QuotaAnchoredMenu(
    transition: Transition<Boolean>,
    onDismiss: () -> Unit,
    panelWidth: Dp? = null,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expanded = transition.targetState
    val progress by transition.animateFloat(transitionSpec = {
        tween(if (targetState) 450 else 500, easing = CubicBezierEasing(.22f, .76f, .22f, 1f))
    }, label = "Anchor to menu") { if (it) 1f else 0f }
    val atSource = !expanded && !transition.currentState && !transition.isRunning
    if (atSource) return

    val density = LocalDensity.current
    val margin = with(density) { 8.dp.roundToPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val menuShape = remember(density, transition) {
        GenericShape { size, _ ->
            val p = progress
            val corner = with(density) { 20.dp.toPx() }
            addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(
                size.width / 2 * (1 - p) + corner * p,
                size.height / 2 * (1 - p) + corner * p,
            )))
        }
    }
    val position = remember(margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
                val left = x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                val top = anchorBounds.top.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
                origin = Offset((anchorBounds.center.x - left).toFloat(), (anchorBounds.center.y - top).toFloat())
                return IntOffset(left, top)
            }
        }
    }
    // The returning surface is visual only. Its old window must not catch a second tap on the source.
    val flags = LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        if (expanded) 0 else (LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE)
    val height = (LocalConfiguration.current.screenHeightDp.dp - 40.dp).coerceAtLeast(48.dp)
    Popup(position, onDismissRequest = onDismiss, properties = PopupProperties(flags = flags, inheritSecurePolicy = true)) {
        Box(Modifier.widthIn(max = panelWidth ?: 264.dp).heightIn(max = height)) {
            Surface(
                modifier = Modifier.graphicsLayer {
                    val p = progress
                    if (badge == null) alpha = p
                    val diameter = 36.dp.toPx()
                    scaleX = p + (1f - p) * diameter / size.width.coerceAtLeast(1f)
                    scaleY = p + (1f - p) * diameter / size.height.coerceAtLeast(1f)
                    translationX = (origin.x - size.width / 2) * (1f - p)
                    translationY = (origin.y - size.height / 2) * (1f - p)
                },
                shape = menuShape,
                color = MaterialTheme.colorScheme.conversationColors().dialog,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 4.dp,
            ) {
                val width = if (panelWidth == null) Modifier.width(IntrinsicSize.Max) else Modifier.width(panelWidth)
                Column(width.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)
                    .graphicsLayer { alpha = if (badge == null) 1f else ((progress - .24f) / .76f).coerceIn(0f, 1f) }, content = content)
            }
            if (badge != null) {
                Box(Modifier.size(36.dp).graphicsLayer {
                    translationX = origin.x - size.width / 2
                    translationY = origin.y - size.height / 2
                    alpha = (1f - progress * 3f).coerceIn(0f, 1f)
                }.clearAndSetSemantics {}, contentAlignment = Alignment.Center) { badge() }
            }
        }
    }
}
