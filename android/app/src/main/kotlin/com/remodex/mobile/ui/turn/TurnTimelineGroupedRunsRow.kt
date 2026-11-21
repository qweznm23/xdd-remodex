package com.remodex.mobile.ui.turn

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CommandExecutionDetails

/**
 * Collapsed header + optional stack of [TurnMessageRow] for a long run of identical tool rows.
 */
@Composable
internal fun TurnTimelineGroupedRunsRow(
    groupKey: String,
    collapsedTitle: String,
    messages: List<CodexMessage>,
    commandExecutionDetailsByItemId: Map<String, CommandExecutionDetails>,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(groupKey) { mutableStateOf(false) }
    val expandLabel = stringResource(R.string.turn_timeline_group_toggle_expand_cd, messages.size)
    val collapseLabel = stringResource(R.string.turn_timeline_group_toggle_collapse_cd)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TimelineDisclosureButton(collapsedTitle, expanded, { expanded = !expanded }, if (expanded) collapseLabel else expandLabel)
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (msg in messages) {
                    TurnMessageRow(
                        message = msg,
                        commandExecutionDetails = msg.itemId?.let { commandExecutionDetailsByItemId[it] },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CommandGroupRow(
    groupKey: String,
    messages: List<CodexMessage>,
    commandExecutionDetailsByItemId: Map<String, CommandExecutionDetails>,
) {
    var open by rememberSaveable(groupKey) { mutableStateOf(false) }
    val title = stringResource(R.string.turn_timeline_group_executed_commands)
    TimelineActionRow(
        onClick = { open = true },
        leading = { Icon(Icons.Outlined.Terminal, null, Modifier.size(18.dp)) },
        trailing = { Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp)) },
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
    if (open) {
        ChatDetailSheet(title, onDismiss = { open = false }) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(messages, key = { it.id }) { message ->
                    TurnCommandExecutionCard(
                        message = message,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        details = message.itemId?.let(commandExecutionDetailsByItemId::get),
                    )
                }
            }
        }
    }
}
