package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remodex.mobile.ui.theme.composerToolColor
import com.composables.icons.lucide.R as LucideR
import kotlinx.coroutines.launch

/** Compact footnote / unified chip icon size. */
internal val ComposerFootnoteIconDp = 13.dp

internal fun compactModelStickerForUnifiedChip(option: TurnComposerRuntimeOption): String {
    val t = option.label.trim()
    val lowered = t.lowercase()
    if (lowered.startsWith("gpt-")) {
        val rest = t.drop(4).trimStart()
        return rest.substringBefore(' ').substringBefore('|').takeIf { it.isNotBlank() } ?: t
    }
    return if (t.length <= 16) {
        t
    } else {
        t.take(14) + "\u2026"
    }
}

internal fun reasoningOrSpeedCompactCaption(runtimeControls: TurnComposerRuntimeControlsState): String =
    when {
        runtimeControls.reasoningEffort.enabled || runtimeControls.reasoningEffort.selected.id != TURN_COMPOSER_RUNTIME_AUTO_ID -> runtimeControls.reasoningEffort.selected.label
        else -> runtimeControls.serviceTier.selected.label
    }.trim()

internal fun unifiedModelRuntimeChipLabel(runtimeControls: TurnComposerRuntimeControlsState): String {
    val m = compactModelStickerForUnifiedChip(runtimeControls.model.selected)
    val r = reasoningOrSpeedCompactCaption(runtimeControls)
    return if (r.isBlank()) m else "$m $r"
}

/** Only server-backed options are selectable; the chip may still show a historical model. */
internal fun composerModelPopupOptions(state: TurnComposerRuntimeSelectorState): List<TurnComposerRuntimeOption> =
    state.options.map { it.copy(enabled = it.enabled && state.enabled) }

@Composable
internal fun UnifiedComposerModelRuntimeChip(
    runtimeControls: TurnComposerRuntimeControlsState,
    onOpenModelPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val showFastModeIcon = runtimeControls.serviceTier.selected.id != TURN_COMPOSER_RUNTIME_AUTO_ID
    AssistChip(
        onClick = {
            keyboard?.hide()
            onOpenModelPicker()
        },
        modifier = modifier.widthIn(max = 152.dp),
        shape = MaterialTheme.shapes.extraLarge,
        border = null,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.composerToolColor(),
            labelColor = MaterialTheme.colorScheme.onSurface,
            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        label = {
            Text(unifiedModelRuntimeChipLabel(runtimeControls), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingIcon = if (showFastModeIcon) {
            { Icon(painterResource(LucideR.drawable.lucide_ic_zap), null, Modifier.size(ComposerFootnoteIconDp)) }
        } else null,
        trailingIcon = {
            Icon(painterResource(LucideR.drawable.lucide_ic_chevron_down), null, Modifier.size(ComposerFootnoteIconDp))
        },
    )
}

@Composable
internal fun ComposerModelSheetHost(
    expanded: Boolean,
    onDismiss: () -> Unit,
    runtimeControls: TurnComposerRuntimeControlsState,
    onSelectModel: suspend (TurnComposerRuntimeOption) -> Unit,
    onSelectReasoningEffort: suspend (TurnComposerRuntimeOption) -> Unit,
    onSelectServiceTier: suspend (TurnComposerRuntimeOption) -> Unit,
) {
    var saving by remember { mutableStateOf(false) }
    var pendingEffort by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun select(option: TurnComposerRuntimeOption, effort: Boolean = false, change: suspend (TurnComposerRuntimeOption) -> Unit) {
        if (saving || !option.enabled) return
        saving = true
        pendingEffort = option.id.takeIf { effort }
        error = null
        scope.launch {
            try {
                reportSettingFailure({ error = it }) { change(option) }
            } finally {
                saving = false
                pendingEffort = null
            }
        }
    }

    if (expanded) {
        ComposerModelSheet(
            runtime = runtimeControls,
            enabled = !saving,
            pendingEffort = pendingEffort,
            saving = saving,
            error = error,
            onSelectModel = { select(it, change = onSelectModel) },
            onSelectEffort = { select(it, effort = true, change = onSelectReasoningEffort) },
            onSelectTier = { select(it, change = onSelectServiceTier) },
            onDismiss = onDismiss,
        )
    }
}
