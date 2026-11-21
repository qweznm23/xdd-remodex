package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexThreadSyncState
import com.remodex.mobile.core.model.JSONValue
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mirrors [CodexService+Sync.swift](../../../../../../../../CodexMobile/CodexMobile/Services/CodexService+Sync.swift)
 * archiveThreadGroup / deleteLocalThreadGroup.
 */
internal suspend fun CodexService.archiveThreadGroupForRepository(threadIds: List<String>): List<String> =
    withContext(Dispatchers.IO) {
        archiveThreadGroupInternal(threadIds)
    }

internal suspend fun CodexService.deleteLocalThreadGroupForRepository(threadIds: List<String>): List<String> =
    withContext(Dispatchers.IO) {
        deleteLocalThreadGroupInternal(threadIds)
    }

internal suspend fun CodexService.archiveThreadGroupInternal(threadIds: List<String>): List<String> {
    val rootThreadIds = collectRootThreadIds(threadIds)
    for (rootId in rootThreadIds) {
        changeThreadArchive(rootId, true)
    }
    return rootThreadIds
}

internal suspend fun CodexService.deleteLocalThreadGroupInternal(threadIds: List<String>): List<String> {
    val rootThreadIds = collectRootThreadIds(threadIds)
    val subtreeThreadIds = rootThreadIds.flatMap { collectSubtreeThreadIds(it) }
    val allIds = (subtreeThreadIds + rootThreadIds).distinct()
    for (tid in allIds) {
        deleteThreadLocallyInternal(tid)
    }
    return rootThreadIds
}

private fun CodexService.collectRootThreadIds(threadIds: List<String>): List<String> {
    val allThreads = _threads.value
    val inputSet = threadIds.toSet()
    return inputSet.filter { tid ->
        val thread = allThreads.find { it.id == tid } ?: return@filter true
        thread.parentThreadId == null || thread.parentThreadId !in inputSet
    }
}

private fun CodexService.collectSubtreeThreadIds(parentId: String): List<String> {
    val allThreads = _threads.value
    val queue = ArrayDeque<String>()
    queue.add(parentId)
    val visited = mutableSetOf<String>()
    val descendants = mutableListOf<String>()
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        for (thread in allThreads) {
            if (thread.parentThreadId == current && visited.add(thread.id)) {
                descendants.add(thread.id)
                queue.add(thread.id)
            }
        }
    }
    return descendants
}

internal suspend fun CodexService.changeThreadArchive(threadId: String, archived: Boolean) {
    threadRefreshMutex.withLock {
        val connection = webSocket
        val device = resolvedMacScopedPersistenceDeviceId()
        sendRequestImpl(if (archived) "thread/archive" else "thread/unarchive",
            JSONValue.Obj(mapOf("threadId" to JSONValue.Str(threadId))))
        if (connection !== webSocket || device != resolvedMacScopedPersistenceDeviceId()) throw CodexServiceError.Disconnected
        for (id in listOf(threadId) + collectSubtreeThreadIds(threadId)) {
            setThreadArchivedLocally(id, archived)
        }
    }
}

private suspend fun CodexService.setThreadArchivedLocally(
    threadId: String,
    isArchived: Boolean,
) {
    if (isArchived) {
        noteTurnFinished(threadId)
        resumedThreadIds.remove(threadId)
        hydratedThreadIds.remove(threadId)
        loadingHistory.remove(threadId)
        messageTimelineStore.removeThreadMessages(threadId)
        persistedThreadRenameById.remove(threadId)
        sessionPersistence.removeThreadRename(threadId)
        associatedManagedWorktreePathByThreadId.remove(threadId)
        sessionPersistence.removeAssociatedManagedWorktreePath(threadId)
        resolvedMacScopedPersistenceDeviceId()?.let { device ->
            macScopedSessionStore.saveThreadRenames(device, persistedThreadRenameById.toMap())
            macScopedSessionStore.saveAssociatedManagedWorktreePaths(device, associatedManagedWorktreePathByThreadId.toMap())
        }
    }
    if (isArchived) {
        setScopedLocallyArchivedThreadId(threadId, isArchived = true)
    } else {
        setScopedLocallyArchivedThreadId(threadId, isArchived = false)
    }

    val currentThreads = _threads.value.toMutableList()
    val idx = currentThreads.indexOfFirst { it.id == threadId }
    if (idx >= 0) {
        currentThreads[idx] = currentThreads[idx].copy(
            syncState = if (isArchived) CodexThreadSyncState.archivedLocal else CodexThreadSyncState.live,
        )
    }

    if (isArchived && _activeThreadId.value == threadId) {
        _activeThreadId.value = null
        persistActiveThreadId(null)
    }

    publishThreads(currentThreads)
}
