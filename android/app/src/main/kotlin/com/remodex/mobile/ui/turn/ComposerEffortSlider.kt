package com.remodex.mobile.ui.turn

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import kotlin.math.min
import kotlin.math.roundToInt

/** Preserve the service's ordering if it introduces an unfamiliar level. */
internal fun sliderEfforts(state: TurnComposerRuntimeSelectorState): List<TurnComposerRuntimeOption> {
    val choices = state.options.filter { it.enabled && it.id != TURN_COMPOSER_RUNTIME_AUTO_ID }.distinctBy { it.id }
    val ranks = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
    fun rank(id: String) = ranks.indexOf(when (val key = id.lowercase().replace('-', '_')) {
        "mid" -> "medium"
        "extra_high" -> "xhigh"
        else -> key
    })
    return if (choices.all { rank(it.id) >= 0 }) choices.sortedBy { rank(it.id) } else choices
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerEffortSlider(
    state: TurnComposerRuntimeSelectorState,
    enabled: Boolean,
    pendingEffort: String?,
    onSelect: (TurnComposerRuntimeOption) -> Unit,
) {
    val options = remember(state.options) { sliderEfforts(state) }
    val selected = options.indexOfFirst { it.id == (pendingEffort ?: state.selected.id) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val value = drag ?: selected.coerceAtLeast(0).toFloat()
    val current = if (drag == null && selected < 0) state.selected else options.getOrNull(value.roundToInt())
    val label = stringResource(R.string.composer_effort_label)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(current?.label ?: stringResource(R.string.composer_effort_unavailable),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (options.size < 2) return@Column
        val last = options.lastIndex
        val active = enabled && state.enabled
        fun commit(index: Int) {
            if (active && index != selected) onSelect(options[index])
        }
        val position by animateFloatAsState(value, if (drag == null) tween(140) else snap(), label = "effort")
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val colors = MaterialTheme.colorScheme
        val foreground = if (active || pendingEffort != null) colors.primary else colors.onSurface.copy(alpha = .38f)
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val h = min(40.dp.toPx(), size.width)
                val end = size.width - h / 2
                fun x(fraction: Float) = h / 2 + (end - h / 2) * (if (rtl) 1f - fraction else fraction)
                val hasSelection = selected >= 0 || drag != null
                val thumb = x(position / last)
                drawRoundRect(colors.surfaceContainerHighest, Offset(0f, center.y - h / 2),
                    Size(size.width, h), CornerRadius(h / 2))
                if (focused) {
                    val border = 2.dp.toPx()
                    drawRoundRect(colors.primary, Offset(border / 2, center.y - h / 2 + border / 2),
                        Size(size.width - border, h - border), CornerRadius((h - border) / 2), style = Stroke(border))
                }
                val radius = h / 2 - 4.dp.toPx()
                val start = x(0f)
                val fillWidth = kotlin.math.abs(start - thumb) + radius * 2
                if (hasSelection) drawRoundRect(foreground, Offset(min(start, thumb) - radius, center.y - radius),
                    Size(fillWidth, radius * 2), CornerRadius(radius))
                for (i in 0..last) {
                    drawCircle(if (hasSelection && i <= position) colors.onPrimary else colors.onSurfaceVariant,
                        2.dp.toPx(), Offset(x(i.toFloat() / last), center.y))
                }
                if (hasSelection) drawCircle(colors.onPrimary, radius * .69f, Offset(thumb, center.y))
            }
            Slider(
                value = value,
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    val index = drag?.roundToInt()
                    drag = null
                    if (index != null) commit(index)
                },
                interactionSource = interaction,
                enabled = active,
                valueRange = 0f..last.toFloat(),
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = label
                    stateDescription = current?.label.orEmpty()
                    progressBarRangeInfo = ProgressBarRangeInfo(value.roundToInt().toFloat(), 0f..last.toFloat(), last - 1)
                    if (active) setProgress { desired ->
                        commit(desired.roundToInt().coerceIn(0, last))
                        true
                    }
                }.onPreviewKeyEvent {
                    val delta = when (it.key) {
                        Key.DirectionRight -> if (rtl) -1 else 1
                        Key.DirectionLeft -> if (rtl) 1 else -1
                        Key.DirectionUp -> 1
                        Key.DirectionDown -> -1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (!active) return@onPreviewKeyEvent false
                    if (it.type == KeyEventType.KeyDown) commit((selected + delta).coerceIn(0, last))
                    true
                },
                thumb = { Spacer(Modifier.size(40.dp)) },
                track = { Spacer(Modifier.fillMaxWidth().height(36.dp)) },
            )
        }
    }
}
