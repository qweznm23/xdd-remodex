package com.remodex.mobile.ui.turn

import com.remodex.mobile.ui.theme.LocalSheetClose
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatDetailSheet(
    title: String,
    onDismiss: () -> Unit,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    RemodexModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = true) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            action?.invoke()
            IconButton(onClick = LocalSheetClose.current) {
                Icon(Icons.Default.Close, stringResource(R.string.turn_preview_close))
            }
        }
        content()
    }
}
