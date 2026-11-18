package com.remodex.mobile.ui.turn

import kotlin.test.*
import org.junit.Test

class InteractivePageRequestRouterTest {
    private val html = InteractivePagePolicy.document("<button>油量 20%</button><script>window.ready=true</script>")
    private val router = InteractivePageRequestRouter(html)

    @Test fun mainDocumentIsServedLocallyAsUtf8HtmlInsteadOf403() {
        val response = assertNotNull(router.intercept(InteractivePagePolicy.URL, true, "GET"))
        assertEquals(200, response.status)
        assertEquals("text/html", response.mime)
        assertEquals(html, response.bytes.toString(Charsets.UTF_8))
        assertTrue(response.bytes.toString(Charsets.UTF_8).contains("<button>油量 20%</button>"))
    }

    @Test fun reloadingGetsCompleteDocumentAndNewPreviewDoesNotReuseOldContent() {
        val first = assertNotNull(router.intercept(InteractivePagePolicy.URL, true, "GET"))
        val reload = assertNotNull(router.intercept(InteractivePagePolicy.URL, true, "GET"))
        assertContentEquals(first.bytes, reload.bytes)
        val other = InteractivePageRequestRouter("new document").intercept(InteractivePagePolicy.URL, true, "GET")
        assertEquals("new document", assertNotNull(other).bytes.toString(Charsets.UTF_8))
    }

    @Test fun onlyKnownMainDocumentIsServedWithoutBroadeningNavigation() {
        for (url in listOf("data:text/html;charset=utf-8;base64,", "https://unpkg.com/lib.js", "file:///data/private.html",
            InteractivePagePolicy.URL + "?other=1", InteractivePagePolicy.URL + "/other")) {
            assertEquals(403, assertNotNull(router.intercept(url, true, "GET")).status, url)
        }
        assertEquals(403, assertNotNull(router.intercept(InteractivePagePolicy.URL, true, "POST")).status)
        assertEquals(403, assertNotNull(router.intercept(InteractivePagePolicy.URL, false, "GET")).status)
    }

    @Test fun embeddedImagesAndApprovedLibrariesStillLoadButBridgeEndpointsStayBlocked() {
        assertNull(router.intercept("data:image/png;base64,AA==", false, "GET"))
        assertNull(router.intercept("https://unpkg.com/lucide@1.17.0/dist/umd/lucide.js", false, "GET"))
        assertEquals(403, assertNotNull(router.intercept("https://127.0.0.1:10004/auth", false, "GET")).status)
        assertEquals(403, assertNotNull(router.intercept("https://unpkg.com/lib.js", false, "POST")).status)
    }
}
