package com.remodex.mobile.services

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Each load owns its slot; a manual refresh supersedes a stalled load on the same connection. */
internal class ThreadHistoryRequests {
    private val requests = ConcurrentHashMap<String, Any>()
    private val _activeThreadIds = MutableStateFlow<Set<String>>(emptySet())
    val activeThreadIds = _activeThreadIds.asStateFlow()

    @Synchronized
    fun begin(threadId: String, replace: Boolean): Any? {
        val ticket = Any()
        if (replace) requests[threadId] = ticket
        else if (requests.putIfAbsent(threadId, ticket) != null) return null
        _activeThreadIds.value = requests.keys.toSet()
        return ticket
    }

    fun requireCurrent(threadId: String, ticket: Any) {
        if (requests[threadId] !== ticket) throw CancellationException("History request superseded by refresh")
    }

    fun isCurrent(threadId: String, ticket: Any): Boolean = requests[threadId] === ticket
    @Synchronized
    fun finish(threadId: String, ticket: Any) {
        requests.remove(threadId, ticket)
        _activeThreadIds.value = requests.keys.toSet()
    }
    @Synchronized
    fun remove(threadId: String) {
        requests.remove(threadId)
        _activeThreadIds.value = requests.keys.toSet()
    }
    @Synchronized
    fun clear() {
        requests.clear()
        _activeThreadIds.value = emptySet()
    }
}
