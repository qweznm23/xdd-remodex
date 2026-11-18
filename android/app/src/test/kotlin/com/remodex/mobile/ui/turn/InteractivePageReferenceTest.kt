package com.remodex.mobile.ui.turn

import kotlin.test.*
import org.junit.Test

class InteractivePageReferenceTest {
    private val marker = "\uE200visualize\uE202{\"path\":\"/root/.codex/visualizations/demo.html\",\"title\":\"油量交互\",\"mode\":\"wide\"}\uE201"

    @Test fun parsesNativeAndHistoricalVisualizeMarkers() {
        assertEquals(InteractivePageReference("/root/.codex/visualizations/demo.html", "油量交互"), InteractivePageReference.parse(marker))
        assertEquals("demo", InteractivePageReference.parse("visualize {\"path\":\"/tmp/demo.html\"}")?.title)
    }

    @Test fun preservesSurroundingProseAndMultiplePageOrder() {
        val segments = MarkdownFenceSegmentParser.parse("before\n$marker\nafter\nvisualize {\"path\":\"/tmp/second.html\"}")
        assertEquals(4, segments.size)
        assertEquals("before", assertIs<MarkdownFenceSegment.Text>(segments[0]).markdown)
        assertEquals("油量交互", assertIs<MarkdownFenceSegment.Interactive>(segments[1]).reference.title)
        assertEquals("after", assertIs<MarkdownFenceSegment.Text>(segments[2]).markdown)
        assertEquals("second", assertIs<MarkdownFenceSegment.Interactive>(segments[3]).reference.title)
    }

    @Test fun doesNotExecuteCodeExamplesOrPartialStreamMarkers() {
        for (text in listOf("```text\n$marker\n```", "~~~\n$marker\n~~~", "`$marker`", "visualize {\"path\":\"/tmp/unfinished")) {
            assertTrue(MarkdownFenceSegmentParser.parse(text).none { it is MarkdownFenceSegment.Interactive })
        }
    }

    @Test fun rejectsMalformedAndNonHtmlPaths() {
        for (path in listOf("https://example.com/demo.html", "//server/a.html", "/root/../secret.html", "/tmp/key.json", "relative.html")) {
            assertNull(InteractivePageReference.parse("visualize {\"path\":\"$path\"}"))
        }
        assertNull(InteractivePageReference.parse("visualize {bad}"))
        assertNull(InteractivePageReference.parse("visualize {\"path\":42}"))
    }

    @Test fun blocksDeviceFilesBridgeEndpointsAndLookalikeCdns() {
        for (url in listOf("file:///data/data/secrets", "content://secret", "http://cdn.jsdelivr.net/lib.js", "https://cdn.jsdelivr.net.attacker.test/a.js", "https://cdn.jsdelivr.net@attacker.test/a.js", "https://127.0.0.1:10004/auth", "https://persistent.oaistatic.com/other/script.js")) {
            assertFalse(InteractivePagePolicy.allowedResource(url), url)
        }
        assertTrue(InteractivePagePolicy.allowedResource("https://cdn.jsdelivr.net/npm/example@1/a.js"))
        assertTrue(InteractivePagePolicy.allowedResource("data:image/png;base64,AA=="))
    }

    @Test fun wrapperPreservesInteractiveScriptAndRestrictsHostAccess() {
        val fragment = "<button onclick=\"this.textContent='clicked'\">Click</button>"
        val html = InteractivePagePolicy.document(fragment)
        assertTrue(html.contains(fragment))
        assertTrue(html.contains("<meta charset=\"UTF-8\">"))
        assertTrue(html.indexOf("Content-Security-Policy") < html.indexOf(fragment))
        for (policy in listOf("connect-src 'none'", "frame-src 'none'", "form-action 'none'", "base-uri 'none'")) assertTrue(html.contains(policy))
        assertFalse(html.contains("addJavascriptInterface"))
    }
}
