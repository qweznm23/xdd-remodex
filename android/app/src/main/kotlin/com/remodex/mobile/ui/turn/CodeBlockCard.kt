package com.remodex.mobile.ui.turn

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.ui.theme.CodeFontFamily
import com.remodex.mobile.ui.theme.chatLayers
import com.remodex.mobile.ui.theme.isAgentLightChrome

@Composable
internal fun CodeBlockCard(raw: String, language: String?, expansionKey: String, onCopy: (String) -> Unit) {
    if (raw.isEmpty()) return
    var open by rememberSaveable(expansionKey) { mutableStateOf(false) }
    val preview = remember(raw) { inlineCodePreview(raw) }
    val label = language?.takeIf { it.isNotBlank() } ?: stringResource(R.string.turn_markdown_code_unknown)
    val codeStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFontFamily)
    val copy: @Composable () -> Unit = {
        IconButton(onClick = { onCopy(raw) }) {
            Icon(Icons.Default.ContentCopy, stringResource(R.string.turn_markdown_copy_code))
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.chatLayers(isAgentLightChrome()).outgoing,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                copy()
                IconButton(onClick = { open = true }) {
                    Icon(Icons.Default.OpenInFull, stringResource(R.string.turn_markdown_expand_code))
                }
            }
            Text(preview, Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                style = codeStyle, softWrap = false)
        }
    }
    if (open) {
        val lines = remember(raw) { raw.lines() }
        ChatDetailSheet(label, onDismiss = { open = false }, action = copy) {
            LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
                items(lines.size) { index ->
                    Text(lines[index], style = codeStyle)
                }
            }
        }
    }
}

/** Keep work bounded even for a single long minified line. The detail sheet and clipboard use the original. */
internal fun inlineCodePreview(raw: String): String {
    var end = minOf(raw.length, 1600)
    var breaks = 0
    for (index in 0 until end) {
        if (raw[index] == '\n' && ++breaks == 8) {
            end = index
            break
        }
    }
    if (end == raw.length) return raw
    if (end > 0 && raw[end - 1].isHighSurrogate()) end--
    return raw.take(end).trimEnd() + "\n…"
}
