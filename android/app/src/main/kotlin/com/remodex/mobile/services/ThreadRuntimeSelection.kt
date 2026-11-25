package com.remodex.mobile.services

import com.remodex.mobile.core.model.CodexModelOption
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.JSONValue

internal data class ThreadModelSelection(val modelId: String?, val reasoningEffort: String?)

/** Explicit mobile choices belong to their conversation; desktop metadata is otherwise authoritative. */
internal class ThreadRuntimeSelection(var defaults: ThreadModelSelection) {
    private val overrides = mutableMapOf<String, ThreadModelSelection>()

    private data class Revision(val epoch: String, val revision: Long, val timestamp: Long)
    private val revisions = mutableMapOf<String, Revision>()
    private val reportedEfforts = mutableSetOf<String>()
    private val fastThreads = mutableSetOf<String>()
    @Synchronized fun fastTier(id: String?): Boolean = id in fastThreads

    @Synchronized fun confirm(id: String, current: ThreadModelSelection, row: Map<String, JSONValue>): Boolean {
        val next = Revision(row["epoch"]?.stringValue ?: return false, row["revision"]?.longValue ?: return false,
            row["updatedAt"]?.longValue ?: return false)
        val prev = revisions[id]
        if (prev != null && (if (prev.epoch == next.epoch) next.revision <= prev.revision else next.timestamp <= prev.timestamp)) return false
        revisions[id] = next
        val previousSelection = overrides[id] ?: current
        overrides[id] = ThreadModelSelection(if ("model" in row) row["model"]?.stringValue else previousSelection.modelId,
            if ("reasoningEffort" in row) row["reasoningEffort"]?.stringValue else previousSelection.reasoningEffort)
        if ("reasoningEffort" in row) reportedEfforts.add(id)
        if ("serviceTier" in row) {
            if (row["serviceTier"]?.stringValue in setOf("priority", "fast")) fastThreads.add(id) else fastThreads.remove(id)
        }
        return true
    }

    @Synchronized
    fun resolve(threadId: String?, threads: List<CodexThread>, models: List<CodexModelOption>): ThreadModelSelection {
        val thread = threads.firstOrNull { it.id == threadId }
        val selected = overrides[threadId]
            ?: thread?.model?.takeIf { it.isNotBlank() }?.let { ThreadModelSelection(it, thread.reasoningEffort) }
            ?: defaults
        val selectedId = selected.modelId?.trim()?.takeIf { it.isNotEmpty() }
        val model = if (selectedId != null) {
            models.firstOrNull { it.id == selectedId || it.model == selectedId }
        } else {
            models.firstOrNull { it.isDefault } ?: models.firstOrNull()
        }
        val supported = model?.supportedReasoningEfforts.orEmpty().map { it.reasoningEffort }
        val effort = selected.reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedEffort = when {
            threadId in reportedEfforts -> effort
            supported.isEmpty() -> effort
            effort in supported -> effort
            model?.defaultReasoningEffort in supported -> model?.defaultReasoningEffort
            "medium" in supported -> "medium"
            else -> supported.firstOrNull()
        }
        return ThreadModelSelection(model?.model?.takeIf { it.isNotBlank() } ?: selectedId ?: model?.id, resolvedEffort)
    }

    @Synchronized
    fun select(threadId: String?, selection: ThreadModelSelection) {
        defaults = selection
        if (threadId != null) overrides[threadId] = selection
    }

    @Synchronized
    fun reset(selection: ThreadModelSelection) {
        overrides.clear()
        revisions.clear()
        fastThreads.clear()
        reportedEfforts.clear()
        defaults = selection
    }
}
