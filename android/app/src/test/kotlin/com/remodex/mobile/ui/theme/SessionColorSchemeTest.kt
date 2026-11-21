package com.remodex.mobile.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class SessionColorSchemeTest {
    @Test fun appActionsAndContainersStayNeutralInBothModes() {
        for (dark in listOf(false, true)) {
            // Start with the standard colored Material defaults to expose any unmapped accent.
            val colors = sessionColorScheme(if (dark) darkColorScheme() else lightColorScheme(), dark)
            val roles = listOf(colors.primary, colors.secondary, colors.tertiary, colors.primaryContainer,
                colors.secondaryContainer, colors.tertiaryContainer, colors.primaryFixed, colors.secondaryFixed,
                colors.tertiaryFixed, colors.surfaceTint, colors.inversePrimary)
            for (color in roles) assertTrue(maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) < .02f)
            assertEquals(chatSurfaces(!dark).reading, colors.background)
            assertEquals(chatSurfaces(!dark).raised, colors.surface)
        }
    }

    @Test fun labelsAndReadingLinksKeepContrastInBothModes() {
        for (dark in listOf(false, true)) {
            val colors = sessionColorScheme(if (dark) darkColorScheme() else lightColorScheme(), dark)
            val pairs = listOf(
                colors.onPrimary to colors.primary,
                colors.onSecondaryContainer to colors.secondaryContainer,
                colors.onPrimaryContainer to colors.primaryContainer,
                colors.onErrorContainer to colors.errorContainer,
                colors.primary to colors.surfaceContainerHigh,
                colors.primary to colors.background,
            ) +
                listOf(colors.surface, colors.background).map { colors.readableLinkOn(it) to it }
            for ((ink, paper) in pairs) {
                val a = ink.luminance()
                val b = paper.luminance()
                assertTrue("Unreadable text for dark=$dark",
                    (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f) >= 4.5f)
            }
        }
    }

    @Test fun suggestionActionUsesChatInkOnBothSurfaces() {
        for (dark in listOf(false, true)) {
            val colors = sessionColorScheme(if (dark) darkColorScheme() else lightColorScheme(), dark).conversationColors()
            assertTrue(contrastRatio(colors.inputText, colors.input) >= 4.5f)
            assertTrue(contrastRatio(colors.inputText, colors.dialog) >= 4.5f)
            assertEquals(dark, colors.inputText.luminance() > colors.input.luminance())
        }
    }
}
