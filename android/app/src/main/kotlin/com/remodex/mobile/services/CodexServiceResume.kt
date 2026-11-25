package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexThreadSyncState
import com.remodex.mobile.core.model.JSONValue

/**
 * Mirrors [CodexService.ensureThreadResumed] in
 * [CodexService+ThreadsTurns.swift](../../../../../../../../CodexMobile/CodexMobile/Services/CodexService+ThreadsTurns.swift).
 */
internal suspend fun CodexService.ensureThreadResumedInternal(
    threadId: String,
    force: Boolean = false,
    preferredProjectPath: String? = null,
    modelIdentifierOverride: String? = null,
    expectedGeneration: Long = connectionGeneration,
): CodexThread? {
    fun checkConnection() {
        if (!sessionReady || connectionGeneration != expectedGeneration) throw CodexServiceError.Disconnected
    }
    checkConnection()
    val tid = threadId.trim()
    if (tid.isEmpty()) return null
    if (!force && resumedThreadIds.contains(tid)) {
        return _threads.value.find { it.id == tid }
    }

    val generation = expectedGeneration
    val normalizedPreferred =
        CodexThread.normalizeProjectPath(preferredProjectPath?.trim()?.takeIf { it.isNotEmpty() })
    val cwd = normalizedPreferred
    val before = executionStates.value[tid]
    val model = modelIdentifierOverride?.trim()?.takeIf { it.isNotEmpty() }

    val camelParams =
        buildMap {
            put("threadId", JSONValue.Str(tid))
            put("excludeTurns", JSONValue.Bool(true))
            cwd?.let { put("cwd", JSONValue.Str(it)) }
            model?.let { put("model", JSONValue.Str(it)) }
        }

    val response =
        try {
            sendGuardedRequest("thread/resume", JSONValue.Obj(camelParams), ::checkConnection)
        } catch (e: CodexServiceError.RpcFailure) {
            if (shouldRetryThreadResumeSnakeCase(e)) {
                val snake =
                    buildMap {
                        put("thread_id", JSONValue.Str(tid))
                        put("excludeTurns", JSONValue.Bool(true))
                        cwd?.let { put("cwd", JSONValue.Str(it)) }
                        model?.let { put("model", JSONValue.Str(it)) }
                    }
                sendGuardedRequest("thread/resume", JSONValue.Obj(snake), ::checkConnection)
            } else {
                throw e
            }
        }

    if (!sessionReady || generation != connectionGeneration) {
        throw kotlinx.coroutines.CancellationException("Thread resume belongs to a replaced connection")
    }
    val result = response.result as? JSONValue.Obj
        ?: throw CodexServiceError.InvalidResponse("thread/resume missing result object")
    val threadEl = result.map["thread"] as? JSONValue.Obj
        ?: throw CodexServiceError.InvalidResponse("thread/resume missing thread object")
    val decoded = CodexThread.fromJsonObject(jsonObjectFromRpc(threadEl))
    if (decoded.id != tid) throw CodexServiceError.InvalidResponse("thread/resume returned another thread")
    observeExecution(tid, threadEl.map + result.map, before, compareSnapshot = true, generation = generation)
    val live = applyAuthoritativeProjectPathToServerThread(decoded.copy(syncState = CodexThreadSyncState.live))
    publishThreads(upsertThreadRow(_threads.value, live))
    confirmAuthoritativeProjectPathIfNeeded(tid, live.normalizedProjectPath, authoritativeProjectPathByThreadId)
    rehydrateRunningTurnFromThreadRead(tid, threadEl.map)
    resumedThreadIds.add(tid)
    return live

}

private fun shouldRetryThreadResumeSnakeCase(e: CodexServiceError.RpcFailure): Boolean {
    val c = e.rpcError.code
    if (c != -32600 && c != -32602) return false
    val m = e.rpcError.message.lowercase()
    val hints =
        listOf(
            "threadid",
            "thread_id",
            "cwd",
            "model",
            "unknown field",
            "missing field",
            "invalid",
        )
    return hints.any { m.contains(it) }
}
