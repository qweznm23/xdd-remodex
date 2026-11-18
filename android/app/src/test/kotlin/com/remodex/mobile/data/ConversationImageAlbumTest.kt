package com.remodex.mobile.data

import com.remodex.mobile.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ConversationImageAlbumTest {
    private fun message(text: String, attachments: List<CodexImageAttachment> = emptyList()) =
        CodexMessage(id = "m", threadId = "t", role = CodexMessageRole.assistant, text = text, createdAt = Instant.EPOCH, attachments = attachments)
    private fun image(path: String) = CodexImageAttachment(id = path, thumbnailBase64JPEG = "", sourceURL = path)

    @Test fun combinesMarkdownAndAttachmentsWithoutModifyingHistory() {
        val original = message("Before ![one](/one.png) between ![two](/two.png) after", listOf(image("/one.png")))
        val display = prepareConversationImageAlbum(original)
        assertEquals("Before  between  after", display.text)
        assertEquals(listOf("/one.png", "/two.png"), display.attachments.map { it.sourceURL })
        assertTrue(original.text.contains("![one]"))
        assertEquals(1, original.attachments.size)
        assertEquals(original.id, display.id)
    }

    @Test fun preservesCodeLinksAndVideoCards() {
        val input = "```md\n![example](/code.png)\n```\n\n`![inline](/inline.png)`\n\n[download](/file.apk)\n\n![movie](/video.mp4)\n\n![photo](/real.png)"
        val display = prepareConversationImageAlbum(message(input))
        assertEquals(input.removeSuffix("![photo](/real.png)"), display.text)
        assertEquals(listOf("/real.png"), display.attachments.map { it.sourceURL })
    }

    @Test fun markdownOrderWinsOverMirroredAttachmentArrivalOrder() {
        val mirrored = image("/page-2.png").copy(thumbnailBase64JPEG = "cached-preview")
        val original = message("![one](/page-1.png) ![two](/page-2.png)", listOf(mirrored, image("/page-1.png")))
        val prepared = prepareConversationImageAlbum(original)
        assertEquals(listOf("/page-1.png", "/page-2.png"), prepared.attachments.map { it.sourceURL })
        assertSame(mirrored, prepared.attachments.last())
        assertEquals(listOf("/page-2.png", "/page-1.png"), original.attachments.map { it.sourceURL })
    }

    @Test fun partialReplyOnlyReordersMentionedSlotsAndNumericTiesAreStable() {
        val images = listOf(image("/a.png"), image("/unmentioned.png"), image("/c.png"))
        assertEquals(listOf("/c.png", "/unmentioned.png", "/a.png"),
            orderedAlbumImages(images, listOf("/c.png", "/a.png")).map { it.sourceURL })
        val numbered = listOf(image("/page-2.png"), image("/page-02.png"), image("/page-10.png"))
        val expected = listOf("/page-02.png", "/page-2.png", "/page-10.png")
        assertEquals(expected, orderedAlbumImages(numbered, emptyList()).map { it.sourceURL })
        assertEquals(expected, orderedAlbumImages(numbered.reversed(), emptyList()).map { it.sourceURL })
    }

    @Test fun linkedImageDoesNotLeaveAnEmptyLink() {
        val album = markdownImageAlbum("Before [![preview](/picture(2).png)](https://example.test/view) after")
        assertEquals("Before  after", album.body)
        assertEquals(listOf("/picture(2).png"), album.images.map { it.sourceURL })
    }

    @Test fun partialStreamDoesNotInventImagesAndCompletionBuildsAlbum() {
        val partial = message("![photo](/unfinished")
        assertSame(partial, prepareConversationImageAlbum(partial))
        assertEquals(listOf("/unfinished.png"), prepareConversationImageAlbum(partial.copy(text = "![photo](/unfinished.png)")).attachments.map { it.sourceURL })
    }

    @Test fun longMessageKeepsOneAlbumAcrossChunksAndCachesUnaffectedRows() {
        val original = message("Long paragraph with detail.\n\n".repeat(600) + "![a](/one.png)\n\n![b](/two.png)")
        val builder = com.remodex.mobile.ui.agent.TimelinePresentationBuilder()
        val first = builder.build(listOf(original), false, null)
        assertSame(original, first.messages.single())
        val chunks = first.items.filterIsInstance<com.remodex.mobile.ui.agent.TimelineListItem.MessageChunk>()
        assertTrue(chunks.size > 1)
        assertEquals(1, chunks.count { it.isFirstChunk })
        assertTrue(chunks.all { !it.chunkText.contains("![") })
        assertEquals(2, chunks.first().message.attachments.size)
        val next = builder.build(listOf(original, message("new stream").copy(id = "stream", isStreaming = true)), true, null)
        first.items.forEachIndexed { index, item -> assertSame(item, next.items[index]) }
    }
}
