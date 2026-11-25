package com.remodex.mobile.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object RemodexPopupChrome {
    val PopupShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val PanelShape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)

    @Composable
    fun surfaceColor(): Color =
        if (isAgentLightChrome()) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh

    @Composable
    fun borderWidth(): Dp = 1.dp

    @Composable
    fun borderColor(accent: Boolean = false): Color =
        if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    @Composable
    fun borderStroke(accent: Boolean = false): BorderStroke =
        BorderStroke(borderWidth(), borderColor(accent))

    @Composable
    fun shadowElevation(panel: Boolean = false) =
        if (isAgentLightChrome()) {
            if (panel) 10.dp else 8.dp
        } else {
            if (panel) 4.dp else 3.dp
        }

    @Composable
    fun tonalElevation() = 0.dp
}

@Composable
fun RemodexPopupSurface(
    modifier: Modifier = Modifier,
    panel: Boolean = false,
    accentBorder: Boolean = false,
    content: @Composable () -> Unit,
) {
    val shape = if (panel) RemodexPopupChrome.PanelShape else RemodexPopupChrome.PopupShape
    Surface(
        modifier = modifier.border(RemodexPopupChrome.borderStroke(accentBorder), shape),
        shape = shape,
        color = RemodexPopupChrome.surfaceColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = RemodexPopupChrome.tonalElevation(),
        shadowElevation = RemodexPopupChrome.shadowElevation(panel),
        content = content,
    )
}

@Composable
internal fun RemodexDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    panelWidth: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    if (!expanded && visibility.isIdle) return
    RemodexAnchoredMenu(
        transition = rememberTransition(visibility, label = "Action menu"),
        onDismiss = onDismissRequest,
        panelWidth = panelWidth,
        content = content,
    )
}

internal val LocalSheetClose = staticCompositionLocalOf<() -> Unit> { error("Close action requires a modal sheet") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemodexModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    sheetGesturesEnabled: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismissRequest)
    var hideJob by remember(sheetState) { mutableStateOf<Job?>(null) }
    val close = {
        if (hideJob?.isActive != true) {
            hideJob = scope.launch {
                sheetState.hide()
                if (!sheetState.isVisible) dismiss()
            }
        }
    }
    MaterialTheme(motionScheme = RemodexPopupMotion) {
        ModalBottomSheet(
            modifier = modifier,
            onDismissRequest = {
                // Native Back and scrim dismissal own their own hide operation.
                hideJob?.cancel()
                dismiss()
            },
            sheetState = sheetState,
            sheetGesturesEnabled = sheetGesturesEnabled,
            containerColor = MaterialTheme.colorScheme.conversationColors().dialog,
            scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = if (isAgentLightChrome()) 0.32f else 0.48f),
        ) {
            CompositionLocalProvider(LocalSheetClose provides close) { content() }
        }
    }
}
