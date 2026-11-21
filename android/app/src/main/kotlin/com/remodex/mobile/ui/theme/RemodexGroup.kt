package com.remodex.mobile.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A group of related rows on a page. Nested controls use surfaceVariant. */
@Composable
internal fun RemodexGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.conversationColors().dialog, contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(content = content)
    }
}
