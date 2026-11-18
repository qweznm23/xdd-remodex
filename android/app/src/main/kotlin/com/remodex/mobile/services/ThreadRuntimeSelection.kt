package com.remodex.mobile.services

import com.remodex.mobile.core.model.CodexModelOption
import com.remodex.mobile.core.model.CodexThread

internal data class ThreadModelSelection(val modelId: String?, val reasoningEffort: String?)

/** Explicit mobile choices belong to their conversation; desktop metadata is otherwise authoritative. */
internal class ThreadRuntimeSelection(var defaults: ThreadModelSelection) {
    private val overrides = mutableMapOf<String, ThreadModelSelection>()

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
        defaults = selection
    }
}
