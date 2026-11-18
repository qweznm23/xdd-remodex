package com.remodex.mobile.services

import com.remodex.mobile.core.model.*
import com.remodex.mobile.core.error.CodexServiceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal suspend fun CodexService.markCodexThreadRead(threadId: String, deviceId: String, revision: String) {
    if (!sessionReady || resolvedMacScopedPersistenceDeviceId() != deviceId) throw CodexServiceError.Disconnected
    val generation = connectionGeneration
    val request = codexSidebar.begin(deviceId)
    try {
        val result = sendRequestImpl("thread/readState/update", JSONValue.Obj(mapOf(
            "threadId" to JSONValue.Str(threadId), "expectedRevision" to JSONValue.Str(revision),
            "operation" to JSONValue.Obj(mapOf("type" to JSONValue.Str("read"))),
        ))).result?.objectValue ?: throw CodexServiceError.InvalidResponse("thread/readState/update missing result")
        val receipt = CodexThreadReadState.decode(result["readState"])
            ?: throw CodexServiceError.InvalidResponse("thread/readState/update missing read state")
        if (generation == connectionGeneration && deviceId == resolvedMacScopedPersistenceDeviceId()) codexSidebar.receipt(deviceId, threadId, receipt, request)
    } finally { codexSidebar.finish(request) }
}

internal suspend fun CodexService.setCodexThreadPinned(threadId: String, deviceId: String, pinned: Boolean) {
    if (!sessionReady || resolvedMacScopedPersistenceDeviceId() != deviceId) throw CodexServiceError.Disconnected
    val section = sidebarActivity.value.takeIf { it.deviceId == deviceId }?.pinnedSectionId
        ?: throw CodexServiceError.InvalidResponse("Codex Pinned section is unavailable; refresh the thread list")
    sendRequestImpl("thread/section/move", JSONValue.Obj(mapOf(
        "threadId" to JSONValue.Str(threadId), "sectionId" to if (pinned) JSONValue.Str(section) else JSONValue.Null,
    )))
    if (deviceId == resolvedMacScopedPersistenceDeviceId()) refreshThreadsInternal()
}

internal fun CodexService.handleSidebarNotification(method: String, params: JSONValue?) {
    val row = params?.objectValue ?: return
    when (method) {
        "thread/readState/changed" -> {
            val id = row["threadId"]?.stringValue ?: error("Read state notification missing threadId")
            val receipt = CodexThreadReadState.decode(row["readState"])
                ?: error("Read state notification missing receipt")
            codexSidebar.receipt(resolvedMacScopedPersistenceDeviceId(), id, receipt)
        }
        "thread/section/changed" -> {
            codexSidebar.sectionsChanged()
            scope.launch {
                try { refreshThreadsInternal() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { android.util.Log.e("RemodexSidebar", "operation=section-change stage=refresh", failure) }
            }
        }
    }
}
