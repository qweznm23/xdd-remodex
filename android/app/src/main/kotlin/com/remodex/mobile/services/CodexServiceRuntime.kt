package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.CodexAccessMode
import com.remodex.mobile.core.model.CodexCollaborationModeKind
import com.remodex.mobile.core.model.CodexModelOption
import com.remodex.mobile.core.model.CodexServiceTier
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.persistence.RuntimeSelectionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun CodexService.refreshModelsInternal() {
    if (!sessionReady) throw CodexServiceError.Disconnected
    _isLoadingModels.value = true
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

suspend fun CodexService.setSelectedModelIdForRepository(modelId: String?) =
    withContext(Dispatchers.IO) {
        synchronized(threadRuntimeSelection) {
            val threadId = _activeThreadId.value
            val current = runtimeSelectionForThread(threadId)
            threadRuntimeSelection.select(threadId, current.copy(modelId = modelId?.trim()?.takeIf { it.isNotEmpty() }))
            normalizeRuntimeSelectionsAfterModelsUpdate()
        }
    }

suspend fun CodexService.setSelectedReasoningEffortForRepository(reasoningEffort: String?) =
    withContext(Dispatchers.IO) {
        synchronized(threadRuntimeSelection) {
            val threadId = _activeThreadId.value
            val current = runtimeSelectionForThread(threadId)
            threadRuntimeSelection.select(threadId, current.copy(reasoningEffort = reasoningEffort?.trim()?.takeIf { it.isNotEmpty() }))
            normalizeRuntimeSelectionsAfterModelsUpdate()
        }
    }

suspend fun CodexService.setSelectedAccessModeForRepository(accessMode: CodexAccessMode) =
    withContext(Dispatchers.IO) {
        _selectedAccessMode.value = accessMode
        persistRuntimeSelection()
    }

suspend fun CodexService.setSelectedServiceTierForRepository(serviceTier: CodexServiceTier?) =
    withContext(Dispatchers.IO) {
        _selectedServiceTier.value = serviceTier
        persistRuntimeSelection()
    }

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
