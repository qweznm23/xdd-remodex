package com.remodex.mobile.ui.agent

import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageKind
import com.remodex.mobile.core.model.CodexMessageRole

/** More than this many identical tool rows in a row → one collapsible group. */
internal const val TIMELINE_TOOL_GROUP_THRESHOLD = 3
private const val TIMELINE_ASSISTANT_CHUNK_TARGET_CHARS = 3_200
private const val TIMELINE_ASSISTANT_CHUNK_MIN_CHARS = 1_600

/**
 * Flat timeline row or a consecutive run of [CodexMessageKind.commandExecution] /
 * [CodexMessageKind.fileChange] (system) that was folded because the run length reaches
 * [TIMELINE_TOOL_GROUP_THRESHOLD].
 */
internal sealed interface TimelineListItem {
    val stableKey: String

    data class Single(val message: CodexMessage) : TimelineListItem {
        override val stableKey: String get() = message.id
    }

    data class MessageChunk(
        val message: CodexMessage,
        val chunkIndex: Int,
        val chunkText: String,
        val isFirstChunk: Boolean,
        val isLastChunk: Boolean,
    ) : TimelineListItem {
        override val stableKey: String get() = "${message.id}-chunk-$chunkIndex"
    }

    data class AssistantWorkGroup(
        val groupKey: String,
        val detailItems: List<TimelineListItem>,
    ) : TimelineListItem {
        init {
            require(detailItems.isNotEmpty())
        }

        val messages = detailItems.flatMap { it.timelineMessages() }.distinctBy { it.id }

        override val stableKey: String get() = groupKey
    }

    data class CommandExecutionGroup(val messages: List<CodexMessage>) : TimelineListItem {
        init {
            require(messages.isNotEmpty())
        }

        override val stableKey: String get() = "${messages.first().id}-cmd-group"
    }

    data class FileChangeGroup(val messages: List<CodexMessage>) : TimelineListItem {
        init {
            require(messages.isNotEmpty())
        }

        override val stableKey: String get() = "${messages.first().id}-fc-group"
    }
}

/**
 * Collapse long consecutive runs of command-execution or file-change system messages into
 * [TimelineListItem.CommandExecutionGroup] / [TimelineListItem.FileChangeGroup].
 */
internal fun List<CodexMessage>.toTimelineListItems(
    collapseLatestTurn: Boolean = true,
    activeTurnId: String? = null,
    collapseAssistantWork: Boolean = true,
    messageItems: (CodexMessage) -> List<TimelineListItem> = ::messageTimelineItems,
): List<TimelineListItem> {
    if (isEmpty()) return emptyList()
    val visibleMessages =
        filterNot { message ->
            message.role == CodexMessageRole.system &&
                message.kind == CodexMessageKind.thinking &&
                (message.reasoningSummary.isEmpty() || (!collapseLatestTurn && message.turnId == activeTurnId))
        }
    if (visibleMessages.isEmpty()) return emptyList()
    val out = ArrayList<TimelineListItem>(visibleMessages.size)
    var i = 0
    while (i < visibleMessages.size) {
        val msg = visibleMessages[i]
        val kind = msg.kind
        if (msg.role == CodexMessageRole.system &&
            (kind == CodexMessageKind.commandExecution || kind == CodexMessageKind.fileChange)
        ) {
            var j = i
            while (j + 1 < visibleMessages.size) {
                val next = visibleMessages[j + 1]
                if (next.role == CodexMessageRole.system && next.kind == kind) {
                    j++
                } else {
                    break
                }
            }
            val run = visibleMessages.subList(i, j + 1).toList()
            if (run.size >= TIMELINE_TOOL_GROUP_THRESHOLD) {
                when (kind) {
                    CodexMessageKind.commandExecution ->
                        out += TimelineListItem.CommandExecutionGroup(run)
                    CodexMessageKind.fileChange ->
                        out += TimelineListItem.FileChangeGroup(run)
                    else -> error("unexpected kind in tool run")
                }
            } else {
                run.forEach { out += TimelineListItem.Single(it) }
            }
            i = j + 1
        } else {
            out += messageItems(msg)
            i++
        }
    }
    if (!collapseAssistantWork) return out
    val excludedTurnId = activeTurnId?.trim()?.takeIf { it.isNotEmpty() }
    return out.collapseAssistantWorkGroups(
        excludedTurnId = excludedTurnId,
        excludeLatestTurn = excludedTurnId == null && !collapseLatestTurn,
    )
}

internal data class TimelineActivityLabel(@androidx.annotation.StringRes val resource: Int, val filename: String? = null)

internal fun List<CodexMessage>.deriveTransientActivityStatus(
    isThreadRunning: Boolean,
    activeTurnId: String? = null,
): TimelineActivityLabel? {
    if (!isThreadRunning) return null
    val latest = if (activeTurnId.isNullOrBlank()) lastOrNull() else lastOrNull { it.turnId == activeTurnId }
    return when {
        latest == null -> TimelineActivityLabel(R.string.activity_working)
        latest.kind == CodexMessageKind.commandExecution -> commandExecutionStatus(latest.text)
        latest.kind == CodexMessageKind.fileChange -> fileChangeStatus(latest.text)
        latest.role == CodexMessageRole.assistant && latest.isStreaming && latest.text.isNotBlank() ->
            TimelineActivityLabel(R.string.activity_replying)
        latest.role == CodexMessageRole.user || latest.kind == CodexMessageKind.thinking ->
            TimelineActivityLabel(R.string.turn_top_bar_thinking)
        else -> TimelineActivityLabel(R.string.activity_working)
    }
}

private fun fileChangeStatus(text: String): TimelineActivityLabel {
    val normalized = text.lowercase()
    if ("patch" in normalized || "apply_patch" in normalized) return TimelineActivityLabel(R.string.activity_patch)
    val filename =
        text.lineSequence()
            .map { it.trim().trim('-', '*').trim() }
            .firstOrNull { it.contains('.') || it.contains('/') || it.contains('\\') }
            ?.let(::compactTimelinePath)
            ?.takeIf { it.isNotBlank() }
    return if (filename != null) {
        TimelineActivityLabel(R.string.activity_edit_file, filename)
    } else {
        TimelineActivityLabel(R.string.activity_files)
    }
}

private fun commandExecutionStatus(text: String): TimelineActivityLabel {
    if (text.substringBefore('>').trim().lowercase() in setOf("completed", "complete", "ran", "failed", "stopped", "error")) {
        return TimelineActivityLabel(R.string.activity_working)
    }
    val command = normalizedCommandText(text)
    return when {
        Regex("""\b(apply_patch|patch|edit|write_file|workspace_?edit)\b""").containsMatchIn(command) ->
            TimelineActivityLabel(R.string.activity_patch)
        Regex("""\b(test|check|compile|build|gradlew|xcodebuild|pytest|jest|vitest|kotlinc|tsc)\b""").containsMatchIn(command) ->
            TimelineActivityLabel(R.string.activity_checks)
        Regex("""\b(git\s+status|git\s+diff|diff|status)\b""").containsMatchIn(command) ->
            TimelineActivityLabel(R.string.activity_changes)
        Regex("""\b(rg|grep|ag|ack|find|fd|search|workspace_?search|semantic_?search|code_?search|web_?search)\b""")
            .containsMatchIn(command) ->
            TimelineActivityLabel(R.string.activity_search)
        Regex("""\b(cat|nl|head|tail|sed|less|more|ls|read_file|view_file)\b""").containsMatchIn(command) ->
            TimelineActivityLabel(R.string.activity_reading)
        else -> TimelineActivityLabel(R.string.activity_working)
    }
}

private fun normalizedCommandText(text: String): String =
    text.trim()
        .replace(Regex("""^(running|completed|complete|ran|failed|stopped|error)\s*>?\s*""", RegexOption.IGNORE_CASE), "")
        .lowercase()

private fun compactTimelinePath(path: String): String {
    val trimmed = path.trim().trim('"', '\'')
    val withoutStats = trimmed.replace(Regex("""\s*[+\uFF0B]\s*\d+\s*[-\u2212\u2013\u2014\uFE63\uFF0D]\s*\d+\s*$"""), "")
    val normalized = withoutStats.replace('\\', '/').trimEnd('/')
    return normalized.substringAfterLast('/').ifBlank { normalized }
}

internal fun messageTimelineItems(message: CodexMessage): List<TimelineListItem> =
    message.toAssistantTimelineChunks() ?: listOf(TimelineListItem.Single(message))

/** Only elide a structured prompt's plain-text copy. Preserve accompanying prose and media. */
internal fun CodexMessage.containsOnlyQuestionPrompt(): Boolean {
    val request = structuredUserInputRequest ?: return false
    if (attachments.isNotEmpty()) return false
    if (kind == CodexMessageKind.userInputPrompt || text.isBlank()) return true
    val lines = text.lineSequence().map { it.trim().removePrefix("- ").removePrefix("* ").removePrefix("• ") }
        .filter { it.isNotBlank() }.toList()
    return lines == request.questions.map { it.question.trim() } ||
        lines == request.questions.flatMap { q -> listOf(q.question.trim()) + q.options.map { it.label.trim() } }
}

private fun CodexMessage.toAssistantTimelineChunks(): List<TimelineListItem.MessageChunk>? {
    if (role != CodexMessageRole.assistant || kind != CodexMessageKind.chat) return null
    if (containsOnlyQuestionPrompt()) return null
    val chunks = splitAssistantMarkdownForTimeline(text)
    if (chunks.size <= 1) return null
    return chunks.mapIndexed { index, chunk ->
        TimelineListItem.MessageChunk(
            message = this,
            chunkIndex = index,
            chunkText = chunk,
            isFirstChunk = index == 0,
            isLastChunk = index == chunks.lastIndex,
        )
    }
}

internal fun splitAssistantMarkdownForTimeline(text: String): List<String> {
    if (text.length <= TIMELINE_ASSISTANT_CHUNK_TARGET_CHARS) return listOf(text)
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    var inFence = false
    var fenceMarker: Char? = null
    var fenceLength = 0

    fun flush() {
        val chunk = current.toString().trim()
        if (chunk.isNotBlank()) {
            chunks += chunk
        }
        current.clear()
    }

    text.lineSequence().forEach { line ->
        val trimmed = line.trimStart()
        val markerChar =
            when {
                trimmed.startsWith("```") -> '`'
                trimmed.startsWith("~~~") -> '~'
                else -> null
            }
        if (markerChar != null) {
            val markerRunLength = trimmed.takeWhile { it == markerChar }.length
            if (!inFence && markerRunLength >= 3) {
                inFence = true
                fenceMarker = markerChar
                fenceLength = markerRunLength
            } else if (inFence && markerChar == fenceMarker && markerRunLength >= fenceLength) {
                inFence = false
                fenceMarker = null
                fenceLength = 0
            }
        }

        if (current.isNotEmpty()) current.append('\n')
        current.append(line)

        val canSplit = !inFence && current.length >= TIMELINE_ASSISTANT_CHUNK_MIN_CHARS
        if (canSplit && (current.length >= TIMELINE_ASSISTANT_CHUNK_TARGET_CHARS || line.isBlank())) {
            flush()
        }
    }
    flush()
    return chunks.filter { it.isNotBlank() }.ifEmpty { listOf(text) }
}

private fun List<TimelineListItem>.collapseAssistantWorkGroups(
    excludedTurnId: String?,
    excludeLatestTurn: Boolean,
): List<TimelineListItem> {
    if (size < 2) return this
    val turnItems = indexedTurnItems()
    val excludedTurnKey =
        excludedTurnId?.let { "turn:$it" }
            ?: if (excludeLatestTurn) turnItems.lastOrNull()?.turnKey else null
    val hiddenIndexes = mutableSetOf<Int>()
    val workGroupsByInsertIndex = mutableMapOf<Int, TimelineListItem.AssistantWorkGroup>()
    turnItems
        .groupBy { it.turnKey }
        .values
        .forEach { itemsForTurn ->
            if (itemsForTurn.firstOrNull()?.turnKey == excludedTurnKey) return@forEach
            val finalAssistantMessage = itemsForTurn.lastAssistantChatMessageOrNull() ?: return@forEach
            if (finalAssistantMessage.isStreaming) return@forEach
            val hiddenItems =
                itemsForTurn.filter { item ->
                    finalAssistantMessage.id !in item.messages.map { it.id } &&
                        item.item.isAssistantWorkItem() && item.messages.none { it.structuredUserInputRequest != null || it.attachments.isNotEmpty() || it.text.contains("![") }
                }
            if (hiddenItems.isEmpty()) return@forEach
            hiddenIndexes += hiddenItems.map { it.index }
            workGroupsByInsertIndex[hiddenItems.first().index] =
                TimelineListItem.AssistantWorkGroup(
                    groupKey = "assistant-work-${itemsForTurn.first().groupKey}",
                    detailItems = hiddenItems.map { it.item },
                )
        }
    if (hiddenIndexes.isEmpty()) return this
    val out = ArrayList<TimelineListItem>(size)
    forEachIndexed { index, item ->
        workGroupsByInsertIndex[index]?.let { out += it }
        if (index !in hiddenIndexes) {
            out += item
        }
    }
    return out
}

private data class IndexedTurnItem(
    val index: Int,
    val turnKey: String,
    val groupKey: String,
    val item: TimelineListItem,
    val messages: List<CodexMessage>,
)

private fun List<TimelineListItem>.indexedTurnItems(): List<IndexedTurnItem> {
    val out = ArrayList<IndexedTurnItem>(size)
    var fallbackGroupKey: String? = null
    forEachIndexed { index, item ->
        val messages = item.timelineMessages()
        messages.firstOrNull { it.role == CodexMessageRole.user }?.let { userMessage ->
            fallbackGroupKey = "user-${userMessage.id}"
        }
        val turnId = messages.firstNotNullOfOrNull { it.turnId?.takeIf(String::isNotBlank) }
        val groupKey =
            if (turnId != null) {
                turnId
            } else {
                fallbackGroupKey ?: return@forEachIndexed
            }
        val turnKey =
            if (turnId != null) {
                "turn:$turnId"
            } else {
                "fallback:$groupKey"
            }
        out +=
            IndexedTurnItem(
                index = index,
                turnKey = turnKey,
                groupKey = groupKey,
                item = item,
                messages = messages,
            )
    }
    return out
}

private fun List<IndexedTurnItem>.lastAssistantChatMessageOrNull(): CodexMessage? =
    asReversed()
        .asSequence()
        .flatMap { indexedItem ->
            when (val item = indexedItem.item) {
                is TimelineListItem.MessageChunk ->
                    if (item.isLastChunk) {
                        sequenceOf(item.message)
                    } else {
                        emptySequence()
                    }
                else -> indexedItem.messages.asSequence()
            }
        }
        .firstOrNull { message ->
            message.role == CodexMessageRole.assistant &&
                message.kind == CodexMessageKind.chat
        }

private fun TimelineListItem.timelineMessages(): List<CodexMessage> =
    when (this) {
        is TimelineListItem.Single -> listOf(message)
        is TimelineListItem.MessageChunk -> listOf(message)
        is TimelineListItem.AssistantWorkGroup -> messages
        is TimelineListItem.CommandExecutionGroup -> messages
        is TimelineListItem.FileChangeGroup -> messages
    }

private fun CodexMessage.isCollapsibleAssistantWork(): Boolean =
    when (kind) {
        CodexMessageKind.plan -> false
        CodexMessageKind.thinking -> reasoningSummary.isNotEmpty()
        else ->
            role == CodexMessageRole.assistant ||
                role == CodexMessageRole.system
    }

private fun TimelineListItem.isAssistantWorkItem(): Boolean =
    timelineMessages().any { it.isCollapsibleAssistantWork() }

/** For a plain reply, place the duration once after its final chunk. Groups carry their own label. */
internal fun completedTimingFooters(items: List<TimelineListItem>): Map<String, String> {
    val grouped = mutableSetOf<String>()
    val lastReply = mutableMapOf<String, String>()
    for (item in items) {
        if (item is TimelineListItem.AssistantWorkGroup) {
            item.messages.firstNotNullOfOrNull { it.turnId }?.let(grouped::add)
            continue
        }
        val message = when (item) {
            is TimelineListItem.Single -> item.message
            is TimelineListItem.MessageChunk -> item.message
            else -> continue
        }
        if (message.role == CodexMessageRole.assistant && !message.isStreaming && message.turnId != null) {
            lastReply[message.turnId] = item.stableKey
        }
    }
    return lastReply.filterKeys { it !in grouped }.entries.associate { (turn, key) -> key to turn }
}
