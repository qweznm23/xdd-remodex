package com.remodex.mobile.data

import com.remodex.mobile.core.model.*
import com.remodex.mobile.ui.agent.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ResponseImageAlbumTest {
    @Test fun actualInterleavedHistoryShapeProducesOneNineImageAlbum() {
        val json = javaClass.getResource("/response-image-album.json")!!.readText()
        val payload = JSONValue.fromJsonElement(kotlinx.serialization.json.Json.parseToJsonElement(json)).objectValue!!
        val decoded = ThreadHistoryDecoder.decodeFromThreadRead("gallery", payload)
        assertEquals(9, decoded.count { it.attachments.isNotEmpty() })
        val presentation = TimelinePresentationBuilder().build(decoded, false, null)
        val albums = presentation.items.filterIsInstance<TimelineListItem.Single>().filter { it.message.attachments.isNotEmpty() }
        assertEquals(1, albums.size)
        assertEquals(9, albums.single().message.attachments.size)
        assertEquals(9, presentation.messages.count { it.attachments.isNotEmpty() })
    }

    private fun image(id: String, turn: String? = "turn") = CodexMessage(id=id,threadId="thread",turnId=turn,
        role=CodexMessageRole.assistant,text="",createdAt=Instant.EPOCH,
        attachments=listOf(CodexImageAttachment(id=id,thumbnailBase64JPEG="",sourceURL="/$id.png")))

    @Test fun separateGeneratedItemsAndMarkdownInSameResponseBecomeOneAlbum() {
        val messages = listOf(image("a"), image("b"), image("caption").copy(text="Caption",attachments=emptyList()),
            image("c").copy(text="![third](/c.png)",attachments=emptyList()))
        val presentation = TimelinePresentationBuilder().build(messages,false,null)
        val visible = presentation.items.filterIsInstance<TimelineListItem.Single>().map { it.message }
        assertEquals(1,visible.count { it.attachments.isNotEmpty() })
        assertEquals(listOf("/a.png","/b.png","/c.png"),visible.first().attachments.map { it.sourceURL })
        assertTrue(visible.any { it.text == "Caption" })
        assertSame(messages,presentation.messages)
        assertEquals(1,messages.first().attachments.size)
    }

    @Test fun neverCombinesAcrossUserOrTurnOrThreadBoundaries() {
        val messages=listOf(image("a"),image("b","other"),image("user","other").copy(role=CodexMessageRole.user),
            image("c","other"),image("d","other").copy(threadId="another"))
        assertEquals(5,groupResponseImageAlbums(messages).count { it.attachments.isNotEmpty() })
    }

    @Test fun missingTurnOnlyGroupsAdjacentImageOnlyMessages() {
        val messages=listOf(image("a",null),image("b",null),image("caption",null).copy(text="separator",attachments=emptyList()),image("c",null))
        val result=groupResponseImageAlbums(messages)
        assertEquals(3,result.size)
        assertEquals(2,result.first().attachments.size)
        assertEquals(1,result.last().attachments.size)
    }

    @Test fun growingAlbumKeepsRowIdentityAndUnchangedGroupsReuseItems() {
        val builder=TimelinePresentationBuilder()
        val original=listOf(image("a"),image("b"))
        val first=builder.build(original,true,"turn")
        val second=builder.build(original+image("stream").copy(text="output",attachments=emptyList(),isStreaming=true),true,"turn")
        assertSame(first.items.first(),second.items.first())
        val third=builder.build(original+image("c"),true,"turn")
        assertEquals(first.items.first().stableKey,third.items.first().stableKey)
        assertEquals(3,(third.items.first() as TimelineListItem.Single).message.attachments.size)
    }

    @Test fun keepsVideosAtTheirOriginalMessageAndDeduplicatesPhotos() {
        val movie=CodexImageAttachment(id="v",thumbnailBase64JPEG="",sourceURL="/v.mp4")
        val messages=listOf(image("a"),image("b").copy(attachments=listOf(movie,image("a").attachments.single())))
        val grouped=groupResponseImageAlbums(messages)
        assertEquals(2,grouped.size)
        assertEquals(1,grouped.first().attachments.size)
        assertEquals(listOf(movie),grouped.last().attachments)
    }

    @Test fun sameTimestampNumberedBatchHasStableNumericOrderAcrossRefreshes() {
        val builder = TimelinePresentationBuilder()
        val a = image("page-1")
        val b = image("page-2")
        val c = image("page-10")
        for (snapshot in listOf(listOf(c, a, b), listOf(b, c, a), listOf(a, b, c))) {
            val row = builder.build(snapshot, false, null).items.filterIsInstance<TimelineListItem.Single>().single()
            assertEquals(listOf("/page-1.png", "/page-2.png", "/page-10.png"), row.message.attachments.map { it.sourceURL })
        }
    }

    @Test fun explicitReplyOrderWinsAcrossSeparateGeneratedItems() {
        val final = image("final").copy(text = "![ten](/page-10.png) ![one](/page-1.png) ![two](/page-2.png)", attachments = emptyList())
        for (snapshot in listOf(
            listOf(image("page-2"), image("page-1"), image("page-10"), final),
            listOf(image("page-1"), image("page-10"), image("page-2"), final),
        )) {
            val rows = TimelinePresentationBuilder().build(snapshot, false, null).items.filterIsInstance<TimelineListItem.Single>()
            val album = rows.single { it.message.attachments.isNotEmpty() }
            assertEquals(listOf("/page-10.png", "/page-1.png", "/page-2.png"), album.message.attachments.map { it.sourceURL })
            assertEquals(snapshot.first().id, album.message.id)
        }
    }

    @Test fun unrelatedNamesAndUserAttachmentOrderAreNotReinterpreted() {
        val unrelated = listOf(image("design2"), image("screen1"))
        assertEquals(listOf("/design2.png", "/screen1.png"), groupResponseImageAlbums(unrelated).first().attachments.map { it.sourceURL })
        val user = image("user").copy(role = CodexMessageRole.user,
            attachments = listOf(image("page-10").attachments.single(), image("page-2").attachments.single()))
        assertEquals(user.attachments, groupResponseImageAlbums(listOf(user)).single().attachments)
        val turns = listOf(image("page-2", "old"), image("page-1", "new"))
        assertEquals(listOf("/page-2.png", "/page-1.png"), groupResponseImageAlbums(turns).flatMap { it.attachments }.map { it.sourceURL })
    }

    @Test fun repeatedFirstAttachmentDoesNotBreakReorderingOrVideoSlots() {
        val photo = image("page-1").attachments.single()
        val movie = CodexImageAttachment(id = "video", thumbnailBase64JPEG = "", sourceURL = "/video.mp4")
        val row = image("row").copy(attachments = listOf(photo, movie, photo))
        assertEquals(listOf(photo, movie), groupResponseImageAlbums(listOf(row)).single().attachments)
    }
}
