package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.ThreadExecutionState
import com.remodex.mobile.core.model.CodexAccessMode
import com.remodex.mobile.core.model.CodexCollaborationModeKind
import com.remodex.mobile.core.model.CodexModelOption
import com.remodex.mobile.core.model.CodexServiceTier
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.persistence.RuntimeSelectionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

internal suspend fun CodexService.refreshModelsInternal() {
    if (!sessionReady) throw CodexServiceError.Disconnected
    if (!_isLoadingModels.compareAndSet(false, true)) return
    try {
        val response =
            sendRequestImpl(
                method = "model/list",
                params =
                    JSONValue.Obj(
                        mapOf(
                            "cursor" to JSONValue.Null,
                            "limit" to JSONValue.NumLong(50),
                            "includeHidden" to JSONValue.Bool(false),
                        ),
                    ),
            )
        val result = response.result?.objectValue
            ?: throw CodexServiceError.InvalidResponse("model/list missing result")
        val items =
            result["items"]?.arrayValue
                ?: result["data"]?.arrayValue
                ?: result["models"]?.arrayValue
                ?: emptyList()
        val decoded =
            items.mapNotNull { item ->
                val obj = item as? JSONValue.Obj ?: return@mapNotNull null
                runCatching { CodexModelOption.fromJsonObject(jsonObjectFromRpc(obj)) }.getOrNull()
            }.filter { it.model.isNotBlank() || it.id.isNotBlank() }

        _availableModels.value = decoded
        _modelsErrorMessage.value = null
        normalizeRuntimeSelectionsAfterModelsUpdate()
    } catch (e: Throwable) {
        _modelsErrorMessage.value = e.message ?: e.javaClass.simpleName
        throw e
    } finally {
        _isLoadingModels.value = false
    }
}

suspend fun CodexService.refreshModelsForRepository() =
    withContext(Dispatchers.IO) {
        refreshModelsInternal()
    }

internal fun CodexService.applyConfirmedSettings(threadId: String, settings: Map<String, JSONValue>) {
    synchronized(threadRuntimeSelection) {
        val current = runtimeSelectionForThread(threadId)
        if (!threadRuntimeSelection.confirm(threadId, current, settings)) return
        syncActiveThreadRuntimeSelection()
    }
}

private suspend fun CodexService.writeSetting(field: String, value: String?) {
    val id = _activeThreadId.value
    val connection = webSocket
    runtimeWriteMutex.withLock {
        if (connection !== webSocket) throw CodexServiceError.Disconnected
        if (supportsSettingsSync && id != null) {
            val params = mapOf("threadId" to JSONValue.Str(id), field to (value?.let(JSONValue::Str) ?: JSONValue.Null))
            val result = sendRequestImpl("thread/settings/update", JSONValue.Obj(params))
            val settings = result.result?.objectValue?.get("runtimeSettings")?.objectValue
                ?: throw CodexServiceError.InvalidResponse("thread/settings/update missing runtimeSettings")
            if (connection === webSocket) applyConfirmedSettings(id, settings)
        } else synchronized(threadRuntimeSelection) {
            val current = runtimeSelectionForThread(id)
            when (field) {
                "model" -> threadRuntimeSelection.select(id, current.copy(modelId = value))
                "effort" -> threadRuntimeSelection.select(id, current.copy(reasoningEffort = value))
                "serviceTier" -> _selectedServiceTier.value = CodexServiceTier.entries.find { it.name == value }
            }
            normalizeRuntimeSelectionsAfterModelsUpdate()
        }
    }
}

suspend fun CodexService.setSelectedModelIdForRepository(modelId: String?) =
    writeSetting("model", _availableModels.value.firstOrNull { it.id == modelId }?.model ?: modelId)

suspend fun CodexService.setSelectedReasoningEffortForRepository(reasoningEffort: String?) =
    writeSetting("effort", reasoningEffort)

suspend fun CodexService.setSelectedAccessModeForRepository(accessMode: CodexAccessMode) =
    withContext(Dispatchers.IO) {
        _selectedAccessMode.value = accessMode
        persistRuntimeSelection()
    }

suspend fun CodexService.setSelectedServiceTierForRepository(serviceTier: CodexServiceTier?) =
    writeSetting("serviceTier", serviceTier?.name)

suspend fun CodexService.setThreadCollaborationModeForRepository(
    threadId: String,
    mode: CodexCollaborationModeKind,
) = withContext(Dispatchers.IO) {
    val tid = threadId.trim().takeIf { it.isNotEmpty() } ?: return@withContext
    val list = _threads.value
    val idx = list.indexOfFirst { it.id == tid }
    publishThreads(
        if (idx >= 0) {
            list.mapIndexed { i, thread ->
                if (i == idx) thread.copy(collaborationMode = mode) else thread
            }
        } else {
            list + CodexThread(id = tid, collaborationMode = mode)
        },
    )
}

internal fun CodexService.runtimeSelectionForThread(threadId: String?): ThreadModelSelection =
    threadRuntimeSelection.resolve(threadId, _threads.value, _availableModels.value)

internal fun CodexService.runtimeModelIdentifierForTurn(threadId: String): String? =
    runtimeSelectionForThread(threadId).modelId

internal fun CodexService.selectedReasoningEffortForThread(threadId: String): String? =
    runtimeSelectionForThread(threadId).reasoningEffort

internal fun CodexService.syncActiveThreadRuntimeSelection() = synchronized(threadRuntimeSelection) {
    val selection = runtimeSelectionForThread(_activeThreadId.value)
    if (supportsSettingsSync) {
        _selectedServiceTier.value = if (threadRuntimeSelection.fastTier(_activeThreadId.value)) CodexServiceTier.fast else null
    }
    _selectedModelId.value = selection.modelId
    _selectedReasoningEffort.value = selection.reasoningEffort
}

internal fun CodexService.normalizeRuntimeSelectionsAfterModelsUpdate() {
    syncActiveThreadRuntimeSelection()
    persistRuntimeSelection()
}

internal fun CodexService.persistRuntimeSelection() {
    persistRuntimeSelectionSnapshot(
        RuntimeSelectionSnapshot(
            selectedModelId = threadRuntimeSelection.defaults.modelId,
            selectedReasoningEffort = threadRuntimeSelection.defaults.reasoningEffort,
            selectedAccessMode = _selectedAccessMode.value.name,
            selectedServiceTier = _selectedServiceTier.value?.name,
        ),
    )
}

internal fun CodexService.observeExecution(
    threadId: String,
    fields: Map<String, JSONValue>,
    before: ThreadExecutionState? = null,
    compareSnapshot: Boolean = false,
    generation: Long = connectionGeneration,
) = synchronized(executionStates) {
    if (generation != connectionGeneration) return@synchronized
    val entries = executionStates.value
    if (!compareSnapshot || entries[threadId] === before) {
        val state = (entries[threadId] ?: ThreadExecutionState()).merge(fields)
        val bounded = if (threadId !in entries && entries.size >= 512) entries - entries.keys.first() else entries
        executionStates.value = bounded + (threadId to state)
    }
}

internal suspend fun CodexService.refreshExecutionFromOwner(threadId: String, generation: Long = connectionGeneration) {
    if (!sessionReady || generation != connectionGeneration) throw CodexServiceError.Disconnected
    val before = executionStates.value[threadId]
    val params = JSONValue.Obj(mapOf("threadId" to JSONValue.Str(threadId), "includeTurns" to JSONValue.Bool(false)))
    val thread = sendRequestImpl("thread/read", params).result?.objectValue?.get("thread")?.objectValue
        ?: throw CodexServiceError.InvalidResponse("thread/read missing thread")
    if (thread["id"]?.stringValue != threadId) throw CodexServiceError.InvalidResponse("thread/read returned another thread")
    if (generation == connectionGeneration && sessionReady) observeExecution(threadId, thread, before, compareSnapshot = true, generation = generation)
}

internal suspend fun CodexService.writeExecutionAccess(threadId: String, mode: CodexAccessMode) {
    val generation = connectionGeneration
    runtimeWriteMutex.withLock {
        if (!sessionReady || generation != connectionGeneration) throw CodexServiceError.Disconnected
        val before = executionStates.value[threadId]
        val policy = mapOf(
            "approvalPolicy" to JSONValue.Str(mode.approvalPolicyCandidates.first()),
            "sandboxPolicy" to runtimeSandboxPolicyObject(mode),
        )
        val response = sendRequestImpl("thread/settings/update", JSONValue.Obj(policy + ("threadId" to JSONValue.Str(threadId))))
        if (generation != connectionGeneration || !sessionReady) throw CodexServiceError.Disconnected
        val confirmed = response.result?.objectValue?.get("threadSettings")?.objectValue
        if (confirmed != null) {
            observeExecution(threadId, confirmed, before, compareSnapshot = true, generation = generation)
        } else if (executionStates.value[threadId] === before) {
            // An acknowledgement can be empty. Only an owner snapshot can confirm the effective policy.
            val unknown = mapOf("approvalPolicy" to JSONValue.Null, "sandboxPolicy" to JSONValue.Null, "activePermissionProfile" to JSONValue.Null)
            observeExecution(threadId, unknown, before, compareSnapshot = true, generation = generation)
            refreshExecutionFromOwner(threadId, generation)
        }
    }
}
