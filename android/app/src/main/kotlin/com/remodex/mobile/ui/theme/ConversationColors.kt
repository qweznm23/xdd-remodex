package com.remodex.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Reading canvas, recessed message and raised controls are independent from the accent seed. */
@Immutable
internal data class ChatSurfaces(
    val reading: Color,
    val outgoing: Color,
    val raised: Color,
    val outgoingEdge: Color,
)

private val LightChatSurfaces = ChatSurfaces(Color(0xFFF9F9F7), Color(0xFFF0EFEC), Color.White, Color(0xFFE5E4E0))
private val DarkChatSurfaces = ChatSurfaces(Color(0xFF151515), Color(0xFF0D0D0D), Color(0xFF20201F), Color(0xFF353533))

internal fun chatSurfaces(light: Boolean) = if (light) LightChatSurfaces else DarkChatSurfaces

internal fun ColorScheme.chatLayers(light: Boolean): ChatSurfaces {
    val builtIn = chatSurfaces(light)
    return if (background == builtIn.reading && surface == builtIn.raised) builtIn
        else ChatSurfaces(background, surfaceContainerHigh, if (light) surfaceContainerLowest else surfaceContainer, outlineVariant)
}

@Immutable
internal data class ConversationColors(val reading: Color, val input: Color, val inputOutline: Color, val dialog: Color,
    val inputText: Color, val inputHint: Color)

internal fun ColorScheme.conversationColors(): ConversationColors {
    val light = background.luminance() >= 0.43f
    val layers = chatLayers(light)
    if (layers != chatSurfaces(light)) return ConversationColors(
        layers.reading, layers.raised, outlineVariant, layers.raised, onSurface, onSurfaceVariant)
    return ConversationColors(
        reading = layers.reading,
        dialog = layers.raised,
        input = layers.raised,
        inputText = if (light) Color(0xFF171715) else Color(0xFFF1F0EC),
        // The light placeholder is slightly darker than the reference to keep 4.5:1 on white.
        inputHint = if (light) Color(0xFF777570) else Color(0xFF97958D),
        inputOutline = if (light) Color(0xFFE7E7E5) else Color(0xFF444441),
    )
}

/** Neutral, recessed actions on the raised composer surface. */
internal fun ColorScheme.composerToolColor(): Color {
    val light = background.luminance() >= 0.43f
    if (chatLayers(light) != chatSurfaces(light)) return surfaceContainerHigh
    return if (light) LightChatSurfaces.outgoing else lerp(DarkChatSurfaces.raised, onSurface, 0.09f)
}
