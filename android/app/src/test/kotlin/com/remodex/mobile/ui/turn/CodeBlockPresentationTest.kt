package com.remodex.mobile.ui.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodeBlockPresentationTest {
    @Test fun clippedProseKeepsWholeFenceAndItsTrailingWhitespace() {
        for (newline in listOf("\n", "\r\n")) {
            val prefix = listOf("Intro", "~~~~bash", "printf 'ok'  ", "~~~", "~~~~").joinToString(newline)
            val markdown = prefix + newline + "After code"
            assertEquals(prefix.length, MarkdownFenceSegmentParser.closingFenceAtLimit(markdown, 3))
            assertNull(MarkdownFenceSegmentParser.closingFenceAtLimit(markdown, 1))
            assertNull(MarkdownFenceSegmentParser.closingFenceAtLimit(markdown, 5))
            assertNull(MarkdownFenceSegmentParser.closingFenceAtLimit(prefix.substringBeforeLast("~~~~"), 3))
        }
        val code = MarkdownFenceSegmentParser.parse("~~~text\nfirst\nlast  \n\n~~~").single() as MarkdownFenceSegment.Code
        assertEquals("first\nlast  \n", code.code)
    }

    @Test fun previewIsBoundedByLengthAndVisibleLines() {
        val eight = (1..8).joinToString("\n")
        assertEquals(eight, inlineCodePreview(eight))
        assertEquals(eight + "\n…", inlineCodePreview(eight + "\n9"))
        val prefix = "x".repeat(1599)
        assertEquals(prefix + "\n…", inlineCodePreview(prefix + "🚗end"))
    }
}
