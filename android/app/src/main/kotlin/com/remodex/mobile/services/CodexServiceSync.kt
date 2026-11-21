package com.remodex.mobile.services

import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexThreadSyncState
import com.remodex.mobile.data.ThreadListSync
import com.remodex.mobile.core.error.CodexServiceError
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

/**
 * Mirrors [CodexService+Sync.swift](../../../../../../../../CodexMobile/CodexMobile/Services/CodexService+Sync.swift).
 */
internal suspend fun CodexService.refreshThreadsInternal(
    deletedOnHost: String? = null,
    expectedGeneration: Long = connectionGeneration,
) = threadRefreshMutex.withLock {
    if (!sessionReady || expectedGeneration != connectionGeneration) return@withLock
    val connection = webSocket
    val device = resolvedMacScopedPersistenceDeviceId()
    val sidebarRequest = codexSidebar.begin(device)
    var deletionScope: String? = null
    var sidebarSnapshot: com.remodex.mobile.core.model.CodexSidebarState? = null
    val statusSnapshot = synchronized(runningTurnStateLock) { threadStatusRefreshGuard.begin() }
    try {
        val locallyDeleted = loadScopedLocallyDeletedThreadIds()
        val locallyArchived = loadScopedLocallyArchivedThreadIds()
        val fetched =
            try { ThreadListSync.fetchSnapshot(this).also { sidebarSnapshot = it.sidebar; deletionScope = it.deletionScope }.threads }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val rpc = (error as? CodexServiceError.RpcFailure)?.rpcError
                val diagnostic = "operation=thread/list stage=refresh attempt=1 code=${rpc?.code ?: "unknown"} rpcData=${rpc?.data}\n${error.stackTraceToString()}"
                Log.e("RemodexThreadSync", diagnostic)
                try {
                    File(appContext.filesDir, "last-thread-sync-error.txt").writeText(
                        if (diagnostic.length > 32_000) diagnostic.take(32_000) + "\n[diagnostic truncated]" else diagnostic,
                    )
                } catch (secondary: Exception) {
                    error.addSuppressed(secondary)
                    Log.e("RemodexThreadSync", "Failed to persist refresh diagnostic", error)
                }
                throw error
            }
                .filter { it.id !in locallyDeleted }

        currentCoroutineContext().ensureActive()
        if (!sessionReady || webSocket !== connection || resolvedMacScopedPersistenceDeviceId() != device) return@withLock
        for (thread in fetched) {
            if (thread.id in locallyArchived && thread.syncState == CodexThreadSyncState.live)
                setScopedLocallyArchivedThreadId(thread.id, false)
        }
        val serverThreads = fetched.map { incoming -> applyAuthoritativeProjectPathToServerThread(incoming) }
        synchronized(runningTurnStateLock) {
            for (thread in serverThreads) {
                if (thread.syncState != CodexThreadSyncState.live || !threadStatusRefreshGuard.accepts(statusSnapshot, thread.id)) continue
                when (thread.runtimeStatus) {
                    com.remodex.mobile.core.model.ThreadRuntimeStatus.Active -> noteProtectedRunningFallback(thread.id, true)
                    com.remodex.mobile.core.model.ThreadRuntimeStatus.Idle,
                    com.remodex.mobile.core.model.ThreadRuntimeStatus.SystemError -> {
                        // An outgoing turn/start may not have reached the server yet.
                        val hasPendingSend = messageTimelineStore.messagesByThread.value[thread.id].orEmpty().any {
                            it.deliveryState == com.remodex.mobile.core.model.CodexMessageDeliveryState.pending
                        }
                        if (!hasPendingSend && (thread.id in _runningTurnIdByThread.value || thread.id in _protectedRunningFallbackThreadIds.value)) {
                            noteTurnFinished(thread.id)
                        }
                    }
                    else -> Unit
                }
            }
        }
        val preserveLocalThreadIds =
            (
                setOfNotNull(_activeThreadId.value?.trim()?.takeIf { it.isNotEmpty() }) +
                    _runningTurnIdByThread.value.keys +
                    _protectedRunningFallbackThreadIds.value
            ).filter { it !in locallyDeleted && it !in locallyArchived }.toSet()
        val observedMissing = catalogVerifier.observe(deletionScope, serverThreads)
        val merged =
            mergeFetchedThreadsPreservingLocalRows(
                fetched = serverThreads,
                previous = _threads.value,
                preserveLocalThreadIds = (preserveLocalThreadIds + observedMissing).filterNot { it == deletedOnHost }.toSet(),
                persistedRename = { tid -> persistedThreadRename(tid) },
            )
        publishThreads(merged)
        catalogVerifier.checkMissing(this, observedMissing)
        sidebarSnapshot?.let { codexSidebar.apply(sidebarRequest, it) }

    } finally {
        codexSidebar.finish(sidebarRequest)
        synchronized(runningTurnStateLock) { threadStatusRefreshGuard.finish(statusSnapshot) }
    }
}

internal fun CodexService.publishThreads(threads: List<CodexThread>) {
    if (supportsSettingsSync) for (row in threads) row.runtimeSettings?.let {
        threadRuntimeSelection.confirm(row.id, ThreadModelSelection(row.model, row.reasoningEffort), it)
    }
    _threads.value = threads
    syncActiveThreadRuntimeSelection()
    if (isApplyingMacScopedState) return
    val device = resolvedMacScopedPersistenceDeviceId()
    if (device != null) {
        macScopedSessionStore.saveCachedThreads(device, threads)
    } else {
        sessionPersistence.saveCachedThreads(threads)
    }
}

internal fun mergeFetchedThreadsPreservingLocalRows(
    fetched: List<CodexThread>,
    previous: List<CodexThread>,
    preserveLocalThreadIds: Set<String>,
    persistedRename: (String) -> String?,
): List<CodexThread> {
    val merged =
        mergeThreadListWithPersistedRenames(
            fetched = fetched,
            previous = previous,
            persistedRename = persistedRename,
        )
    val mergedIds = merged.mapTo(mutableSetOf()) { it.id }
    val previousById = previous.associateBy { it.id }
    val preserved =
        preserveLocalThreadIds.mapNotNull { id ->
            previousById[id]
                ?.takeIf { it.syncState == CodexThreadSyncState.live && it.id !in mergedIds }
                ?.let { thread -> thread.withPersistedThreadRename(persistedRename(thread.id)) }
        }
    if (preserved.isEmpty()) return merged
    return sortThreadsForBridge(merged + preserved)
}
