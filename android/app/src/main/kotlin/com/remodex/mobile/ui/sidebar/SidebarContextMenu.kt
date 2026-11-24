package com.remodex.mobile.ui.sidebar

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.remodex.mobile.ui.theme.QuotaAnchoredMenu

/** The native row owns click, long-click and scroll arbitration. Only observe the anchor position. */
internal class SidebarContextMenuState {
    var press: IntOffset? = null
    var origin: IntOffset? = null
        private set
    var visibility by mutableStateOf<MutableTransitionState<Boolean>?>(null)
        private set

    fun open() {
        origin = press
        val transition = visibility ?: MutableTransitionState(false).also { visibility = it }
        transition.targetState = true
    }
    fun close() { visibility?.targetState = false }
    fun finish(transition: MutableTransitionState<Boolean>) {
        if (visibility === transition && transition.isIdle && !transition.targetState) visibility = null
    }
}

internal fun Modifier.observeMenuPress(menu: SidebarContextMenuState) = pointerInput(menu) {
    awaitEachGesture {
        menu.press = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position.round()
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
            } while (event.changes.any { it.pressed })
        } finally {
            menu.press = null
        }
    }
}

@Composable
internal fun BoxScope.SidebarContextMenu(
    menu: SidebarContextMenuState,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = menu.visibility ?: return
    val transition = rememberTransition(visibility, label = "Sidebar context menu")
    LaunchedEffect(visibility.isIdle, visibility.targetState) { menu.finish(visibility) }
    val origin = menu.origin
    val anchor = if (origin == null) Modifier.align(Alignment.Center)
        else Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset { origin }
    Box(anchor) {
        QuotaAnchoredMenu(transition, menu::close, panelWidth = 216.dp, content = content)
    }
}
