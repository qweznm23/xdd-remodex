package com.remodex.mobile.data

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageDeliveryState
import com.remodex.mobile.core.model.CodexMessageKind
import com.remodex.mobile.core.model.CodexMessageRole

/**
 * Merges [thread/read] snapshots into the live timeline without duplicating stable rows.
 * Simplified vs iOS [CodexService.mergeHistoryMessages] but same keying idea.
 */
internal object HistoryMessageMerge {
    /** A page is an item window, not a complete turn. Keep same-turn page boundaries. */
    fun mergeItemPage(
        existing: List<CodexMessage>, incoming: List<CodexMessage>,
        older: Boolean = false, beforeMessageId: String? = null,
    ): List<CodexMessage> {
        val out = existing.toMutableList()
        fun same(a: CodexMessage, b: CodexMessage) = a.turnId == b.turnId &&
            a.role == b.role && a.kind == b.kind && !a.itemId.isNullOrBlank() && a.itemId == b.itemId
        incoming.forEachIndexed { position, row ->
            val exact = out.indexOfFirst { same(it, row) }
            val index = if (exact >= 0 || row.role != CodexMessageRole.user) exact
                else out.indexOfFirst { isCompatibleUserChatDuplicate(it, row) }
            if (index >= 0) {
                val current = out[index]
                // Reuse existing snapshot reconciliation, scoped to this single stable item.
                out[index] = merge(listOf(current), listOf(row)).first().let {
                    if (current.isStreaming && !it.isAssistantMessageComplete) it.copy(isStreaming = true) else it
                }
            } else {
                val following = incoming.drop(position + 1).firstNotNullOfOrNull { next ->
                    out.indexOfFirst { same(it, next) }.takeIf { it >= 0 }
                }
                val previous = incoming.take(position).asReversed().firstNotNullOfOrNull { prev ->
                    out.indexOfFirst { same(it, prev) }.takeIf { it >= 0 }?.plus(1)
                }
                val boundary = out.indexOfFirst { it.id == beforeMessageId }.takeIf { it >= 0 }
                val insertion = following ?: previous ?: if (older) boundary ?: 0 else out.size
                out.add(insertion, row)
            }
        }
        return normalize(applyIncomingTurnOrder(out, incoming))
    }

    /** Insert newly recovered turns relative to the overlapping server history window. */
    fun mergeRecentPage(existing: List<CodexMessage>, incoming: List<CodexMessage>): List<CodexMessage> {
        val merged = merge(existing, incoming)
        val known = existing.mapNotNull { it.turnId }.toSet()
        val order = incoming.mapNotNull { it.turnId }.distinct()
        val selected = order.toSet()
        val anchor = merged.indexOfFirst { it.turnId in known && it.turnId in selected }
        if (anchor < 0) return merged
        val insertion = merged.take(anchor).count { it.turnId !in selected }
        val retained = merged.filterNot { it.turnId in selected }.toMutableList()
        val byTurn = merged.groupBy { it.turnId }
        retained.addAll(insertion, order.flatMap { byTurn[it].orEmpty() })
        return retained
    }

    /** Place newly fetched older turns before the previous page boundary without moving cached/live turns. */
    fun mergeOlderPage(
        existing: List<CodexMessage>,
        incoming: List<CodexMessage>,
        beforeTurnId: String?,
    ): List<CodexMessage> {
        val merged = merge(existing, incoming)
        val knownTurns = existing.mapNotNull { it.turnId }.toSet()
        val newOlderTurns = incoming.mapNotNull { it.turnId }.toSet() - knownTurns
        val olderRows = merged.filter { it.turnId in newOlderTurns }
        if (olderRows.isEmpty()) return merged
        val retained = merged.filterNot { it.turnId in newOlderTurns }.toMutableList()
        val anchor = retained.indexOfFirst { it.turnId == beforeTurnId }.takeIf { it >= 0 } ?: 0
        retained.addAll(anchor, olderRows)
        return retained
    }

    fun normalize(messages: List<CodexMessage>): List<CodexMessage> =
        ConversationQuestionCodec.reconcile(messages.dedupeAssistantItemSnapshots().dedupeCompatibleUserChats())

    /** Repair cached duplicate rows created by a delta arriving after an item stopped streaming. */
    private fun List<CodexMessage>.dedupeAssistantItemSnapshots(): List<CodexMessage> {
        val out = ArrayList<CodexMessage>(size)
        val indexes = mutableMapOf<Triple<String, String?, String>, Int>()
        for (message in this) {
            val itemId = message.itemId?.takeIf { it.isNotBlank() }
            if (message.role != CodexMessageRole.assistant || message.kind != CodexMessageKind.chat || itemId == null) {
                out.add(message)
                continue
            }
            val key = Triple(message.threadId, message.turnId, itemId)
            val index = indexes[key]
            val previous = index?.let(out::get)
            // Only coalesce snapshots whose overlap is known. Ambiguous fragments remain until
            // official history supplies the full item; never discard text based on length alone.
            if (previous != null && (previous.text.startsWith(message.text) || previous.text.endsWith(message.text) ||
                    message.text.startsWith(previous.text) || message.text.endsWith(previous.text))) {
                out[index] = mergeAssistantChatDuplicate(previous, message)
            } else {
                indexes[key] = out.size
                out.add(message)
            }
        }
        return out
    }

    fun merge(
        existing: List<CodexMessage>,
        incoming: List<CodexMessage>,
    ): List<CodexMessage> {
        if (incoming.isEmpty()) return existing
        if (existing.isEmpty()) {
            return incoming
                .dedupeAssistantItemSnapshots()
                .dedupeCompatibleUserChats()
        }
        val merged = existing.toMutableList()
        val keys = merged.map { historyKey(it) }.toMutableSet()
        val additions = ArrayList<CodexMessage>()
        for (m in incoming) {
            if (m.role == CodexMessageRole.system &&
                m.kind == CodexMessageKind.thinking &&
                !m.turnId.isNullOrBlank()
            ) {
                val thinkingIndex =
                    merged.indexOfLast { existingMessage ->
                        existingMessage.role == CodexMessageRole.system &&
                            existingMessage.kind == CodexMessageKind.thinking &&
                            existingMessage.turnId == m.turnId &&
                            (existingMessage.itemId == m.itemId ||
                                (existingMessage.reasoningSummary.isEmpty() && m.reasoningSummary.isEmpty()))
                    }
                if (thinkingIndex >= 0) {
                    val current = merged[thinkingIndex]
                    keys.remove(historyKey(current))
                    val next = mergeSystemItemDuplicate(current, m)
                    merged[thinkingIndex] = next
                    keys.add(historyKey(next))
                    continue
                }
            }

            if (m.role == CodexMessageRole.system &&
                m.kind == CodexMessageKind.fileChange &&
                !m.turnId.isNullOrBlank()
            ) {
                val mergeIndex =
                    merged.indexOfLast { existingMessage ->
                        existingMessage.role == CodexMessageRole.system &&
                            existingMessage.kind == CodexMessageKind.fileChange &&
                            existingMessage.turnId == m.turnId &&
                            shouldMergeAdjacentFileChangeRows(existingMessage, m)
                    }
                if (mergeIndex >= 0) {
                    val current = merged[mergeIndex]
                    keys.remove(historyKey(current))
                    val next = mergeFileChangeDuplicate(current, m)
                    merged[mergeIndex] = next
                    keys.add(historyKey(next))
                    continue
                }
            }

            if (m.role == CodexMessageRole.system &&
                m.kind == CodexMessageKind.commandExecution &&
                !m.turnId.isNullOrBlank()
            ) {
                val incomingCommandKey = normalizedCommandExecutionPreviewKey(m.text)
                val commandIndexByPreview =
                    incomingCommandKey?.let { key ->
                        merged.indexOfLast { existingMessage ->
                            existingMessage.role == CodexMessageRole.system &&
                                existingMessage.kind == CodexMessageKind.commandExecution &&
                                existingMessage.turnId == m.turnId &&
                                normalizedCommandExecutionPreviewKey(existingMessage.text) == key
                        }
                    } ?: -1
                val commandIndex =
                    if (commandIndexByPreview >= 0) {
                        commandIndexByPreview
                    } else {
                        merged.indexOfLast { existingMessage ->
                            existingMessage.role == CodexMessageRole.system &&
                                existingMessage.kind == CodexMessageKind.commandExecution &&
                                existingMessage.turnId == m.turnId &&
                                shouldReconcileCommandByTurn(existingMessage, m)
                        }
                    }
                if (commandIndex >= 0) {
                    val current = merged[commandIndex]
                    keys.remove(historyKey(current))
                    val next = mergeSystemItemDuplicate(current, m)
                    merged[commandIndex] = next
                    keys.add(historyKey(next))
                    continue
                }
            }

            if (m.role == CodexMessageRole.assistant &&
                m.kind == CodexMessageKind.chat &&
                !m.turnId.isNullOrBlank()
            ) {
                val assistantIndex =
                    merged.indexOfLast { existingMessage ->
                        isCompatibleAssistantChatDuplicate(existingMessage, m)
                    }
                if (assistantIndex >= 0) {
                    val current = merged[assistantIndex]
                    keys.remove(historyKey(current))
                    val next = mergeAssistantChatDuplicate(current, m)
                    merged[assistantIndex] = next
                    keys.add(historyKey(next))
                    continue
                }
            }

            val duplicateFileChangeIndex = merged.indexOfLast { existingMessage ->
                isCompatibleFileChangeDuplicate(existingMessage, m)
            }
            if (duplicateFileChangeIndex >= 0) {
                val current = merged[duplicateFileChangeIndex]
                keys.remove(historyKey(current))
                val next = mergeFileChangeDuplicate(current, m)
                merged[duplicateFileChangeIndex] = next
                keys.add(historyKey(next))
                continue
            }

            val duplicateUserIndex = if (m.role == CodexMessageRole.user) {
                val exact = merged.indexOfFirst { it.turnId == m.turnId && it.role == m.role &&
                    m.itemId != null && it.itemId == m.itemId }
                if (exact >= 0) exact else merged.indexOfFirst { isCompatibleUserChatDuplicate(it, m) }
            } else -1
            if (duplicateUserIndex >= 0) {
                val current = merged[duplicateUserIndex]
                keys.remove(historyKey(current))
                val next = mergeUserChatDuplicate(current, m)
                merged[duplicateUserIndex] = next
                keys.add(historyKey(next))
                continue
            }

            val k = historyKey(m)
            if (k !in keys) {
                keys.add(k)
                additions.add(m)
            }
        }
        val mergedTimeline =
            applyIncomingTurnOrder(
                messages = mergeLateAdditionsIntoTurnPositions(merged, additions),
                incoming = incoming,
            ).dedupeAssistantItemSnapshots().dedupeCompatibleUserChats()
        if (additions.isEmpty() && mergedTimeline == existing) return existing
        return mergedTimeline
    }

    private fun applyIncomingTurnOrder(
        messages: List<CodexMessage>,
        incoming: List<CodexMessage>,
    ): List<CodexMessage> {
        val incomingByTurn =
            incoming
                .mapNotNull { message ->
                    message.turnId?.trim()?.takeIf { it.isNotEmpty() }?.let { it to message }
                }
                .groupBy({ it.first }, { it.second })
        if (incomingByTurn.isEmpty()) return messages

        var out = messages
        for ((turnId, turnHistory) in incomingByTurn) {
            val indexed = out.withIndex().filter { it.value.turnId == turnId }
            if (indexed.size < 2) continue
            val ranked =
                indexed.map { entry ->
                    RankedTurnMessage(
                        message = entry.value,
                        rank = turnHistoryRank(entry.value, turnHistory),
                    )
                }
            val reorderable = ranked.filter { it.rank != Int.MAX_VALUE }
            if (reorderable.size < 2) continue

            val orderedMatches =
                reorderable
                    .sortedWith(
                        compareBy<RankedTurnMessage> { it.rank },
                    )
                    .map { it.message }
                    .iterator()
            val orderedTurn =
                ranked.map { rankedMessage ->
                    if (rankedMessage.rank == Int.MAX_VALUE) {
                        rankedMessage.message
                    } else {
                        orderedMatches.next()
                    }
                }
            val rebuilt = out.toMutableList()
            indexed.forEachIndexed { position, entry -> rebuilt[entry.index] = orderedTurn[position] }
            out = rebuilt
        }
        return out
    }

    private data class RankedTurnMessage(
        val message: CodexMessage,
        val rank: Int,
    )

    private fun turnHistoryRank(
        message: CodexMessage,
        turnHistory: List<CodexMessage>,
    ): Int =
        turnHistory.indexOfFirst { history -> isSameHistoryItem(message, history) }
            .takeIf { it >= 0 }
            ?: Int.MAX_VALUE

    private fun isSameHistoryItem(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): Boolean {
        if (existing.role != incoming.role || existing.kind != incoming.kind) return false
        val existingItem = existing.itemId?.trim()?.takeIf { it.isNotEmpty() }
        val incomingItem = incoming.itemId?.trim()?.takeIf { it.isNotEmpty() }
        if (existingItem != null && incomingItem != null) return existingItem == incomingItem
        if (existing.role == CodexMessageRole.user && existing.kind == CodexMessageKind.chat) {
            return isCompatibleUserChatDuplicate(existing, incoming)
        }
        if (existing.role == CodexMessageRole.system && existing.kind == CodexMessageKind.commandExecution) {
            val existingCommand = normalizedCommandExecutionPreviewKey(existing.text)
            val incomingCommand = normalizedCommandExecutionPreviewKey(incoming.text)
            if (existingCommand != null && existingCommand == incomingCommand) return true
        }
        return normalizedMessageText(existing.text) == normalizedMessageText(incoming.text)
    }

    private fun mergeLateAdditionsIntoTurnPositions(
        existing: List<CodexMessage>,
        additions: List<CodexMessage>,
    ): List<CodexMessage> {
        if (additions.isEmpty()) return existing
        val out = existing.toMutableList()
        additions.forEach { addition ->
            val insertionIndex = turnAwareInsertionIndex(out, addition)
            out.add(insertionIndex, addition)
        }
        return out
    }

    private fun turnAwareInsertionIndex(
        messages: List<CodexMessage>,
        incoming: CodexMessage,
    ): Int {
        val turnId = incoming.turnId?.trim()?.takeIf { it.isNotEmpty() } ?: return messages.size
        if (incoming.role == CodexMessageRole.user && incoming.kind == CodexMessageKind.chat) {
            val firstSameTurnNonUser =
                messages.indexOfFirst { message ->
                    message.turnId == turnId && message.role != CodexMessageRole.user
                }
            if (firstSameTurnNonUser >= 0 && messages.none { it.turnId == turnId && it.role == CodexMessageRole.user })
                return firstSameTurnNonUser
        }
        val lastSameTurn =
            messages.indexOfLast { message -> message.turnId == turnId }
        return if (lastSameTurn >= 0) lastSameTurn + 1 else messages.size
    }

    private fun historyKey(m: CodexMessage): String {
        val item = m.itemId?.trim()?.takeIf { it.isNotEmpty() }
        if (item != null) {
            return "item:${m.turnId}:${m.role}:${m.kind}:$item"
        }
        val normalizedText = normalizedMessageText(m.text)
        return "${m.role}|${m.kind}|${m.turnId}|${m.createdAt}|${normalizedText.length}:${normalizedText.hashCode()}"
    }

    internal fun isCompatibleUserChatDuplicate(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): Boolean {
        if (existing.role != CodexMessageRole.user || incoming.role != CodexMessageRole.user) return false
        if (existing.isRpcAnswerReceipt || incoming.isRpcAnswerReceipt) return existing.id == incoming.id
        if (existing.kind != CodexMessageKind.chat || incoming.kind != CodexMessageKind.chat) return false
        if (normalizedMessageText(existing.text) != normalizedMessageText(incoming.text)) return false
        if (!isUserChatMergeEligible(existing, incoming)) return false
        return compatibleAttachments(existing, incoming)
    }

    private fun isUserChatMergeEligible(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): Boolean {
        val existingItem = existing.itemId?.trim()?.takeIf { it.isNotEmpty() }
        val incomingItem = incoming.itemId?.trim()?.takeIf { it.isNotEmpty() }
        val existingTurn = existing.turnId?.trim()?.takeIf { it.isNotEmpty() }
        val incomingTurn = incoming.turnId?.trim()?.takeIf { it.isNotEmpty() }
        if (!compatibleTurnIds(existingTurn, incomingTurn)) return false
        if (existing.userMirrorId != null && incoming.userMirrorId != null &&
            existing.userMirrorId != incoming.userMirrorId) return false
        if (existingItem != null && incomingItem != null) {
            if (existingItem == incomingItem) return true
            // Desktop IPC may synthesize the opening item before the canonical snapshot arrives.
            return existingTurn != null && existingTurn == incomingTurn &&
                (existingItem == "$existingTurn:input" || incomingItem == "$existingTurn:input")
        }

        if (existingTurn != null && incomingTurn != null) return existingTurn == incomingTurn

        val hasPendingLocal =
            existing.deliveryState == CodexMessageDeliveryState.pending ||
                incoming.deliveryState == CodexMessageDeliveryState.pending
        return hasPendingLocal && compatibleTurnIds(existing.turnId, incoming.turnId)
    }

    private fun mergeUserChatDuplicate(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): CodexMessage =
        existing.copy(
            text = incoming.text.ifBlank { existing.text },
            turnId = incoming.turnId ?: existing.turnId,
            itemId = if (incoming.itemId == "${incoming.turnId}:input") existing.itemId ?: incoming.itemId
                else incoming.itemId ?: existing.itemId,
            userMirrorId = incoming.userMirrorId ?: existing.userMirrorId,
            isStreaming = false,
            deliveryState = CodexMessageDeliveryState.confirmed,
            attachments =
                UserChatAttachmentMatcher.merge(existing.attachments, incoming.attachments),
        )

    private fun isCompatibleAssistantChatDuplicate(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): Boolean {
        if (existing.role != CodexMessageRole.assistant || incoming.role != CodexMessageRole.assistant) return false
        if (existing.kind != CodexMessageKind.chat || incoming.kind != CodexMessageKind.chat) return false
        if (!compatibleTurnIds(existing.turnId, incoming.turnId)) return false

        val existingItem = existing.itemId?.trim()?.takeIf { it.isNotEmpty() }
        val incomingItem = incoming.itemId?.trim()?.takeIf { it.isNotEmpty() }
        if (existingItem != null && incomingItem != null && existingItem == incomingItem) return true

        val existingText = normalizedMessageText(existing.text)
        val incomingText = normalizedMessageText(incoming.text)
        if (existingText.isEmpty() || incomingText.isEmpty()) return existing.isStreaming || incoming.isStreaming
        if (existingText == incomingText) return true
        return (existing.isStreaming || incoming.isStreaming) &&
            (incomingText.startsWith(existingText) || existingText.startsWith(incomingText))
    }

    private fun mergeAssistantChatDuplicate(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): CodexMessage =
        existing.copy(
            text = mergeSnapshot(existing.text, incoming.text),
            assistantPhase = incoming.assistantPhase ?: existing.assistantPhase,
            structuredUserInputRequest = incoming.structuredUserInputRequest?.let { ConversationQuestionCodec.mergeRequest(existing.structuredUserInputRequest, it) } ?: existing.structuredUserInputRequest,
            turnId = incoming.turnId ?: existing.turnId,
            itemId = incoming.itemId ?: existing.itemId,
            isStreaming = incoming.isStreaming && !existing.isAssistantMessageComplete && !incoming.isAssistantMessageComplete,
            isAssistantMessageComplete = existing.isAssistantMessageComplete || incoming.isAssistantMessageComplete,
            deliveryState = CodexMessageDeliveryState.confirmed,
            attachments =
                if (incoming.attachments.isNotEmpty()) {
                    incoming.attachments
                } else {
                    existing.attachments
                },
        )

    private fun mergeSystemItemDuplicate(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): CodexMessage =
        existing.copy(
            reasoningSummary = existing.reasoningSummary + incoming.reasoningSummary.mapValues { (index, value) ->
                existing.reasoningSummary[index]?.takeIf { it.startsWith(value) } ?: value
            },
            text = mergeSnapshot(existing.text, incoming.text),
            turnId = incoming.turnId ?: existing.turnId,
            itemId = incoming.itemId ?: existing.itemId,
            isStreaming = false,
            deliveryState = CodexMessageDeliveryState.confirmed,
        )

    private fun isCompatibleFileChangeDuplicate(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): Boolean {
        if (existing.role != CodexMessageRole.system || incoming.role != CodexMessageRole.system) return false
        if (existing.kind != CodexMessageKind.fileChange || incoming.kind != CodexMessageKind.fileChange) return false
        val existingItem = existing.itemId?.trim()?.takeIf { it.isNotEmpty() }
        val incomingItem = incoming.itemId?.trim()?.takeIf { it.isNotEmpty() }
        if (existingItem != null && incomingItem != null) return existingItem == incomingItem
        return compatibleTurnIds(existing.turnId, incoming.turnId) &&
            (isFileChangePlaceholder(existing.text) || isFileChangePlaceholder(incoming.text))
    }

    private fun shouldMergeAdjacentFileChangeRows(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): Boolean =
        existing.isStreaming ||
            incoming.isStreaming ||
            isFileChangePlaceholder(existing.text) ||
            isFileChangePlaceholder(incoming.text)

    private fun mergeFileChangeDuplicate(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): CodexMessage {
        val existingStructured = isStructuredFileChange(existing.text)
        val incomingStructured = isStructuredFileChange(incoming.text)
        val text =
            when {
                existingStructured && isFileChangePlaceholder(incoming.text) -> existing.text
                incomingStructured && isFileChangePlaceholder(existing.text) -> incoming.text
                incomingStructured && !existingStructured -> incoming.text
                existingStructured && !incomingStructured -> existing.text
                incoming.text.isNotBlank() -> incoming.text
                else -> existing.text
            }
        return existing.copy(
            text = text,
            turnId = incoming.turnId ?: existing.turnId,
            itemId = incoming.itemId ?: existing.itemId,
            isStreaming = false,
        )
    }

    private fun List<CodexMessage>.dedupeCompatibleUserChats(): List<CodexMessage> {
        if (size < 2) return this
        val result = mutableListOf<CodexMessage>()
        for (message in this) {
            val duplicateIndex =
                result.indexOfFirst { candidate ->
                    isCompatibleUserChatDuplicate(candidate, message)
                }
            if (duplicateIndex >= 0) {
                result[duplicateIndex] = mergeUserChatDuplicate(result[duplicateIndex], message)
            } else {
                result.add(message)
            }
        }
        return result
    }

    private fun compatibleTurnIds(
        existing: String?,
        incoming: String?,
    ): Boolean {
        val existingTurn = existing?.trim()?.takeIf { it.isNotEmpty() }
        val incomingTurn = incoming?.trim()?.takeIf { it.isNotEmpty() }
        return existingTurn == null || incomingTurn == null || existingTurn == incomingTurn
    }

    private fun compatibleAttachments(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): Boolean =
        UserChatAttachmentMatcher.compatible(existing.attachments, incoming.attachments)

    private fun normalizedMessageText(text: String): String =
        text.trim().replace("\\s+".toRegex(), " ")

    private fun isFileChangePlaceholder(text: String): Boolean =
        text.trim().equals("[file change]", ignoreCase = true) ||
            text.trim().equals("file change", ignoreCase = true)

    private fun isStructuredFileChange(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.contains("\nPath:", ignoreCase = true) || t.startsWith("Path:", ignoreCase = true)) return true
        if (t.contains("\nTotals:", ignoreCase = true) || t.startsWith("Totals:", ignoreCase = true)) return true
        if (t.contains("```diff", ignoreCase = true)) return true
        if (t.contains("diff --git")) return true
        return false
    }

    private fun shouldReconcileCommandByTurn(
        existing: CodexMessage,
        incoming: CodexMessage,
    ): Boolean {
        val existingItem = existing.itemId?.trim()?.takeIf { it.isNotEmpty() }
        val incomingItem = incoming.itemId?.trim()?.takeIf { it.isNotEmpty() }
        return existing.isStreaming ||
            incoming.isStreaming ||
            existingItem == null ||
            incomingItem == null ||
            isSyntheticTurnScopedItemId(existingItem) ||
            isSyntheticTurnScopedItemId(incomingItem)
    }

    private fun isSyntheticTurnScopedItemId(itemId: String?): Boolean =
        itemId?.startsWith("turn:", ignoreCase = true) == true &&
            itemId.contains("|kind:", ignoreCase = true)

    private fun normalizedCommandExecutionPreviewKey(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val withoutPhase =
            trimmed.replaceFirst(
                Regex("""^(running|completed|failed|stopped)\s*>?\s*""", RegexOption.IGNORE_CASE),
                "",
            )
        val commandTokens = withoutPhase.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val normalized =
            commandTokens
                .joinToString(" ") { token -> token.trim().trim('"', '\'') }
                .replace("\\s+".toRegex(), " ")
                .lowercase()
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun mergeSnapshot(
        existing: String,
        incoming: String,
    ): String {
        if (existing.isEmpty()) return incoming
        if (incoming.isEmpty()) return existing
        if (incoming == existing) return existing
        if (existing.endsWith(incoming)) return existing
        if (incoming.length > existing.length && incoming.startsWith(existing)) return incoming
        if (existing.length > incoming.length && existing.startsWith(incoming)) return existing
        return incoming
    }
}

/** Rebind a synthetic turn only when stable item identities agree one-to-one on its canonical turn. */
internal fun reconcileDesktopTurnAliases(cached: List<CodexMessage>, page: List<CodexMessage>): List<CodexMessage> {
    if (cached.none { it.turnId?.startsWith("ipc-turn-") == true }) return cached
    fun identity(row: CodexMessage) = Triple(row.itemId, row.role, row.kind)
    val canonical = page.filter { !it.turnId.isNullOrBlank() && !it.turnId.startsWith("ipc-turn-") && !it.itemId.isNullOrBlank() }
        .groupBy(::identity)
    val aliases = cached.filter { it.turnId?.startsWith("ipc-turn-") == true }.groupBy { it.turnId }
        .mapValues { (_, rows) -> rows.flatMap { row -> canonical[identity(row)].orEmpty().mapNotNull { it.turnId } }.distinct().singleOrNull() }
    val owners = aliases.values.filterNotNull().groupingBy { it }.eachCount()
    val existing = cached.filter { !it.turnId.isNullOrBlank() && !it.turnId.startsWith("ipc-turn-") && !it.itemId.isNullOrBlank() }
        .mapTo(hashSetOf()) { it.turnId to identity(it) }
    return cached.mapNotNull { row ->
        val target = aliases[row.turnId]
        when {
            target == null || owners[target] != 1 -> row
            row.deliveryState == com.remodex.mobile.core.model.CodexMessageDeliveryState.confirmed &&
                (target to identity(row)) in existing -> null
            else -> row.copy(turnId = target)
        }
    }
}
