package com.remodex.mobile.ui.turn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.R
import com.remodex.mobile.data.ComposerPrediction
import com.remodex.mobile.ui.theme.RemodexComposerCapsuleChrome
import com.remodex.mobile.ui.theme.RemodexDropdownMenu
import com.remodex.mobile.ui.theme.RemodexPopupChrome
import com.remodex.mobile.ui.theme.isAgentLightChrome
import com.remodex.mobile.ui.theme.composerInput
import com.remodex.mobile.ui.theme.conversationColors
import com.remodex.mobile.ui.theme.composerToolColor

internal enum class TurnVoicePhase {
    Idle,
    Recording,
    Transcribing,
}

internal val LocalComposerFocusTransfer = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/**
 * Composer J.2/J.6: testo + picker immagini + preview allegati.
 *
 * In the iOS/SwiftUI surface this is the prompt composer (`TurnComposer*` family); keep this type
 * as the single Android entry point for that responsibility. [composerEnvironment] hosts the
 * Swift-style secondary row (Local / access / branch / usage ring) when the pane has room for it.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TurnComposerBar(
    draft: String,
    compact: Boolean = false,
    queueEditFocusId: String? = null,
    onQueueEditFocused: () -> Unit = {},
    isFollowUp: Boolean = false,
    onExpand: () -> Unit = {},
    prediction: ComposerPrediction? = null,
    canFillPrediction: Boolean = false,
    onAcceptPrediction: () -> Boolean = { false },
    attachments: List<TurnComposerAttachment>,
    model: TurnComposerModel,
    isPlanModeEnabled: Boolean,
    runtimeControls: TurnComposerRuntimeControlsState = TurnComposerRuntimeControlsState(),
    mentionChips: List<ComposerMentionChipPayload> = emptyList(),
    autocomplete: TurnComposerAutocompleteState? = null,
    onDraftChange: (String) -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onSetPlanModeEnabled: (Boolean) -> Unit,
    onOpenModelPicker: () -> Unit = {},
    onSelectModel: suspend (TurnComposerRuntimeOption) -> Unit = {},
    onSelectReasoningEffort: suspend (TurnComposerRuntimeOption) -> Unit = {},
    onSelectAccessMode: (TurnComposerRuntimeOption) -> Unit = {},
    onSelectServiceTier: suspend (TurnComposerRuntimeOption) -> Unit = {},
    onRemoveAttachment: (String) -> Unit,
    onRetryAttachment: (String) -> Unit = {},
    onRemoveMentionChip: (ComposerMentionChipPayload) -> Unit = {},
    onSelectAutocomplete: (TurnComposerAutocompleteItem) -> Unit = {},
    onSend: () -> Unit,
    onStopTurn: () -> Unit = {},
    composerEnvironment: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var modelSheetOpen by remember { mutableStateOf(false) }
    ComposerModelSheetHost(
        expanded = modelSheetOpen,
        onDismiss = { modelSheetOpen = false },
        runtimeControls = runtimeControls,
        onSelectModel = onSelectModel,
        onSelectReasoningEffort = onSelectReasoningEffort,
        onSelectServiceTier = onSelectServiceTier,
    )
    val textInteractionSource = remember { MutableInteractionSource() }
    val actions = remember(model) { TurnComposerToolbarActionsBuilder.build(model, voiceUiEnabled = false) }
    val locks = remember(model) { model.deriveInteractionLocks() }
    val agentLightChrome = isAgentLightChrome()
    val colors = MaterialTheme.colorScheme
    val hint = stringResource(if (isFollowUp) R.string.turn_composer_reply_hint else R.string.turn_composer_hint)
    val inputStyle = MaterialTheme.typography.composerInput
    val chat = colors.conversationColors()
    val primaryActionColors = IconButtonDefaults.filledIconButtonColors(
        containerColor = colors.onSurface, contentColor = colors.surface,
    )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ComposerAttachmentStrip(
            attachments = attachments,
            onRemove = onRemoveAttachment,
            onRetry = onRetryAttachment,
        )
        ComposerMentionChipStrip(
            chips = mentionChips,
            onRemove = onRemoveMentionChip,
        )
        autocomplete?.takeIf { it.isVisible }?.let { state ->
            val autoShape = MaterialTheme.shapes.small
            val autoBg = RemodexPopupChrome.surfaceColor()
            val autoModifier =
                if (agentLightChrome) {
                    Modifier.border(RemodexPopupChrome.borderStroke(), autoShape)
                } else {
                    Modifier
                }
            Surface(
                modifier = autoModifier.heightIn(max = 280.dp),
                shape = autoShape,
                color = autoBg,
            ) {
                Column(
                    modifier =
                        Modifier
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.isLoading && state.items.isEmpty()) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(R.string.turn_runtime_loading),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    state.items.forEach { item ->
                        DropdownMenuItem(
                            onClick = { onSelectAutocomplete(item) },
                            leadingIcon = { Icon(painterResource(autocompleteIconRes(item.payload.kind)), null,
                                Modifier.size(18.dp), tint = autocompleteIconTint(item.payload.kind)) },
                            text = {
                                Column {
                                    Text(item.title, style = MaterialTheme.typography.bodyMedium)
                                    item.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            },
                        )
                    }
                }
            }
        }
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val completeFocusTransfer = LocalComposerFocusTransfer.current
        LaunchedEffect(completeFocusTransfer != null) {
            if (completeFocusTransfer != null) {
                focusRequester.requestFocus()
                keyboard?.show()
                completeFocusTransfer()
            }
        }
        var fieldState by remember { mutableStateOf(TextFieldValue(draft)) }
        var lastReportedText by remember(draft) { mutableStateOf(draft) }
        val fieldValue = if (queueEditFocusId != null) {
            TextFieldValue(draft, selection = TextRange(draft.length))
        } else fieldState.copy(text = draft)
        SideEffect {
            if (fieldValue.selection != fieldState.selection || fieldValue.composition != fieldState.composition) {
                fieldState = fieldValue
            }
        }
        LaunchedEffect(queueEditFocusId, actions.textFieldEnabled) {
            if (queueEditFocusId != null && actions.textFieldEnabled) {
                fieldState = fieldValue
                focusRequester.requestFocus()
                keyboard?.show()
                onQueueEditFocused()
            }
        }
        var focusAfterFill by remember { mutableStateOf(false) }
        LaunchedEffect(focusAfterFill) {
            if (focusAfterFill) {
                focusRequester.requestFocus()
                keyboard?.show()
                focusAfterFill = false
            }
        }
        // Keep ongoing IME composition and the first-send focus handoff in the editor.
        val reading = compact &&
            fieldValue.composition == null && completeFocusTransfer == null && queueEditFocusId == null && !focusAfterFill
        val showSend = !reading && (draft.isNotBlank() || model.draftText.isNotBlank() || attachments.isNotEmpty())
        val hasAction = actions.stopButtonVisible || actions.sendShowsProgress || showSend || prediction != null
        var showSuggestion by remember(prediction?.sourceTurnId, prediction?.text) { mutableStateOf(false) }
        if (showSuggestion && prediction != null) {
            val suggestion = prediction
            val fillEnabled = canFillPrediction && actions.textFieldEnabled && draft.isEmpty()
            ComposerPredictionSheet(suggestion.text, { showSuggestion = false }, onFill = {
                if (fillEnabled && onAcceptPrediction()) {
                    if (reading) onExpand()
                    fieldState = TextFieldValue(suggestion.text, selection = TextRange(suggestion.text.length))
                    focusAfterFill = true
                    showSuggestion = false
                }
            }, fillEnabled = fillEnabled)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { composerEnvironment() }
        RemodexComposerCapsuleChrome(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            val transition = updateTransition(targetState = !reading, label = "Composer layout")
            val expansion by transition.animateFloat(
                transitionSpec = { tween(if (targetState) 220 else 160, easing = EaseOutCubic) },
                label = "Text and model position",
            ) { expanded -> if (expanded) 1f else 0f }
            Box(Modifier.fillMaxWidth().padding(2.dp)) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Spacer(Modifier.width(48.dp - 40.dp * expansion))
                        if (reading) {
                            Box(
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .clickable(role = Role.Button) {
                                        onExpand()
                                        focusAfterFill = actions.textFieldEnabled
                                    },
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = draft.ifEmpty { prediction?.text?.takeIf { it.isNotBlank() } ?: hint },
                                    modifier = Modifier.padding(start = 7.dp, end = 4.dp),
                                    style = inputStyle,
                                    color = chat.inputHint,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            BasicTextField(
                                value = fieldValue,
                                onValueChange = { updated ->
                                    fieldState = updated
                                    if (updated.text != lastReportedText) {
                                        lastReportedText = updated.text
                                        if (compact) onExpand()
                                        onDraftChange(updated.text)
                                    }
                                },
                                modifier =
                                    Modifier
                                        .focusRequester(focusRequester)
                                        .weight(1f),
                                interactionSource = textInteractionSource,
                                enabled = actions.textFieldEnabled,
                                minLines = 1,
                                maxLines = 4,
                                textStyle = inputStyle.copy(color = chat.inputText),
                                cursorBrush = SolidColor(chat.inputText),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .padding(start = 7.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (draft.isEmpty()) {
                                            Text(
                                                text = hint,
                                                style = inputStyle,
                                                color = chat.inputHint,
                                            )
                                        }
                                        innerTextField()
                                    }
                                },
                            )
                        }
                        if (hasAction) Spacer(Modifier.width(48.dp * (1f - expansion)))
                    }
                    transition.AnimatedVisibility(
                        visible = { it },
                        enter = expandVertically(tween(220, easing = EaseOutCubic), expandFrom = Alignment.Top) +
                            fadeIn(tween(150, delayMillis = 50)),
                        exit = shrinkVertically(tween(160, easing = EaseOutCubic), shrinkTowards = Alignment.Top) +
                            fadeOut(tween(80)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .padding(start = 48.dp + 12.dp * (1f - expansion), end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                UnifiedComposerModelRuntimeChip(
                                    runtimeControls = runtimeControls,
                                    onOpenModelPicker = {
                                        modelSheetOpen = true
                                        onOpenModelPicker()
                                    },
                                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 152.dp),
                                )
                                if (isPlanModeEnabled) {
                                    ComposerPlanModeBadge(
                                        onClick = { onSetPlanModeEnabled(false) },
                                    )
                                }
                            }
                            if (hasAction) Spacer(Modifier.width(48.dp))
                        }
                    }
                }
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd).size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (actions.stopButtonVisible) {
                        FilledIconButton(
                            onClick = onStopTurn,
                            modifier = Modifier.size(36.dp),
                            enabled = actions.stopButtonEnabled,
                            shapes = IconButtonDefaults.shapes(),
                            colors = primaryActionColors,
                        ) {
                            Icon(Icons.Rounded.Stop, stringResource(R.string.turn_stop), Modifier.size(24.dp))
                        }
                    } else if (actions.sendShowsProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else if (showSend) {
                        val arrowScale = animateFloatAsState(
                            if (actions.sendButtonEnabled) 1f else 0.86f,
                            tween(150, easing = EaseOutCubic), label = "Send ready",
                        )
                        FilledIconButton(
                            onClick = onSend,
                            modifier = Modifier.size(36.dp),
                            colors = primaryActionColors,
                            enabled = actions.sendButtonEnabled,
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(painterResource(LucideR.drawable.lucide_ic_arrow_up),
                                stringResource(R.string.turn_send_cd), Modifier.size(20.dp).graphicsLayer {
                                    scaleX = arrowScale.value
                                    scaleY = arrowScale.value
                                })
                        }
                    } else if (prediction != null) {
                        IconButton(onClick = { showSuggestion = true }) {
                            Icon(Icons.Outlined.Lightbulb, stringResource(R.string.prediction_heading),
                                Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                // One touch target stays at the bottom start as the tools row unfolds.
                ComposerAddButton(
                    modifier = Modifier.align(Alignment.BottomStart),
                    enabled = !locks.runtimeControlsLocked,
                    isPlanModeEnabled = isPlanModeEnabled,
                    onPickImages = onPickImages,
                    onPickFiles = onPickFiles,
                    onTakePhoto = onTakePhoto,
                    onSetPlanModeEnabled = onSetPlanModeEnabled,
                )
            }
        }
    }
}

@Composable
private fun ComposerAddButton(
    modifier: Modifier,
    enabled: Boolean,
    isPlanModeEnabled: Boolean,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onTakePhoto: () -> Unit,
    onSetPlanModeEnabled: (Boolean) -> Unit,
) {
    var showActions by remember(enabled) { mutableStateOf(false) }
    Box(modifier) {
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = stringResource(R.string.turn_attachment_menu_library),
                onLongClickLabel = stringResource(R.string.attachment_more_options),
                onClick = onPickImages,
                onLongClick = { showActions = true },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.composerToolColor(),
                contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
                    else IconButtonDefaults.iconButtonColors().disabledContentColor,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_plus),
                        stringResource(R.string.attachment_shortcuts), Modifier.size(20.dp))
                }
            }
        }
        RemodexDropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.turn_attachment_menu_library)) },
                onClick = { showActions = false; onPickImages() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.turn_attachment_menu_file)) },
                onClick = { showActions = false; onPickFiles() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.turn_attachment_menu_camera)) },
                onClick = { showActions = false; onTakePhoto() },
            )
            HorizontalDivider()
            val planLabel = stringResource(R.string.turn_plan_mode_chip)
            DropdownMenuItem(
                text = { Text(if (isPlanModeEnabled) "$planLabel: on" else planLabel) },
                onClick = { showActions = false; onSetPlanModeEnabled(!isPlanModeEnabled) },
            )
        }
    }
}

@Composable
private fun autocompleteIconTint(kind: ComposerMentionKind): Color =
    when (kind) {
        ComposerMentionKind.Skill -> MaterialTheme.colorScheme.primary
        ComposerMentionKind.Plugin -> MaterialTheme.colorScheme.tertiary
        ComposerMentionKind.File -> MaterialTheme.colorScheme.onSurfaceVariant
        ComposerMentionKind.SlashCommand -> MaterialTheme.colorScheme.secondary
    }

private fun autocompleteIconRes(kind: ComposerMentionKind): Int =
    when (kind) {
        ComposerMentionKind.File -> LucideR.drawable.lucide_ic_file
        ComposerMentionKind.Skill -> LucideR.drawable.lucide_ic_square_asterisk
        ComposerMentionKind.Plugin -> LucideR.drawable.lucide_ic_blocks
        ComposerMentionKind.SlashCommand -> LucideR.drawable.lucide_ic_command
    }

@Composable
private fun ComposerPlanModeBadge(
    onClick: () -> Unit,
) {
    AssistChip(onClick = onClick, label = { Text(stringResource(R.string.turn_plan_mode_chip), maxLines = 1) })
}

@Composable
private fun ComposerMentionChipStrip(
    chips: List<ComposerMentionChipPayload>,
    onRemove: (ComposerMentionChipPayload) -> Unit,
) {
    if (chips.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            FilterChip(
                selected = false,
                onClick = {},
                enabled = true,
                label = {
                    val prefix =
                        when (chip.kind) {
                            ComposerMentionKind.File -> "@"
                            ComposerMentionKind.Skill -> "$"
                            ComposerMentionKind.Plugin -> "@"
                            ComposerMentionKind.SlashCommand -> "/"
                        }
                    Text(text = "$prefix${chip.displayLabel ?: chip.semanticValue}")
                },
                trailingIcon = {
                    IconButton(
                        onClick = { onRemove(chip) },
                        modifier = Modifier.size(18.dp),
                    ) {
                        Icon(
                            painter = painterResource(LucideR.drawable.lucide_ic_x),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                },
            )
        }
    }
}
