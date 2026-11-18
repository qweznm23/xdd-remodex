package com.remodex.mobile.core.model

import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class ConversationMediaTypeTest {
    @Test fun videoPathsAndSignedUrlsAreRecognizedWithoutUsingQueryParameters() {
        for (source in listOf("/repo/demo.mp4", "sandbox:/mnt/data/preview.MOV", "file:///repo/demo%20clip.webm", "https://example.test/media/demo%2Emp4?token=keep#t=2", "C:\\videos\\demo.mkv")) {
            assertTrue(ConversationMediaType.isVideo(source),source)
        }
        for (source in listOf("photo.png?download=video.mp4", "https://example.test/photo.jpg?next=video.mp4", "image.gif", "module.ts", "video.mp4.png")) assertFalse(ConversationMediaType.isVideo(source),source)
        assertEquals("a+b.mp4",ConversationMediaType.filename("https://example.test/a+b.mp4?sig=keep"))
    }
    @Test fun mimeTypesHandleExtensionlessVideosAndKeepActualImagesAsImages() {
        assertTrue(ConversationMediaType.isVideo("https://example.test/download/123","video/mp4; charset=binary"))
        assertFalse(ConversationMediaType.isVideo("https://example.test/poster.mp4","image/png"))
        assertTrue(ConversationMediaType.isVideo("data:video/mp4;base64,AAAA"))
        assertFalse(ConversationMediaType.isVideo("data:image/png;base64,AAAA"))
    }
    @Test fun oldPersistedVideoAttachmentsRouteToVideoEvenWithAnImageThumbnail() {
        val legacy=Json.decodeFromString<CodexImageAttachment>("""{"id":"old","thumbnailBase64JPEG":"poster","sourceURL":"/repo/movie.mp4"}""")
        assertEquals("/repo/movie.mp4",ConversationMediaType.videoSource(legacy))
        val typed=legacy.copy(sourceURL="https://example.test/download/123",mimeType="video/webm")
        assertEquals(typed,Json.decodeFromString<CodexImageAttachment>(Json.encodeToString(typed)))
        assertEquals(typed.sourceURL,ConversationMediaType.videoSource(typed))
    }
}
