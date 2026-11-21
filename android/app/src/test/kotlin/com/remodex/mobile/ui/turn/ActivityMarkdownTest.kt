package com.remodex.mobile.ui.turn

import com.mikepenz.markdown.model.State
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.ui.agent.TimelineListItem
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ActivityMarkdownTest {
    @Test fun onlyLiteralParagraphsUseTextLayout() {
        assertTrue(isPlainActivityParagraph("已完成检查。"))
        assertTrue(isPlainActivityParagraph("Completed the check."))
        for (text in listOf("1.", "1. check", "x  y", "    code", "## Heading", "*note*", "see example.org",
            "[link](https://example.org)", "a\nb", "`code`", "![x](x.png)", "x &amp; y")) {
            assertFalse(isPlainActivityParagraph(text), text)
        }
    }

    @Test fun preparedRichTextIsInTheRenderCacheAndPlainTextNeedsNoParse() = runTest {
        val msg = CodexMessage(threadId = "qa", role = CodexMessageRole.assistant, text = "准备测试。", createdAt = Instant.EPOCH)
        timelineMarkdownCache.clear()
        prepareTimelineItemMarkdown(TimelineListItem.Single(msg), activityDetail = true)
        assertNull(timelineMarkdownCache.peek(msg.text))
        val rich = msg.copy(text = "步骤 **一**")
        prepareTimelineItemMarkdown(TimelineListItem.Single(rich), activityDetail = true)
        assertIs<State.Success>(timelineMarkdownCache.peek(rich.text))
    }
}
