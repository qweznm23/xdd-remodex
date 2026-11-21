package com.remodex.mobile.ui.sidebar

import com.remodex.mobile.ui.theme.conversationColors
import com.remodex.mobile.ui.theme.composerToolColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.remodex.mobile.ui.theme.isAgentLightChrome

@Immutable
data class SidebarColorPalette(
    val background: Color,
    val surface: Color,
    val selectedRow: Color,
    val border: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val mutedText: Color,
    val accent: Color,
    val green: Color,
    val red: Color,
    val unread: Color,
    val unreadBackground: Color,
)

@Composable
fun rememberSidebarColorPalette(
    darkTheme: Boolean = !isAgentLightChrome(),
): SidebarColorPalette {
    val c = MaterialTheme.colorScheme
    return remember(darkTheme, c) {
        SidebarColorPalette(
            background = c.conversationColors().reading, surface = c.surface, selectedRow = c.composerToolColor(),
            border = c.outlineVariant, primaryText = c.onSurface, secondaryText = c.onSurfaceVariant,
            mutedText = c.onSurfaceVariant, accent = c.primary,
            green = if (darkTheme) Color(0xff80db90) else Color(0xff26743b),
            red = c.error, unread = if (darkTheme) Color(0xFF8CB8F4) else Color(0xFF356599), unreadBackground = c.primaryContainer,
        )
    }
}
