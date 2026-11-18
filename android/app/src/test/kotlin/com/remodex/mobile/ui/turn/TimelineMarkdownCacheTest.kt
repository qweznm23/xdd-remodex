package com.remodex.mobile.ui.turn

import com.mikepenz.markdown.model.State
import com.remodex.mobile.core.model.*
import com.remodex.mobile.ui.agent.TimelinePresentationBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.*

class TimelineMarkdownCacheTest {
    @Test fun recycledRowsReuseParsedDocumentAndReferenceLinks() = runTest {
        val cache = TimelineMarkdownCache()
        val text = "中文 **内容** [参考][doc]\n\n[doc]: https://example.com/docs"
        val first = cache.get(text) as State.Success
        assertSame(first, cache.get(text))
        assertSame(first, cache.peek(text))
        assertEquals(text, first.content)
        assertEquals("https://example.com/docs", first.referenceLinkHandler.find("[doc]"))
    }

    @Test fun cachesHaveEntryAndTextBudgetsAndClearReleasesDocuments() = runTest {
        val cache = TimelineMarkdownCache(maxEntries = 2, maxChars = 10)
        cache.get("aaa"); cache.get("bbb");cache.peek("aaa");cache.get("ccc")
        assertNull(cache.peek("bbb"));assertEquals(2,cache.size())
        cache.get("123456789")
        assertEquals(1,cache.size());assertNull(cache.peek("aaa"))
        assertIs<State.Success>(cache.get("a text beyond cache budget"))
        assertNull(cache.peek("a text beyond cache budget"))
        cache.clear();assertEquals(0,cache.size())
    }

    @Test fun cancelledPreparationDoesNotPublishOrConvertCancellationToFailure() = runTest {
        val cache = TimelineMarkdownCache()
        var published = false
        val text = "cancelled **document**\n\n".repeat(100)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { cache.get(text);published=true }
        job.cancel();job.join()
        assertFalse(published);assertNull(cache.peek(text))
    }

    @Test fun historyIsPreparedBeforePublicationAndUnchangedRowsAreNotReparsedForLiveDeltas() = runTest {
        timelineMarkdownCache.clear()
        val builder = TimelinePresentationBuilder()
        val fixed = message("fixed", "Older **reply**")
        val live = message("live", "Live **text**").copy(isStreaming=true)
        val first = builder.build(listOf(fixed, live), true, "live")
        prepareTimelineMarkdown(first)
        assertIs<State.Success>(timelineMarkdownCache.peek("Older **reply**"))
        assertNull(timelineMarkdownCache.peek("Live **text**"))
        timelineMarkdownCache.clear() // Even eviction must not cause reparsing all history on each delta.
        val next = builder.build(listOf(fixed, live.copy(text="Live **text** continues")),true,"live")
        prepareTimelineMarkdown(next, first)
        assertEquals(0,timelineMarkdownCache.size())
    }

    @Test fun prewarmingUsesRenderedBodyWithoutHiddenPlanOrQuestionPrompt() {
        val message = message("body", "Visible\n\n<proposed_plan>\nHidden plan\n</proposed_plan>")
        assertEquals("Visible",settledTimelineMarkdown(message)?.trim())
        assertNull(settledTimelineMarkdown(message.copy(isStreaming=true)))
        assertNull(settledTimelineMarkdown(message.copy(kind=CodexMessageKind.commandExecution)))
    }

    private fun message(id:String,text:String)=CodexMessage(id=id,threadId="thread",turnId=id,
        itemId=id,role=CodexMessageRole.assistant,text=text,createdAt=Instant.EPOCH)
}
