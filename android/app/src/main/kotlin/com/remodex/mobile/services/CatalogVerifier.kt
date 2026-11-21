package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexThreadSyncState
import com.remodex.mobile.core.model.JSONValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class CatalogVerifier {
    private var scope: String? = null
    private var job: Job? = null
    private val observations = mutableMapOf<String, Long>()
    private var sequence = 0L
    private var offset = 0
    private var lastCheck = 0L

    @Synchronized fun reset() {
        job?.cancel()
        job = null
        scope = null
        observations.clear()
        offset = 0
        lastCheck = 0
    }

    @Synchronized fun observe(currentScope: String?, rows: List<CodexThread>): Set<String> {
        if (scope != currentScope) reset()
        scope = currentScope ?: return emptySet()
        sequence++
        val ids = rows.mapTo(hashSetOf()) { it.id }
        for (row in rows) {
            if (row.syncState != CodexThreadSyncState.live) observations.remove(row.id)
            else if (!row.preview.isNullOrBlank() || row.id in observations) observations[row.id] = sequence
        }
        return observations.filter { (id, lastSeen) -> id !in ids && sequence - lastSeen >= 2 }.keys
    }

    @Synchronized fun checkMissing(service: CodexService, missing: Set<String>) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (scope == null || missing.isEmpty() || job?.isActive == true || lastCheck != 0L && now - lastCheck < 20_000) return
        val candidates = missing.toList()
        val batch = List(minOf(8, candidates.size)) { candidates[(offset + it) % candidates.size] }.associateWith { observations[it] }
        offset = (offset + batch.size) % candidates.size
        val snapshot = scope
        val connection = service.webSocket
        lastCheck = now
        job = service.scope.launch {
            for ((id, version) in batch) {
                fun eligible() = synchronized(this@CatalogVerifier) {
                    service.sessionReady && connection === service.webSocket && scope == snapshot && observations[id] == version &&
                        service._activeThreadId.value != id && id !in service._runningTurnIdByThread.value &&
                        id !in service._protectedRunningFallbackThreadIds.value &&
                        service._threads.value.any { it.id == id && it.syncState == CodexThreadSyncState.live }
                }
                if (!eligible()) continue
                try {
                    val result = withTimeoutOrNull(2_000L) { service.sendRequestImpl("thread/read",
                        JSONValue.Obj(mapOf("threadId" to JSONValue.Str(id), "includeTurns" to JSONValue.Bool(false)))) }
                    if (result == null) android.util.Log.w("RemodexCatalog", "thread/read timed out for $id")
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) {
                    val rpc = (failure as? CodexServiceError.RpcFailure)?.rpcError
                    if (rpc?.code == -32600 && rpc.message == "thread not loaded: $id") {
                        service.threadRefreshMutex.withLock {
                            if (eligible()) {
                                // A remote disappearance is not a user-requested permanent local deletion.
                                // Allow a restored server entry to reappear with its cached conversation.
                                service.publishThreads(service._threads.value.filterNot { it.id == id })
                                synchronized(this@CatalogVerifier) { observations.remove(id) }
                            }
                        }
                    } else android.util.Log.w("RemodexCatalog", "thread/read $id", failure)
                }
            }
        }
    }
}
