package com.remodex.mobile.ui.settings

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.remodex.mobile.ui.theme.*
import org.junit.Assert.*
import org.junit.Test

class SettingsAccentTest {
    @Test fun customSeedsGenerateReadableRolesInLightAndDark() {
        for (dark in listOf(false, true)) {
            val base = if (dark) darkColorScheme() else lightColorScheme()
            for (seed in listOf(0xff000000, 0xffffffff, 0xffffea00, 0xff007aff, 0xff44825c, 0xff8866aa)) {
                val colors = seedColorScheme(base, dark, seed.toInt())
                val chat = colors.conversationColors()
                for ((ink, paper) in listOf(colors.onPrimary to colors.primary,
                    colors.onPrimaryContainer to colors.primaryContainer, colors.primary to chat.reading,
                    colors.onSurface to chat.reading, chat.inputText to chat.input, chat.inputHint to chat.input)) {
                    assertTrue("seed=$seed dark=$dark", contrastRatio(ink, paper) >= 4.5f)
                }
                assertEquals(colors.background, chat.reading)
            }
        }
    }

    @Test fun settingsGraySeparatesCardsFromCanvasInBothModes() {
        for (dark in listOf(false, true)) {
            val base = sessionColorScheme(if (dark) darkColorScheme() else lightColorScheme(), dark)
            val colors = settingsColorScheme(base, PaletteMode.BuiltIn)
            val canvas = colors.conversationColors().reading
            for (card in listOf(colors.surface, colors.conversationColors().dialog)) {
                assertTrue(contrastRatio(card, canvas) >= 1.20f)
                assertTrue(contrastRatio(card, colors.onSurfaceVariant) >= 4.5f)
            }
            assertSame(base, settingsColorScheme(base, PaletteMode.System))
            assertSame(base, settingsColorScheme(base, PaletteMode.Custom))
        }
    }

    @Test fun seedInputAndInitialChoice() {
        assertEquals(PaletteMode.BuiltIn, AppPalette().mode)
        assertEquals(0xff44825c.toInt(), parsePaletteSeed(" #44825c "))
        assertEquals(0xff000000.toInt(), parsePaletteSeed("000000"))
        for (invalid in listOf("blue", "", "#abc", "11223344", "#77xx22", "FFFFFFF")) assertNull(parsePaletteSeed(invalid))
    }
}
