package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TimelineActionRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onClick == null) {
        Row(modifier.fillMaxWidth().padding(vertical = 2.dp), Arrangement.spacedBy(6.dp), Alignment.CenterVertically) {
            leading?.invoke()
            Box(Modifier.weight(1f)) { content() }
            trailing?.invoke()
        }
        return
    }
    ListItem(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { role = Role.Button },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        leadingContent = leading,
        trailingContent = trailing,
        content = content,
    )
}
