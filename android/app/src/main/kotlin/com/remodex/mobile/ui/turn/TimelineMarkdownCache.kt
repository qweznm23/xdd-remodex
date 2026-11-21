package com.remodex.mobile.ui.turn

import androidx.compose.runtime.*
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import com.remodex.mobile.data.TurnMarkdownSegmentKind
import com.remodex.mobile.data.TurnTimelineRichContentCache
import com.remodex.mobile.ui.agent.TimelineListItem
import com.remodex.mobile.ui.agent.TimelinePresentation
import com.remodex.mobile.ui.agent.toRenderMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Parsed documents survive lazy row disposal. No UI state, jobs or views are held here. */
internal class TimelineMarkdownCache(private val maxEntries: Int = 256, private val maxChars: Int = 512_000) {
    private val entries = LinkedHashMap<String, State>(16, .75f, true)
    private var chars = 0
    @Synchronized fun peek(text: String): State? = entries[text]
    @Synchronized fun clear() { entries.clear(); chars = 0 }
    @Synchronized internal fun size() = entries.size

    suspend fun get(text: String): State {
        peek(text)?.let { return it }
        // Parsing never runs under the cache lock; scrolling must not wait for a parser.
        val parsed = withContext(Dispatchers.Default) {
            parseMarkdownFlow(text).first { it !is State.Loading }
        }
        if (parsed is State.Error) {
            if (parsed.result is CancellationException) throw parsed.result
            android.util.Log.e("RemodexMarkdown", "operation=parse stage=markdown_ast chars=${text.length}", parsed.result)
        }
        return synchronized(this) {
            entries[text]?.let { return@synchronized it }
            if (text.length <= maxChars) {
                entries[text] = parsed; chars += text.length
                while (entries.size > maxEntries || chars > maxChars) {
                    val oldest = entries.entries.iterator(); chars -= oldest.next().key.length; oldest.remove()
                }
            }
            parsed
        }
    }
}

internal val timelineMarkdownCache = TimelineMarkdownCache()

/** Settled rows are ready before insertion, so their first measurement has their text height. */
internal suspend fun prepareTimelineMarkdown(presentation: TimelinePresentation, previous: TimelinePresentation? = null) {
    val previousItems = previous?.items?.associateBy { it.stableKey }.orEmpty()
    for (item in presentation.items) {
        if (previousItems[item.stableKey] === item) continue
        prepareTimelineItemMarkdown(item)
    }
}

internal suspend fun prepareTimelineItemMarkdown(item: TimelineListItem, activityDetail: Boolean = false) {
    val message = when (item) {
        is TimelineListItem.Single -> item.message
        is TimelineListItem.MessageChunk -> item.toRenderMessage()
        else -> return // Collapsed tool groups do not render Markdown bodies.
    }
    val body = settledTimelineMarkdown(message)?.takeIf { it.isNotEmpty() } ?: return
    if (activityDetail && isPlainActivityParagraph(body)) return
    val rich = TurnTimelineRichContentCache.parseMermaidMarkdown(body)
    val markdown = rich?.filter { it.kind == TurnMarkdownSegmentKind.markdown }?.map { it.text } ?: listOf(body)
    for (part in markdown) for (segment in TurnMarkdownRenderCache.fenceSegments(TurnMarkdownRenderCache.visibleProse(part))) {
        if (segment is MarkdownFenceSegment.Text) timelineMarkdownCache.get(segment.markdown)
    }
}

/** Live text keeps its previous render until the next parse finishes, without starving on deltas. */
@Composable
internal fun rememberCachedMarkdown(text: String): State {
    val current by rememberUpdatedState(text)
    val state by produceState<State>(timelineMarkdownCache.peek(text) ?: State.Loading()) {
        snapshotFlow { current }.conflate().collect { value = timelineMarkdownCache.get(it) }
    }
    return timelineMarkdownCache.peek(text) ?: state
}
