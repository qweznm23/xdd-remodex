package com.remodex.mobile.ui.turn

import com.remodex.mobile.ui.theme.LocalSheetClose
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import com.remodex.mobile.ui.theme.conversationColors

@Composable
private fun RowScope.PredictionHeading() {
    Icon(Icons.Outlined.Lightbulb, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.conversationColors().inputHint)
    Text(stringResource(R.string.prediction_heading), Modifier.weight(1f).padding(start = 8.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.conversationColors().inputHint)
}

@Composable
private fun FillPredictionButton(onClick: () -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val ink = MaterialTheme.colorScheme.conversationColors().inputText
    TextButton(
        onClick = onClick, modifier = modifier, enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = ink, disabledContentColor = ink.copy(alpha = 0.38f)),
    ) { Text(label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerPredictionSheet(text: String, onClose: () -> Unit, onFill: () -> Unit, fillEnabled: Boolean = true) {
    RemodexModalBottomSheet(onDismissRequest = onClose, sheetGesturesEnabled = true) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.82f)
            .padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PredictionHeading()
                IconButton(onClick = LocalSheetClose.current) { Icon(Icons.Default.Close, stringResource(R.string.turn_preview_close)) }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.conversationColors().inputText) }
            }
            FillPredictionButton(onFill, stringResource(R.string.prediction_fill), Modifier.align(Alignment.End), enabled = fillEnabled)
        }
    }
}
