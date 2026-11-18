package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.ContextWindowUsageCodec
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.ThreadHistoryPaginationState
import com.remodex.mobile.core.model.isExplicitServerThreadMissing
import com.remodex.mobile.data.ThreadHistoryDecoder
import com.remodex.mobile.data.RunningThreadRefreshPolicy
import com.remodex.mobile.data.ThreadTurnRecovery
import com.remodex.mobile.data.ThreadTurnRecoveryAction
import com.remodex.mobile.data.ThreadTurnSnapshot
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch

/**
 * Mirrors [CodexService+History.swift](../../../../../../../../CodexMobile/CodexMobile/Services/CodexService+History.swift).
 */
internal suspend fun CodexService.syncThreadHistoryInternal(
    threadId: String,
    force: Boolean,
    replaceInFlight: Boolean = false,
) {
    if (!sessionReady) return
    val tid = threadId.trim().ifEmpty { return }
    if (!force && hydratedThreadIds.contains(tid)) return
    val generation = connectionGeneration
    val ticket = loadingHistory.begin(tid, replaceInFlight) ?: return
    val previousTail = messagesByThread.value[tid].orEmpty().lastOrNull {
        !it.itemId.isNullOrBlank() && !it.turnId.isNullOrBlank() &&
            it.deliveryState == com.remodex.mobile.core.model.CodexMessageDeliveryState.confirmed
    }?.let { it.turnId!! to it.itemId!! }
    fun requireCurrentRequest() {
        requireHistoryConnection(generation)
        loadingHistory.requireCurrent(tid, ticket)
    }
    val sidebarRequest = codexSidebar.begin(resolvedMacScopedPersistenceDeviceId())
    var activityRevision: String? = null
    var stage = "thread/read metadata"
    _olderHistoryErrorByThread.value = _olderHistoryErrorByThread.value - tid
    try {
        val metadata = fetchThreadMetadata(tid) { method, params ->
            sendRequestImpl(method, params).also { response ->
                requireCurrentRequest()
                val receipt = com.remodex.mobile.core.model.CodexThreadReadState.decode(response.result?.objectValue?.get("readState"))
                activityRevision = receipt?.revision
                codexSidebar.receipt(resolvedMacScopedPersistenceDeviceId(), tid, receipt, sidebarRequest)
            }
        }
        requireCurrentRequest()
        val decoded = CodexThread.fromJsonObject(jsonObjectFromRpc(metadata))
        publishThreads(upsertThreadRow(_threads.value, applyPersistedThreadRename(applyAuthoritativeProjectPathToServerThread(decoded))))
        stage = "thread/items/list initial"
        val page = fetchRecentThreadHistory(tid) { method, params ->
            requireCurrentRequest()
            sendRequestImpl(method, params).also { requireCurrentRequest() }
        }
        stage = "initial history decode/merge"
        val threadObject = page.threadObject(metadata.map)
        val messages = ThreadHistoryDecoder.decodeFromThreadRead(tid, threadObject)
        requireCurrentRequest()
        if (messages.isNotEmpty()) messageTimelineStore.mergeThreadHistory(
            tid, messages, isItemPage = page.itemPage, requireCurrentRequest = ::requireCurrentRequest,
        )
        requireCurrentRequest()
        if (!page.itemPage) rehydrateRunningTurnFromThreadRead(tid, threadObject)
        ContextWindowUsageCodec.decodeFromThreadReadThreadObject(threadObject)?.let { applyLiveContextWindowUsage(tid, it) }
        markThreadHistoryPage(tid, page, messages.firstOrNull()?.turnId, messages.firstOrNull()?.id, initialLoad = true, activityRevision = activityRevision)
        hydratedThreadIds.add(tid)
        if (page.itemPage && previousTail != null && previousTail !in page.itemKeys && page.nextCursor != null) {
            // First page is already visible. Fill a reconnect gap in the background
            // until the native window meets cached history; never block the first paint.
            val revision = _threadHistoryPaginationByThread.value[tid]?.initialLoadRevision
            scope.launch {
                var anchor = page.itemKeys.firstNotNullOfOrNull { (turn, item) ->
                    messagesByThread.value[tid]?.firstOrNull { it.turnId == turn && it.itemId == item }?.id
                }
                fun requireCurrentGap() {
                    requireHistoryConnection(generation)
                    if (activeThreadId.value != tid || _threadHistoryPaginationByThread.value[tid]?.initialLoadRevision != revision) {
                        throw kotlinx.coroutines.CancellationException("Recent history gap request superseded")
                    }
                }
                try {
                    // Gap catch-up uses its own cursor, never the user's oldest loaded boundary.
                    walkRecentHistoryGap(page.nextCursor, previousTail, requireCurrent = ::requireCurrentGap,
                        fetch = { cursor -> fetchThreadItemPage(tid, cursor) { method, params -> sendRequestImpl(method, params) } },
                        merge = { gapPage ->
                            val rows = ThreadHistoryDecoder.decodeFromThreadRead(tid, gapPage.threadObject(emptyMap()))
                            if (rows.isNotEmpty()) messageTimelineStore.mergeThreadHistory(
                                tid, rows, isItemPage = true, isOlderPage = true, olderPageBeforeMessageId = anchor,
                                requireCurrentRequest = ::requireCurrentGap,
                            )
                            anchor = gapPage.itemKeys.firstNotNullOfOrNull { (turn, item) ->
                                messagesByThread.value[tid]?.firstOrNull { it.turnId == turn && it.itemId == item }?.id
                            } ?: anchor
                        },
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    try { requireCurrentGap() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) {
                        cancelled.initCause(failure)
                        throw cancelled
                    }
                    recordThreadHistoryFailure(tid, "thread/items/list recent-gap", failure)
                }
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!sessionReady || connectionGeneration != generation || !loadingHistory.isCurrent(tid, ticket)) {
            throw kotlinx.coroutines.CancellationException("Replaced history request failed at $stage").apply { initCause(e) }
        }
        if (e is CodexServiceError.RpcFailure && e.rpcError.isExplicitServerThreadMissing()) {
            handleMissingThread(tid)
        }
        recordThreadHistoryFailure(tid, stage, e)
        throw e
    } finally {
        codexSidebar.finish(sidebarRequest)
        loadingHistory.finish(tid, ticket)
    }
}

private fun CodexService.requireHistoryConnection(generation: Long) {
    if (!sessionReady || connectionGeneration != generation) throw kotlinx.coroutines.CancellationException("History request belongs to a replaced connection")
}

internal fun CodexService.rehydrateRunningTurnFromThreadRead(
    threadId: String,
    threadObject: Map<String, JSONValue>,
) {
    val tid = threadId.trim().ifEmpty { return }
    val snapshot = ThreadTurnSnapshot.fromThreadObject(threadObject)
    when (val action = ThreadTurnRecovery.actionFor(snapshot)) {
        is ThreadTurnRecoveryAction.Running -> noteTurnStarted(tid, action.turnId)
        ThreadTurnRecoveryAction.ProtectedFallback -> noteProtectedRunningFallback(tid, true)
        ThreadTurnRecoveryAction.Idle -> {
            // Avoid clearing local live state from a stale thread/read response. Reconnect resets local
            // running state first, so absence here naturally means "nothing to restore".
        }
    }
}

internal suspend fun CodexService.loadOlderThreadHistoryInternal(threadId: String) {
    if (!sessionReady) return
    val tid = threadId.trim().ifEmpty { return }
    val generation = connectionGeneration
    val pageState = _threadHistoryPaginationByThread.value[tid]
    if (pageState?.initialTurnsLoaded != true) {
        syncThreadHistoryInternal(tid, force = true)
        return
    }
    val cursor = pageState.olderCursor ?: return
    if (cursor == JSONValue.Null || pageState.hasAuthoritativeLocalHistoryStart) return
    if (tid in _loadingOlderHistoryThreadIds.value) return
    _loadingOlderHistoryThreadIds.value = _loadingOlderHistoryThreadIds.value + tid
    _olderHistoryErrorByThread.value = _olderHistoryErrorByThread.value - tid
    fun requireCurrentOlderPage() {
        requireHistoryConnection(generation)
        if (_threadHistoryPaginationByThread.value[tid] !== pageState) {
            throw kotlinx.coroutines.CancellationException("Older history page superseded by refresh")
        }
    }
    var stage = if (pageState.usesItemPagination) "thread/items/list older" else "thread/turns/list older"
    try {
        val page = if (pageState.usesItemPagination) fetchThreadItemPage(tid, cursor) { method, params -> sendRequestImpl(method, params) }
            else fetchThreadHistoryPage(tid, cursor) { method, params -> sendRequestImpl(method, params) }
        requireCurrentOlderPage()
        stage = "older history decode/merge"
        val messages = ThreadHistoryDecoder.decodeFromThreadRead(tid, page.threadObject(emptyMap()))
        if (messages.isNotEmpty()) {
            messageTimelineStore.mergeThreadHistory(tid, messages, olderPageBeforeTurnId = pageState.olderPageAnchorTurnId,
                isOlderPage = true, isItemPage = page.itemPage, olderPageBeforeMessageId = pageState.olderPageAnchorMessageId,
                requireCurrentRequest = ::requireCurrentOlderPage)
        }
        requireCurrentOlderPage()
        markThreadHistoryPage(tid, page, messages.firstOrNull()?.turnId, messages.firstOrNull()?.id, initialLoad = false)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!sessionReady || connectionGeneration != generation || _threadHistoryPaginationByThread.value[tid] !== pageState) {
            throw kotlinx.coroutines.CancellationException("Replaced older history request failed at $stage").apply { initCause(e) }
        }
        recordThreadHistoryFailure(tid, stage, e)
        throw e
    } finally {
        if (connectionGeneration == generation) _loadingOlderHistoryThreadIds.value = _loadingOlderHistoryThreadIds.value - tid
    }
}

private fun CodexService.markThreadHistoryPage(
    threadId: String,
    page: ThreadHistoryPage,
    oldestLoadedTurnId: String?,
    oldestMessageId: String?,
    initialLoad: Boolean,
    activityRevision: String? = null,
) {
    val previous = _threadHistoryPaginationByThread.value[threadId] ?: ThreadHistoryPaginationState()
    val stableAnchor = page.itemKeys.firstNotNullOfOrNull { (turn, item) ->
        messagesByThread.value[threadId]?.firstOrNull { it.turnId == turn && it.itemId == item }?.id
    } ?: oldestMessageId?.let { candidate -> messagesByThread.value[threadId]?.firstOrNull { it.id == candidate }?.id }
    _threadHistoryPaginationByThread.value = _threadHistoryPaginationByThread.value + (threadId to historyPaginationAfterPage(
        previous, page, oldestLoadedTurnId, stableAnchor, initialLoad,
        revision = if (initialLoad) historyLoadRevision.incrementAndGet() else previous.initialLoadRevision,
        activityRevision = activityRevision,
    ))
}

private fun CodexService.recordThreadHistoryFailure(threadId: String, stage: String, error: Exception) {
    val code = (error as? CodexServiceError.RpcFailure)?.rpcError?.code
    android.util.Log.e("RemodexHistory", "history failed thread=$threadId stage=$stage attempt=1 rpcCode=$code", error)
    _olderHistoryErrorByThread.value = _olderHistoryErrorByThread.value +
        (threadId to "$stage: ${error.message ?: error.javaClass.name}")
}

internal suspend fun CodexService.refreshInFlightTurnStateInternal(threadId: String, propagateFailure: Boolean = false): Boolean {
    if (!sessionReady) return false
    val tid = threadId.trim().ifEmpty { return false }
    val generation = connectionGeneration
    val predictionCheck = if (_activeThreadId.value == tid) predictionStore.beginCheck(tid) else null
    val snapshot =
        try {
            fetchThreadTurnInterruptSnapshotImpl(tid).also { requireHistoryConnection(generation) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            predictionCheck?.let { predictionStore.abandonCheck(tid, it) }
            throw e
        } catch (e: Exception) {
            predictionCheck?.let { predictionStore.abandonCheck(tid, it) }
            if (propagateFailure) throw e
            recordThreadHistoryFailure(tid, "thread/read + latest turn status", e)
            return false
        }

    if (predictionCheck != null) {
        predictionStore.checked(tid, predictionCheck, snapshot.latestSuccessfulTurnId)?.let { requestComposerPrediction(it) }
    }
    return when (val action = ThreadTurnRecovery.actionFor(snapshot)) {
        is ThreadTurnRecoveryAction.Running -> {
            noteTurnStarted(tid, action.turnId)
            true
        }
        ThreadTurnRecoveryAction.ProtectedFallback -> {
            noteProtectedRunningFallback(tid, true)
            true
        }
        ThreadTurnRecoveryAction.Idle -> {
            noteTurnFinished(tid)
            true
        }
    }
}

internal suspend fun CodexService.catchUpThreadAfterSelectionOrReconnect(threadId: String): Boolean {
    val tid = threadId.trim().ifEmpty { return false }
    val refreshed = refreshInFlightTurnStateInternal(tid)
    val isRunning = _runningTurnIdByThread.value.containsKey(tid) || _protectedRunningFallbackThreadIds.value.contains(tid)
    if (refreshed && isRunning) {
        try {
            ensureThreadResumedInternal(tid, force = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            recordThreadHistoryFailure(tid, "thread/resume live mirroring", e)
            return false
        }
    }
    return try {
        // Resuming only restores metadata/live subscriptions; every history load still uses a bounded page.
        syncThreadHistoryInternal(tid, force = isRunning)
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        // syncThreadHistoryInternal recorded the original exception and leaves hydration/cursor state retryable.
        false
    }
}

internal suspend fun CodexService.refreshInactiveRunningThreadStatesInternal(limit: Int = 3) {
    if (!sessionReady) return
    val candidates =
        RunningThreadRefreshPolicy.inactiveRunningThreadIds(
            threads = _threads.value,
            activeThreadId = _activeThreadId.value,
            runningTurnIdByThread = _runningTurnIdByThread.value,
            protectedRunningFallbackThreadIds = _protectedRunningFallbackThreadIds.value,
            limit = limit,
        )
    for (threadId in candidates) {
        refreshInFlightTurnStateInternal(threadId)
    }
}

internal fun jsonObjectFromRpc(obj: JSONValue.Obj): JsonObject =
    buildJsonObject {
        obj.map.forEach { (k, v) ->
            put(k, JSONValue.toJsonElement(v))
        }
    }

internal fun upsertThreadRow(
    list: List<CodexThread>,
    thread: CodexThread,
): List<CodexThread> {
    val i = list.indexOfFirst { it.id == thread.id }
    val merged =
        if (i >= 0) {
            val existing = list[i]
            val filled = thread.withMissingDisplayFieldsFrom(existing)
            list.mapIndexed { idx, t ->
                if (idx == i) {
                    filled.copy(syncState = existing.syncState)
                } else {
                    t
                }
            }
        } else {
            list + thread
        }
    return sortThreadsForBridge(merged)
}

internal fun sortThreadsForBridge(value: List<CodexThread>): List<CodexThread> {
    val past = Instant.EPOCH
    return value.sortedWith { lhs, rhs ->
        val l = lhs.updatedAt ?: lhs.createdAt ?: past
        val r = rhs.updatedAt ?: rhs.createdAt ?: past
        r.compareTo(l)
    }
}

internal suspend fun CodexService.refreshConversationInternal(threadId: String) {
    val tid = threadId.trim()
    if (tid.isEmpty()) throw CodexServiceError.InvalidInput("Missing conversation ID")
    if (!sessionReady) throw CodexServiceError.Disconnected
    val generation = connectionGeneration
    refreshConversationStages(
        loadHistory = { syncThreadHistoryInternal(tid, force = true, replaceInFlight = true) },
        resumeSubscription = {
            requireHistoryConnection(generation)
            ensureThreadResumedInternal(tid, force = true)
            requireHistoryConnection(generation)
        },
        refreshTurnState = {
            requireHistoryConnection(generation)
            refreshInFlightTurnStateInternal(tid, propagateFailure = true)
            requireHistoryConnection(generation)
        },
        onFailure = { stage, error ->
            // History already records its own RPC/decode failure; deadline is owned here.
            if (stage != "history" || error is java.util.concurrent.TimeoutException) {
                recordThreadHistoryFailure(tid, "manual-refresh/$stage", error)
            }
        },
    )
}
