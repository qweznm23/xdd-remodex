package com.remodex.mobile.ui.sidebar

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR

@Composable
fun SidebarActionRow(
    label: String,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_plus),
            contentDescription = null,
            modifier = Modifier.size(21.dp),
        )
    },
) {
    DropdownMenuItem(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth(),
        text = { Text(label) },
        leadingIcon = {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else leading()
        },
    )
}

@Composable
fun SidebarCompactActionRow(
    label: String,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_plus),
            contentDescription = null,
            modifier = Modifier.size(21.dp),
        )
    },
) {
    SidebarActionRow(
        label = label,
        enabled = enabled,
        busy = busy,
        onClick = onClick,
        modifier = modifier,
        leading = leading,
    )
}
