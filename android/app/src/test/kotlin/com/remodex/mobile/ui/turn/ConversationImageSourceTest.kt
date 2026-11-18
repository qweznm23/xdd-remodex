package com.remodex.mobile.ui.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConversationImageSourceTest {
    @Test fun watchScreenshotUsesComputerFileRoute() {
        val path = "/data/projects/xdd/mywidget/mywidget-watch-android/app/build/home-hero/actual-home.png"
        assertEquals(ConversationImageSource.Workspace(path), conversationImageSource(path))
        assertEquals(ConversationImageSource.Workspace(path), conversationImageSource("file://$path"))
        assertEquals(ConversationImageSource.Workspace(path), conversationImageSource("sandbox:$path"))
    }

    @Test fun relativePathsPreserveWorkspaceContextAndPercentEncodedSpaces() {
        assertEquals(ConversationImageSource.Workspace("./screens/hello world.png"), conversationImageSource("./screens/hello%20world.png"))
        assertEquals(ConversationImageSource.Workspace("screen.png"), conversationImageSource("screen.png"))
    }

    @Test fun networkAndInlineImagesDoNotBecomeComputerPaths() {
        val remote = "https://example.com/image.png?token=test"
        assertEquals(ConversationImageSource.Remote(remote), conversationImageSource(remote))
        assertEquals(ConversationImageSource.Inline("data:image/png;base64,QQ=="), conversationImageSource("data:image/png;base64,QQ=="))
        assertFailsWith<IllegalArgumentException> { conversationImageSource("remodex://history-image-elided") }
        assertFailsWith<IllegalArgumentException> { conversationImageSource("content://another-app/image") }
    }

    @Test fun absoluteScreenshotUsesItsOwnDirectoryEvenWhenThreadIsInHome() {
        val image = conversationImageSource("/root/remodex-test/preview.png") as ConversationImageSource.Workspace
        assertEquals("/root/remodex-test", image.previewCwd("/root"))
        assertEquals("/root/remodex-test", image.previewCwd("/another/project"))
        assertEquals("/root/remodex-test", image.previewCwd(null))
    }

    @Test fun relativeImageKeepsTheThreadDirectory() {
        val image = conversationImageSource("./screens/preview.png") as ConversationImageSource.Workspace
        assertEquals("/project", image.previewCwd("/project"))
        assertEquals(null, image.previewCwd(null))
    }

    @Test fun encodedAndWindowsImagePathsUseTheDecodedParent() {
        val encoded = conversationImageSource("file:///root/test%20images/preview.png") as ConversationImageSource.Workspace
        assertEquals("/root/test images", encoded.previewCwd("/root"))
        val windows = conversationImageSource("C:/Users/Alice/screens/preview.png") as ConversationImageSource.Workspace
        assertEquals("C:/Users/Alice/screens", windows.previewCwd("C:/Users/Alice"))
    }
}
