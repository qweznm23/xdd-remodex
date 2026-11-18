package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet

@Composable
internal fun ComposerPredictionPreview(text: String, onCollapse: () -> Unit, onExpand: () -> Unit, onFill: () -> Unit) {
    var overflows by remember(text) { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                onTextLayout = { overflows = it.hasVisualOverflow })
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (overflows) {
                    TextButton(onClick = onExpand) { Text(stringResource(R.string.prediction_expand)) }
                } else {
                    Text(stringResource(R.string.prediction_heading), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onFill) { Text(stringResource(R.string.prediction_fill)) }
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.prediction_collapse))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerPredictionSheet(text: String, onClose: () -> Unit, onFill: () -> Unit) {
    RemodexModalBottomSheet(onDismissRequest = onClose, sheetGesturesEnabled = true) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.82f).padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.prediction_heading), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.turn_preview_close)) }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
            }
            TextButton(modifier = Modifier.align(Alignment.End), onClick = onFill) { Text(stringResource(R.string.prediction_fill)) }
        }
    }
}
