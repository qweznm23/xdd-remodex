package com.remodex.mobile.services

import com.remodex.mobile.data.ComposerPrediction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The server can explicitly skip a turn. Cache that outcome just like a suggestion. */
internal class ComposerPredictions {
    class Request(val thread: String, val turn: String)
    private class Entry(val request: Request, var finished: Boolean = false, var failed: Boolean = false, var text: String? = null, var typed: Boolean = false)
    private val entries = LinkedHashMap<String, Entry>()
    private val activeTurns = mutableMapOf<String, String>()
    private class Check(var typed: Boolean = false)
    private val checks = mutableMapOf<String, Check>()
    private val visible = MutableStateFlow<Map<String, ComposerPrediction>>(emptyMap())
    val suggestions = visible.asStateFlow()

    @Synchronized fun disconnected() {
        entries.clear()
        activeTurns.clear()
        checks.clear()
        visible.value = emptyMap()
    }

    @Synchronized fun beginCheck(thread: String): Any = Check(checks[thread]?.typed == true).also {
        checks[thread] = it
        visible.value = visible.value - thread
    }

    @Synchronized fun abandonCheck(thread: String, check: Any) {
        if (checks[thread] === check) checks.remove(thread)
    }

    @Synchronized fun checked(thread: String, check: Any, completedTurn: String?): Request? {
        if (checks[thread] !== check) return null
        val typed = checks.remove(thread)?.typed == true
        if (completedTurn == null) {
            invalidate(thread)
            return null
        }
        activeTurns.remove(thread)
        if (entries[thread]?.failed == true) invalidate(thread)
        val request = completed(thread, completedTurn)
        if (typed) typing(thread)
        return request
    }

    @Synchronized fun started(thread: String, turn: String?) {
        invalidate(thread)
        if (turn != null) activeTurns[thread] = turn
    }

    @Synchronized fun invalidate(thread: String) {
        activeTurns.remove(thread)
        entries.remove(thread)
        checks.remove(thread)
        visible.value = visible.value - thread
    }

    @Synchronized fun completed(thread: String, turn: String): Request? {
        val active = activeTurns[thread]
        if (active != null && active != turn) return null
        activeTurns.remove(thread)
        checks.remove(thread)
        val previous = entries[thread]
        if (previous?.request?.turn == turn) {
            previous.text?.let { visible.value = visible.value + (thread to ComposerPrediction(turn, it, previous.typed)) }
            return null
        }
        invalidate(thread)
        if (entries.size >= 128) invalidate(entries.keys.first())
        return Request(thread, turn).also { entries[thread] = Entry(it) }
    }

    @Synchronized fun fail(request: Request) {
        if (pending(request)) {
            entries.getValue(request.thread).failed = true
            entries.getValue(request.thread).finished = true
        }
    }

    @Synchronized fun pending(request: Request): Boolean =
        entries[request.thread]?.let { it.request === request && !it.finished } == true

    @Synchronized fun finish(request: Request, text: String?) {
        if (!pending(request)) return
        val entry = entries.getValue(request.thread)
        entry.finished = true
        entry.text = text?.trim()?.takeIf { it.isNotEmpty() }
        if (!checks.containsKey(request.thread)) entry.text?.let {
            visible.value = visible.value + (request.thread to ComposerPrediction(request.turn, it, entry.typed))
        }
    }

    @Synchronized fun typing(thread: String) {
        checks[thread]?.typed = true
        val entry = entries[thread] ?: return
        entry.typed = true
        visible.value[thread]?.let { visible.value = visible.value + (thread to it.copy(collapsedByTyping = true)) }
    }

    @Synchronized fun dismiss(thread: String, turn: String) {
        val entry = entries[thread] ?: return
        if (entry.request.turn != turn) return
        entry.finished = true
        entry.text = null
        visible.value = visible.value - thread
    }
}
