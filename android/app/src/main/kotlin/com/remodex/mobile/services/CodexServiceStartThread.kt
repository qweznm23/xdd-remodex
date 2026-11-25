package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mirrors [CodexService.startThreadImpl] in
 * [CodexService+ThreadsTurns.swift](../../../../../../../../CodexMobile/CodexMobile/Services/CodexService+ThreadsTurns.swift).
 */
internal suspend fun CodexService.startThreadInternal(
    model: String? = null,
    cwd: String? = null,
    serviceTier: String? = null,
): CodexThread {
    val created = createThreadInternal(model, cwd, serviceTier)
    return runNewThreadOpenFlow(
        thread = created,
        normalizedCwd = CodexThread.normalizeThreadStartCwd(cwd?.trim()?.takeIf { it.isNotEmpty() }),
        sink = CodexServiceNewThreadOpenSink(this),
    )
}

internal suspend fun CodexService.createThreadInternal(
    model: String?,
    cwd: String?,
    serviceTier: String?,
): CodexThread {
    if (!sessionReady) throw CodexServiceError.Disconnected
    val generation = connectionGeneration
    val normalizedCwd = CodexThread.normalizeThreadStartCwd(cwd?.trim()?.takeIf { it.isNotEmpty() })
    val resolvedModel =
        model?.trim()?.takeIf { it.isNotEmpty() }?.let { selected ->
            _availableModels.value.firstOrNull { it.id == selected || it.model == selected }?.model ?: selected
        } ?: runtimeSelectionForThread(null).modelId
    val resolvedServiceTier =
        serviceTier?.trim()?.takeIf { it.isNotEmpty() }
            ?: _selectedServiceTier.value?.name
    var includesServiceTier = shouldWireServiceTier(supportsServiceTier, resolvedServiceTier != null)
    var threadStartRpc: RPCMessage? = null
    while (true) {
        val params =
            buildMap {
                resolvedModel?.let { put("model", JSONValue.Str(it)) }
                normalizedCwd?.let { put("cwd", JSONValue.Str(it)) }
                if (includesServiceTier) {
                    resolvedServiceTier?.let { put("serviceTier", JSONValue.Str(it)) }
                }
            }
        val payload =
            if (params.isEmpty()) JSONValue.Obj(emptyMap()) else JSONValue.Obj(params)
        try {
            threadStartRpc = sendRequestWithSandboxAndApprovalFallback("thread/start", payload)
            break
        } catch (e: Throwable) {
            if (includesServiceTier && shouldRetryTurnStartWithoutServiceTier(e)) {
                markServiceTierUnsupportedForCurrentBridge()
                includesServiceTier = false
                continue
            }
            throw e
        }
    }
    if (!sessionReady || generation != connectionGeneration) {
        throw kotlinx.coroutines.CancellationException("Thread creation belongs to a replaced connection")
    }
    val result =
        threadStartRpc?.result ?: throw CodexServiceError.InvalidResponse("thread/start missing result")
    val resultObj = result as? JSONValue.Obj ?: throw CodexServiceError.InvalidResponse("thread/start result not object")
    val threadEl =
        resultObj.map["thread"] as? JSONValue.Obj
            ?: throw CodexServiceError.InvalidResponse("thread/start response missing thread")
    val decoded =
        runCatching {
            CodexThread.fromJsonObject(jsonObjectFromRpc(threadEl))
        }.getOrElse {
            throw CodexServiceError.InvalidResponse("thread/start thread decode failed").also { error -> error.initCause(it) }
        }
    observeExecution(decoded.id, threadEl.map + resultObj.map, null, compareSnapshot = true, generation = generation)
    val patched = applyRequestedProjectPathForNewThread(decoded.copy(model = decoded.model ?: resolvedModel), normalizedCwd)
    CodexThread.normalizeProjectPath(normalizedCwd)?.let { projectPath ->
        beginAuthoritativeProjectPathTransition(patched.id, projectPath)
        rememberAssociatedManagedWorktreePathIfWorktree(projectPath, patched.id)
    }
    return patched
}

internal interface NewThreadOpenFlowSink {
    suspend fun publishStartedThread(thread: CodexThread)

    suspend fun forceResumeStartedThread(
        thread: CodexThread,
        normalizedCwd: String?,
    ): CodexThread?

    suspend fun syncStartedThreadHistory(threadId: String)

    fun activateStartedThread(threadId: String)
}

internal suspend fun runNewThreadOpenFlow(
    thread: CodexThread,
    normalizedCwd: String?,
    sink: NewThreadOpenFlowSink,
): CodexThread {
    sink.publishStartedThread(thread)
    val resumed =
        try {
            sink.forceResumeStartedThread(
                thread = thread,
                normalizedCwd = normalizedCwd,
            ) ?: thread
        } catch (e: Exception) {
            if (!shouldAllowProjectRebindWithoutResume(e)) throw e
            thread
        }
    runCatching { sink.syncStartedThreadHistory(thread.id) }
        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    sink.activateStartedThread(thread.id)
    return resumed
}

private class CodexServiceNewThreadOpenSink(
    private val service: CodexService,
) : NewThreadOpenFlowSink {
    override suspend fun publishStartedThread(thread: CodexThread) {
        service.publishThreads(upsertThreadRow(service._threads.value, thread))
    }

    override suspend fun forceResumeStartedThread(
        thread: CodexThread,
        normalizedCwd: String?,
    ): CodexThread? =
        service.ensureThreadResumedInternal(
            threadId = thread.id,
            force = true,
            preferredProjectPath = normalizedCwd,
            modelIdentifierOverride = thread.model,
        )

    override suspend fun syncStartedThreadHistory(threadId: String) {
        service.syncThreadHistoryInternal(threadId, force = true)
    }

    override fun activateStartedThread(threadId: String) {
        service._activeThreadId.value = threadId
        service.syncActiveThreadRuntimeSelection()
        service.persistActiveThreadId(threadId)
    }
}

internal fun applyRequestedProjectPathForNewThread(
    thread: CodexThread,
    requested: String?,
): CodexThread {
    val preferred = CodexThread.normalizeThreadStartCwd(requested) ?: return thread
    if (thread.cwd == preferred) return thread
    return thread.copy(cwd = preferred)
}

suspend fun CodexService.startThreadForRepository(
    model: String?,
    cwd: String?,
    serviceTier: String?,
): CodexThread =
    withContext(Dispatchers.IO) {
        startThreadInternal(model, cwd, serviceTier)
    }
