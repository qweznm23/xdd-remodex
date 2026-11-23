package com.remodex.mobile.ui.sidebar

import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.outlined.Add
import com.remodex.mobile.ui.theme.composerToolColor
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.R
import com.remodex.mobile.ui.turn.WeeklyQuotaMoreMenu

enum class SidebarListTab {
    Home, Projects,
}

/** Shared circular icon control used in sidebar header and turn toolbar. */
@Composable
fun RemodexCircleIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size).semantics { this.contentDescription = contentDescription },
        content = icon,
    )
}

@Composable
fun SidebarBrandHeader(
    colors: SidebarColorPalette,
    navigation: @Composable () -> Unit,
    onMoreMenu: SidebarMoreMenuCallbacks,
    onOpenSettings: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var moreExpanded by remember { mutableStateOf(false) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { navigation() }
        SidebarHeaderIconButton(
            icon = { Icon(painterResource(LucideR.drawable.lucide_ic_search), null, Modifier.size(20.dp), tint = colors.primaryText) },
            contentDescription = stringResource(R.string.sidebar_search_hint), onClick = onSearch,
        )
        WeeklyQuotaMoreMenu(
            description = stringResource(R.string.sidebar_more_menu_cd),
            expanded = moreExpanded,
            setExpanded = { moreExpanded = it },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_archived_chats)) },
                onClick = {
                    moreExpanded = false
                    onMoreMenu.onOpenArchivedChats()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cd_refresh_thread_list)) },
                onClick = {
                    moreExpanded = false
                    onMoreMenu.onRefreshThreads()
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_refresh_cw),
                        contentDescription = null,
                    )
                },
            )
            onMoreMenu.openIndex?.let { open ->
                DropdownMenuItem(text = { Text(stringResource(R.string.session_all_projects)) }, onClick = { moreExpanded = false; open() })
            }
            onMoreMenu.toggleGroups?.let { toggle ->
                DropdownMenuItem(text = { Text(stringResource(if (onMoreMenu.allCollapsed) R.string.drawer_expand else R.string.drawer_collapse)) },
                    onClick = { moreExpanded = false; toggle() })
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.my_devices_title)) },
                onClick = {
                    moreExpanded = false
                    onMoreMenu.onOpenConnections()
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Devices,
                        contentDescription = null,
                    )
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.nav_settings)) },
                leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                onClick = { moreExpanded = false; onOpenSettings() },
            )
        }
    }
}

data class SidebarMoreMenuCallbacks(
    val onOpenArchivedChats: () -> Unit,
    val onRefreshThreads: () -> Unit,
    val onOpenConnections: () -> Unit,
    val toggleGroups: (() -> Unit)?,
    val allCollapsed: Boolean,
    val openIndex: (() -> Unit)?,
)

@Composable
private fun SidebarHeaderIconButton(
    icon: @Composable () -> Unit,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RemodexCircleIconButton(
        onClick = onClick,
        contentDescription = contentDescription,
        modifier = modifier,
        icon = icon,
    )
}

@Composable
fun SidebarListTabRow(
    selected: SidebarListTab,
    onSelected: (SidebarListTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal, modifier = modifier,
        containerColor = Color.Transparent, divider = {}) {
        SidebarListTab.entries.forEach { tab ->
            Tab(selected = selected == tab, onClick = { onSelected(tab) },
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                Text(stringResource(if (tab == SidebarListTab.Home) R.string.sidebar_tab_chats else R.string.sidebar_tab_projects),
                    Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
fun SidebarFloatingActionBar(
    terminalEnabled: Boolean,
    chatEnabled: Boolean,
    onTerminal: () -> Unit,
    onChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onTerminal, enabled = terminalEnabled) {
            Icon(painterResource(LucideR.drawable.lucide_ic_square_terminal), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sidebar_terminal))
        }
        FilledTonalButton(onClick = onChat, enabled = chatEnabled,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.composerToolColor(),
                contentColor = MaterialTheme.colorScheme.onSurface)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sidebar_new_chat))
        }
    }
}
