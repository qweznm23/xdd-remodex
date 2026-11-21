package com.remodex.mobile.data

import com.remodex.mobile.core.model.*
import kotlin.test.*

class ThreadHistoryVideoTest {
    private fun decode(content: Map<String,JSONValue>) = ThreadHistoryDecoder.decodeFromThreadRead("thread",mapOf(
        "turns" to JSONValue.Arr(listOf(JSONValue.Obj(mapOf("id" to JSONValue.Str("turn"),"items" to JSONValue.Arr(listOf(JSONValue.Obj(mapOf(
            "id" to JSONValue.Str("item"),"type" to JSONValue.Str("assistant_message"),"content" to JSONValue.Arr(listOf(JSONValue.Obj(content))),
        ))))))))))
    @Test fun extensionlessVideoContentAndMimeSurviveHistoryDecoding() {
        val messages=decode(mapOf("type" to JSONValue.Str("video"),"video_url" to JSONValue.Str("https://example.test/download/123"),"mime_type" to JSONValue.Str("video/webm")))
        val attachment=messages.single().attachments.single()
        assertEquals("video/webm",attachment.mimeType)
        assertEquals("https://example.test/download/123",ConversationMediaType.videoSource(attachment))
    }
    @Test fun videoUrlInsideLegacyImageContentStillRoutesAsVideo() {
        val attachment=decode(mapOf("type" to JSONValue.Str("image"),"url" to JSONValue.Str("/repo/clip.mp4"))).single().attachments.single()
        assertEquals("/repo/clip.mp4",ConversationMediaType.videoSource(attachment))
    }
    @Test fun cachedUserThumbnailDoesNotHideRefreshedVideoType() {
        val old=CodexImageAttachment(id="cached",thumbnailBase64JPEG="poster",sourceURL="/repo/asset")
        val fresh=old.copy(id="remote",mimeType="video/mp4",thumbnailBase64JPEG="")
        val merged=UserChatAttachmentMatcher.merge(listOf(old),listOf(fresh)).single()
        assertEquals("cached",merged.id)
        assertEquals("poster",merged.thumbnailBase64JPEG)
        assertEquals("/repo/asset",ConversationMediaType.videoSource(merged))
    }
    @Test fun generatedAttachmentKeepsServerMimeType() {
        val item=mapOf("type" to JSONValue.Str("image_generation"),"saved_path" to JSONValue.Str("/repo/generated"),"mime_type" to JSONValue.Str("video/mp4"))
        val thread=mapOf("turns" to JSONValue.Arr(listOf(JSONValue.Obj(mapOf("items" to JSONValue.Arr(listOf(JSONValue.Obj(item))))))))
        val attachment=ThreadHistoryDecoder.decodeFromThreadRead("thread",thread).single().attachments.single()
        assertEquals("video/mp4",attachment.mimeType)
        assertEquals("/repo/generated",ConversationMediaType.videoSource(attachment))
    }
    @Test fun declaredVideoDoesNotNeedAFilenameExtension() {
        val attachment=decode(mapOf("type" to JSONValue.Str("local_video"),"path" to JSONValue.Str("/repo/asset"))).single().attachments.single()
        assertEquals("/repo/asset",ConversationMediaType.videoSource(attachment))
    }
    @Test fun nativeImageHistoryKeepsTheExactHostPath() {
        val path = "/host/image #2.png "
        val attachment = decode(mapOf("type" to JSONValue.Str("localImage"), "path" to JSONValue.Str(path))).single().attachments.single()
        assertEquals(path, attachment.sourceURL)
        assertNull(attachment.payloadDataURL)
    }
}
