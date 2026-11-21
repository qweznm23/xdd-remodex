package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R

@Composable
internal fun ComposerModelSheet(
    runtime: TurnComposerRuntimeControlsState,
    enabled: Boolean,
    pendingEffort: String?,
    saving: Boolean,
    error: String?,
    onSelectModel: (TurnComposerRuntimeOption) -> Unit,
    onSelectEffort: (TurnComposerRuntimeOption) -> Unit,
    onSelectTier: (TurnComposerRuntimeOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val model = runtime.model
    val options = remember(model.options, model.enabled) { composerModelPopupOptions(model).distinctBy { it.id } }
    val selectedIndex = remember(options, model.selected.id) { options.indexOfFirst { it.id == model.selected.id } }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex.coerceAtLeast(0))
    val tiers = runtime.serviceTier
    val fast = tiers.options.firstOrNull { it.id == "fast" }
    val normal = tiers.options.firstOrNull { it.id == TURN_COMPOSER_RUNTIME_AUTO_ID }
    ChatDetailSheet(
        title = stringResource(R.string.composer_picker_title),
        onDismiss = onDismiss,
        action = {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        },
    ) {
        if (error != null) {
            Text(error, Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        val adjustments: @Composable () -> Unit = {
            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            if (fast != null && normal != null) {
                val checked = tiers.selected.id == fast.id
                val target = if (checked) normal else fast
                val canToggle = enabled && tiers.enabled && target.enabled
                Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = canToggle,
                    role = Role.Switch, onValueChange = { onSelectTier(target) })
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.onboarding_feature_fast_title), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge)
                    Switch(checked, onCheckedChange = null, enabled = canToggle)
                }
            }
            key(model.selected.id, runtime.reasoningEffort.options) {
                ComposerEffortSlider(runtime.reasoningEffort, enabled && model.enabled,
                    pendingEffort, onSelectEffort)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Keep the model list reachable even with a narrow landscape window or large text.
            val pinControls = maxHeight >= 240.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val listHeightLimit = minOf(180.dp, maxHeight * 0.45f)
            Column(Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f, fill = false).then(
                        if (pinControls) Modifier.heightIn(max = listHeightLimit) else Modifier
                    ),
                    contentPadding = PaddingValues(bottom = if (pinControls) 4.dp else 12.dp),
                ) {
                    if (model.selected.enabled && selectedIndex < 0) item {
                        Text(stringResource(R.string.composer_current_model, model.selected.label),
                            Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    }
                    items(options, key = { it.id }, contentType = { "model" }) { option ->
                        ModelChoiceRow(
                            option = option,
                            selected = option.id == model.selected.id && option.id != TURN_COMPOSER_RUNTIME_AUTO_ID,
                            enabled = enabled && option.enabled,
                            onSelect = onSelectModel,
                        )
                    }
                    if (!pinControls) item { adjustments() }
                }
                if (pinControls) {
                    adjustments()
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelChoiceRow(
    option: TurnComposerRuntimeOption,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (TurnComposerRuntimeOption) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton) {
                if (!selected) onSelect(option)
            }
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            option.detail?.let { detail ->
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (option.id != TURN_COMPOSER_RUNTIME_AUTO_ID) {
            RadioButton(selected, onClick = null, enabled = enabled)
        }
    }
}
