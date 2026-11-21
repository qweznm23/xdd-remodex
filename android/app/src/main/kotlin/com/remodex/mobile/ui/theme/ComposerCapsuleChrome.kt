package com.remodex.mobile.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun isAgentLightChrome(): Boolean =
    MaterialTheme.colorScheme.background.luminance() >= 0.43f

@Composable
internal fun RemodexComposerCapsuleChrome(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val chat = colors.conversationColors()
    val shape = RoundedCornerShape(25.dp)
    Surface(
        modifier = modifier.dropShadow(
            shape,
            Shadow(radius = 6.dp, color = Color.Black.copy(alpha = if (isAgentLightChrome()) 0.10f else 0.25f),
                offset = DpOffset(0.dp, 2.dp)),
        ),
        shape = shape,
        color = chat.input,
        contentColor = colors.onSurface,
        border = BorderStroke(Dp.Hairline, chat.inputOutline),
        content = content,
    )
}
