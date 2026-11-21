package com.remodex.mobile.ui.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownUrlPolicyTest {
    @Test
    fun allowsOnlyExpectedExternalSchemes() {
        assertTrue(MarkdownUrlPolicy.isAllowedLink("https://example.com"))
        assertTrue(MarkdownUrlPolicy.isAllowedLink("http://example.com"))
        assertTrue(MarkdownUrlPolicy.isAllowedLink("mailto:security@example.com"))

        assertFalse(MarkdownUrlPolicy.isAllowedLink("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(MarkdownUrlPolicy.isAllowedLink("file:///data/data/com.remodex.mobile/shared_prefs/store.xml"))
        assertFalse(MarkdownUrlPolicy.isAllowedLink("content://com.example.provider/item"))
        assertFalse(MarkdownUrlPolicy.isAllowedLink("javascript:alert(1)"))
        assertFalse(MarkdownUrlPolicy.isAllowedLink("./local/file.kt"))
    }

    @Test
    fun repoMarkdownPreviewPathAcceptsUncommonLocalFileExtensions() {
        assertEquals("example.ex", RepoMarkdownFileLink.previewPath("example.ex"))
        assertEquals("./src/example.ex", RepoMarkdownFileLink.previewPath("./src/example.ex"))
    }

    @Test
    fun sandboxArtifactLinksUseHostPathAndParentForPreview() {
        val link = "sandbox:/mnt/data/A4L-S4-refined.png"
        val path = requireNotNull(RepoMarkdownFileLink.previewPath(link))
        assertEquals("/mnt/data/A4L-S4-refined.png", path)
        assertEquals("/mnt/data", RepoMarkdownFileLink.previewWorkspaceCwd(path))
        assertEquals("C:/repo/image.png", RepoMarkdownFileLink.previewPath("sandbox:/C:/repo/image.png"))
        assertEquals("/mnt/data/A+B #1.glb", RepoMarkdownFileLink.previewPath("sandbox:/mnt/data/A+B%20%231.glb?download=1"))
        assertEquals(null, RepoMarkdownFileLink.previewPath("sandbox://other-host/mnt/data/file.png"))
        assertEquals(null, RepoMarkdownFileLink.previewPath("sandbox:relative.png"))
    }

    @Test
    fun repoMarkdownPreviewPathDropsLineSuffixes() {
        assertEquals("README.md", RepoMarkdownFileLink.previewPath("README.md:12"))
        assertEquals("./Docs/README.md", RepoMarkdownFileLink.previewPath("./Docs/README.md:12:3"))
        assertEquals("C:/repo/Docs/README.md", RepoMarkdownFileLink.previewPath("file:///C:/repo/Docs/README.md:12"))
        assertEquals("./Docs/README.md", RepoMarkdownFileLink.previewPath("./Docs/README.md:12#L12"))
    }

    @Test
    fun repoMarkdownPreviewCwdUsesAbsoluteLinkParent() {
        assertEquals("C:/repo/Docs", RepoMarkdownFileLink.previewWorkspaceCwd("C:/repo/Docs/README.md"))
        assertEquals("/Users/me/repo/Docs", RepoMarkdownFileLink.previewWorkspaceCwd("/Users/me/repo/Docs/README.md"))
        assertEquals(null, RepoMarkdownFileLink.previewWorkspaceCwd("./Docs/README.md"))
    }
}
