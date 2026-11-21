package com.remodex.mobile.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.remodex.mobile.R
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * Navigation belongs to the page surface in both display modes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun remodexScreenTopAppBarColors() =
    TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.conversationColors().reading,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        scrolledContainerColor = MaterialTheme.colorScheme.conversationColors().reading,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemodexPageHeader(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    val textHeight = with(density) {
        MaterialTheme.typography.titleMedium.lineHeight.toDp() +
            if (subtitle.isNullOrBlank()) 0.dp else MaterialTheme.typography.bodySmall.lineHeight.toDp()
    }
    TopAppBar(
        expandedHeight = maxOf(56.dp, textHeight + 12.dp),
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        actions = actions,
        colors = remodexScreenTopAppBarColors(),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_navigate_back))
            }
        },
    )
}
