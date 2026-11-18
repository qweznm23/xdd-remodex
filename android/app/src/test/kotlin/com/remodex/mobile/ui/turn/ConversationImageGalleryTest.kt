package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationImageGalleryTest {
    private fun image(path: String) = CodexImageAttachment(id = path, thumbnailBase64JPEG = "", sourceURL = path)
    private fun message(text: String, attachments: List<CodexImageAttachment> = emptyList()) =
        CodexMessage(createdAt = java.time.Instant.EPOCH, threadId = "t", role = CodexMessageRole.assistant, text = text, attachments = attachments)

    @Test fun preservesConversationOrderAndDeduplicatesMixedSources() {
        val selected = image("/second.png")
        val images = conversationGalleryImages(listOf(
            message("![first](/first.png)"),
            message("![second](/second.png)", listOf(selected)),
            message("![again](/first.png)\n\n![last](/last.png)"),
        ), selected)
        assertEquals(listOf("/first.png", "/second.png", "/last.png"), images.map { it.sourceURL })
    }

    @Test fun excludesVideosAndCodeExamplesAndOrdinaryLinks() {
        val selected = image("/real.png")
        val images = conversationGalleryImages(listOf(message(
            "```md\n![code](/code.png)\n```\n\n`![inline](/inline.png)`\n\n![video](/movie.mp4)\n\n[file](/not-an-image.pdf)\n\n![actual](/real.png)",
            listOf(image("https://example.test/clip.webm?download=1")),
        )), selected)
        assertEquals(listOf("/real.png"), images.map { it.sourceURL })
    }

    @Test fun parsesBalancedParenthesesAndEscapedDestinations() {
        val images = conversationGalleryImages(listOf(message("![one](/folder/picture(2).png)\n\n![two](/folder/a\\(b\\).png)")), image("/folder/picture(2).png"))
        assertEquals(listOf("/folder/picture(2).png", "/folder/a(b).png"), images.map { it.sourceURL })
    }

    @Test fun keepsTappedImageWhenHistoryDoesNotContainIt() {
        val selected = image("/not-loaded.png")
        assertEquals(listOf(selected), conversationGalleryImages(emptyList(), selected))
    }

    @Test fun galleryAndThumbnailAlbumAgreeAfterHistoryRefreshChangesImageArrivalOrder() {
        val two = image("/page-2.png")
        val one = image("/page-1.png")
        val ten = image("/page-10.png")
        for (order in listOf(listOf(ten, one, two), listOf(two, ten, one))) {
            val messages = order.map { message("", listOf(it)).copy(turnId = "turn") } +
                message("![one](/page-1.png) ![two](/page-2.png) ![ten](/page-10.png)").copy(turnId = "turn")
            val visible = com.remodex.mobile.ui.agent.TimelinePresentationBuilder().build(messages, false, null)
                .items.filterIsInstance<com.remodex.mobile.ui.agent.TimelineListItem.Single>()
                .single { it.message.attachments.isNotEmpty() }.message.attachments
            val gallery = conversationGalleryImages(messages, two)
            assertEquals(listOf("/page-1.png", "/page-2.png", "/page-10.png"), gallery.map { it.sourceURL })
            assertEquals(visible.map { it.sourceURL }, gallery.map { it.sourceURL })
            assertEquals(1, gallery.indexOfFirst { it.sourceURL == two.sourceURL })
        }
    }
}
