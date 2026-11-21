package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R

@Composable
internal fun ReasoningSummaryRow(parts: List<String>, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    TimelineActionRow(
        modifier = modifier,
        onClick = { open = true },
        trailing = { Icon(Icons.Rounded.ChevronRight, stringResource(R.string.reasoning_summary), Modifier.size(18.dp)) },
    ) {
        Text(parts.last(), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    if (open) {
        ChatDetailSheet(stringResource(R.string.reasoning_summary), onDismiss = { open = false }) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                items(parts.size) { index ->
                    SelectionContainer {
                        Text(parts[parts.lastIndex - index], Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
