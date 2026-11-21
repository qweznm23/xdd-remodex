package com.remodex.mobile.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.remodex.mobile.core.model.UserBubbleColor
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class UserBubbleColorPaletteTest {
    @Test
    fun readingSurfaceStaysNeutralAndBubbleTextRemainsReadable() {
        for (light in listOf(true, false)) {
            val base = if (light) lightColorScheme() else darkColorScheme()
            val scheme = sessionColorScheme(base, !light)
            val chat = scheme.conversationColors()
            val paper = chat.reading
            assertTrue(maxOf(paper.red, paper.green, paper.blue) - minOf(paper.red, paper.green, paper.blue) < .03f)
            assertEquals(1f, chat.input.alpha)
            if (light) {
                assertEquals(Color(0xFFF9F9F7), paper)
                assertEquals(Color.White, chat.input)
                assertEquals(Color(0xFFF0EFEC), UserBubbleColor.default.bubbleBackground(scheme, true))
            } else assertTrue(contrastRatio(paper, chat.input) >= 1.08f)
            assertEquals(if (light) Color(0xFFE7E7E5) else Color(0xFF444441), chat.inputOutline)
            assertTrue(contrastRatio(scheme.primary, paper) >= 3f)
            assertTrue(contrastRatio(scheme.primary, chat.input) >= 3f)
            assertTrue(contrastRatio(paper, scheme.onSurface) >= 4.5f)
            assertTrue(contrastRatio(chat.input, chat.inputText) >= 4.5f)
            assertTrue(contrastRatio(chat.input, chat.inputHint) >= 4.5f)
            assertTrue(contrastRatio(chat.input, scheme.onSurfaceVariant) >= 4.5f)
            assertTrue(contrastRatio(chat.dialog, scheme.onSurface) >= 4.5f)
            assertTrue(contrastRatio(chat.dialog, scheme.onSurfaceVariant) >= 4.5f)
            for (choice in UserBubbleColor.entries) {
                val fill = choice.bubbleBackground(scheme, light)
                assertTrue(contrastRatio(choice.bubbleForeground(scheme, light), fill) >= 4.5f)
            }
        }
    }

    @Test
    fun blackColorInvertsBetweenLightAndDarkChrome() {
        val lightScheme = lightColorScheme()
        val darkScheme = darkColorScheme()

        assertEquals(Color.Black, UserBubbleColor.black.bubbleBackground(lightScheme, true))
        assertEquals(Color.White, UserBubbleColor.black.bubbleForeground(lightScheme, true))
        assertEquals(Color.White, UserBubbleColor.black.bubbleBackground(darkScheme, false))
        assertEquals(Color.Black, UserBubbleColor.black.bubbleForeground(darkScheme, false))
    }

    @Test
    fun accentColorsMatchBubblePalette() {
        val lightScheme = lightColorScheme()
        UserBubbleColor.entries.forEach { color ->
            assertEquals(
                color.bubbleBackground(lightScheme, true),
                color.accentBackground(lightScheme, true),
            )
            assertEquals(
                color.bubbleForeground(lightScheme, true),
                color.accentForeground(lightScheme, true),
            )
        }
    }
}
