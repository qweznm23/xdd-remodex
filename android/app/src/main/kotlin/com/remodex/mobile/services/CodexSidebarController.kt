package com.remodex.mobile.services

import com.remodex.mobile.core.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory server snapshot only. No phone visit order, local pins, or inferred unread markers. */
internal class CodexSidebarController {
    class Request internal constructor(val device: String?) {
        internal val changed = mutableSetOf<String>()
        internal var sectionsChanged = false
    }
    private val lock = Any()
    private val requests = mutableSetOf<Request>()
    private val mutableState = MutableStateFlow(CodexSidebarState())
    val state = mutableState.asStateFlow()
    fun reset(device: String?) = synchronized(lock) { requests.clear(); mutableState.value = CodexSidebarState(device) }
    fun begin(device: String?): Request = synchronized(lock) {
        if (mutableState.value.deviceId != device) reset(device)
        Request(device).also { requests += it }
    }
    fun finish(request: Request) = synchronized(lock) { requests.remove(request); Unit }
    fun apply(request: Request, snapshot: CodexSidebarState) = synchronized(lock) {
        if (request !in requests || request.device != mutableState.value.deviceId) return@synchronized
        val current = mutableState.value
        val receipts = snapshot.threads.toMutableMap()
        request.changed.forEach { id -> current.threads[id]?.let { receipts[id] = it } ?: receipts.remove(id) }
        mutableState.value = snapshot.copy(
            deviceId = request.device, threads = receipts,
            pinnedSectionId = if (request.sectionsChanged) current.pinnedSectionId else snapshot.pinnedSectionId,
            pinnedThreadIds = if (request.sectionsChanged) current.pinnedThreadIds else snapshot.pinnedThreadIds,
        )
    }
    fun receipt(device: String?, threadId: String, receipt: CodexThreadReadState?, request: Request? = null) = synchronized(lock) {
        if (device != mutableState.value.deviceId) return@synchronized
        if (request != null && (request !in requests || threadId in request.changed)) return@synchronized
        // A duplicate metadata snapshot is not a newer event and must not discard
        // the read acknowledgement already in flight for this same revision.
        if (mutableState.value.threads[threadId] == receipt) return@synchronized
        requests.forEach { it.changed += threadId }
        val current = mutableState.value
        mutableState.value = current.copy(threads = if (receipt == null) current.threads - threadId else current.threads + (threadId to receipt))
    }
    fun sectionsChanged() = synchronized(lock) { requests.forEach { it.sectionsChanged = true } }
}
