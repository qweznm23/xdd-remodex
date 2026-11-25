package com.remodex.mobile.ui.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.core.model.visibleSummaryParts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal data class TimelinePresentation(
    val messages: List<CodexMessage> = emptyList(),
    val items: List<TimelineListItem> = emptyList(),
    val statusText: TimelineActivityLabel? = null,
    val activeSummary: List<String> = emptyList(),
) {
    val elapsedFooterKeys = completedTimingFooters(items)
    val hasActivityRow: Boolean get() = statusText != null

    fun lastListIndex(headerCount: Int): Int =
        (headerCount + items.size + if (hasActivityRow) 1 else 0) - 1
}

/** One cache per conversation, retaining only messages in the current rendered window. */
internal class TimelinePresentationBuilder {
    private data class Entry(val message: CodexMessage, val items: List<TimelineListItem>)
    private val messageCache = mutableMapOf<String, Entry>()
    private val preparedMessages = mutableMapOf<String, Pair<CodexMessage, com.remodex.mobile.data.PreparedImageAlbum>>()
    private var previousItems = emptyMap<String, TimelineListItem>()

    @Synchronized
    fun build(messages: List<CodexMessage>, running: Boolean, activeTurnId: String?,
              allMessages: List<CodexMessage> = messages): TimelinePresentation {
        val summaryTurnId = activeTurnId ?: if (running) {
            // A busy thread may briefly lack its active turn id. Only trust a streaming
            // summary after the latest user input in this case.
            allMessages.asReversed().asSequence().takeWhile { it.role != CodexMessageRole.user }
                .firstOrNull { it.isStreaming && it.reasoningSummary.isNotEmpty() }?.turnId
        } else null
        val prepared = messages.map { message ->
            val cached = preparedMessages[message.id]
            if (cached?.first === message) cached.second else {
                com.remodex.mobile.data.prepareImageAlbum(message).also {
                    preparedMessages[message.id] = message to it
                }
            }
        }
        val items = com.remodex.mobile.data.groupResponseImageAlbums(
            prepared.map { it.message },
            prepared.filter { it.explicitOrder.isNotEmpty() }.associate { it.message.id to it.explicitOrder },
        ).toTimelineListItems(
            collapseLatestTurn = !running,
            activeTurnId = summaryTurnId,
            messageItems = { message ->
                val cached = messageCache[message.id]
                if (cached?.message == message) {
                    cached.items
                } else {
                    messageTimelineItems(message).also { messageCache[message.id] = Entry(message, it) }
                }
            },
        ).map { item -> previousItems[item.stableKey]?.takeIf { it == item } ?: item }
        messageCache.keys.retainAll(messages.mapTo(hashSetOf()) { it.id })
        preparedMessages.keys.retainAll(messages.mapTo(hashSetOf()) { it.id })
        previousItems = items.associateBy { it.stableKey }
        return TimelinePresentation(
            messages = messages,
            items = items,
            statusText = messages.deriveTransientActivityStatus(running, activeTurnId),
            activeSummary = if (running && summaryTurnId != null) allMessages.asSequence()
                .filter { it.turnId == summaryTurnId && it.reasoningSummary.isNotEmpty() }
                .flatMap { it.visibleSummaryParts() }.toList() else emptyList(),
        )
    }
}

internal data class TimelineBuildRequest(
    val messages: List<CodexMessage>,
    val running: Boolean,
    val activeTurnId: String?,
    val allMessages: List<CodexMessage> = messages,
)

/** Finish current preparation, then take the newest snapshot; fast input must not starve display. */
internal fun Flow<TimelineBuildRequest>.prepareTimeline(
    build: suspend (TimelineBuildRequest) -> TimelinePresentation,
): Flow<TimelinePresentation> = conflate().map { build(it) }

@Composable
internal fun rememberTimelinePresentation(
    threadId: String,
    messages: List<CodexMessage>,
    running: Boolean,
    activeTurnId: String?,
    allMessages: List<CodexMessage> = messages,
): TimelinePresentation = key(threadId) {
    val builder = remember(threadId) { TimelinePresentationBuilder() }
    val request by rememberUpdatedState(TimelineBuildRequest(messages, running, activeTurnId, allMessages))
    val result by produceState(TimelinePresentation(), builder) {
        var previous: TimelinePresentation? = null
        snapshotFlow { request }.prepareTimeline { next ->
            // Thread/lifecycle cancellation still prevents publication to a departed screen.
            withContext(Dispatchers.Default) {
                builder.build(next.messages, next.running, next.activeTurnId, next.allMessages).also {
                    com.remodex.mobile.ui.turn.prepareTimelineMarkdown(it, previous)
                    previous = it
                }
            }
        }.collect { value = it }
    }
    result
}
