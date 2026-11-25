package com.remodex.mobile.services

import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import android.content.pm.PackageManager
import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCMessage
import com.remodex.mobile.core.readRemodexAppVersionName
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val REMODEX_WIRE_LOG_TAG = "RemodexWire"

/**
 * Mirrors [CodexService+Messages.swift](../../../../../../../../CodexMobile/CodexMobile/Services/CodexService+Messages.swift).
 * Low-level JSON-RPC framing lives in [com.remodex.mobile.core.protocol.JsonRpcCodec].
 */
internal suspend fun CodexService.sendRequestImpl(
    method: String,
    params: JSONValue?,
): RPCMessage = sendGuardedRequest(method, params) {}

internal suspend fun CodexService.sendGuardedRequest(
    method: String,
    params: JSONValue?,
    checkDestination: () -> Unit,
): RPCMessage {
    checkDestination()
    testRpcRequestHandler?.let { handler ->
        return handler(method, params)
    }
    if (!sessionReady) throw CodexServiceError.Disconnected
    val id = JSONValue.Str(UUID.randomUUID().toString())
    val req = RPCMessage.request(id = id, method = method, params = params, includeJsonRpc = false)
    val deferred = CompletableDeferred<RPCMessage>()
    pendingRpc[idKey(id)] = deferred
    try {
        synchronized(secureSessionLock) {
            checkDestination()
            sendMessage(req)
        }
        return deferred.await()
    } finally {
        pendingRpc.remove(idKey(id))
    }
}

internal suspend fun CodexService.sendNotificationImpl(
    method: String,
    params: JSONValue?,
) {
    if (!sessionReady) throw CodexServiceError.Disconnected
    sendMessage(RPCMessage.notification(method = method, params = params, includeJsonRpc = false))
}

internal fun CodexService.sendMessage(message: RPCMessage) {
    val payload = jsonRpc.encodeMessage(message)
    // Counter assignment and socket enqueue must keep the same order, including
    // concurrent file chunks, history requests, and foreground refreshes.
    synchronized(secureSessionLock) {
        val wire = secureWireText(payload)
        sendRawText(wire)
    }
}

internal fun CodexService.processWireText(raw: String) {
    if (isSecureWirePreClassification(raw)) {
        val kind = wireMessageKind(raw) ?: return
        if (kind == "encryptedEnvelope") {
            handleEncryptedEnvelope(raw)
        } else {
            controlMux?.offer(kind, raw)
        }
        return
    }
    // Application RPCs and replay controls are accepted only inside an authenticated envelope.
}

private fun CodexService.isSecureWirePreClassification(text: String): Boolean {
    if (!text.contains("\"kind\":")) return false
    val markers =
        listOf(
            "\"serverHello\"",
            "\"secureReady\"",
            "\"secureError\"",
            "\"encryptedEnvelope\"",
        )
    return markers.any { text.contains(it) }
}

private fun CodexService.wireMessageKind(text: String): String? =
    try {
        val el = json.parseToJsonElement(text)
        (el as? JsonObject)?.get("kind")?.jsonPrimitive?.content
    } catch (e: Exception) {
        Log.w(REMODEX_WIRE_LOG_TAG, "dropped malformed secure-control message bytes=${text.toByteArray().size}: ${e.javaClass.simpleName}")
        null
    }

internal fun CodexService.dispatchIncomingRpc(message: RPCMessage) {
    val method = message.method?.trim()
    if (method != null && message.id != null) {
        val generation = connectionGeneration
        val socket = webSocket
        incomingRouter.dispatchServerRequest(
            method = method,
            requestId = message.id!!,
            params = message.params,
        ) { response ->
            synchronized(secureSessionLock) {
                if (generation != connectionGeneration || socket !== webSocket || !sessionReady) throw CodexServiceError.Disconnected
                sendMessage(response)
            }
        }
        return
    }
    if (method != null) {
        if (method == "serverRequest/resolved") {
            val params = message.params?.objectValue
            val threadId = params?.get("threadId")?.stringValue
            params?.get("requestId")?.let { requestId ->
                val key = JSONValue.toJsonElement(requestId).toString()
                val resolvedApproval = synchronized(pendingApprovalResponders) {
                    val approval = pendingApprovalQueue[key]
                    if (approval != null && (threadId == null || approval.threadId == threadId)) {
                        pendingApprovalResponders.remove(key)
                        pendingApprovalQueue.remove(key)
                        _pendingApprovalRequest.value = pendingApprovalQueue.values.firstOrNull()
                        true
                    } else false
                }
                if (resolvedApproval) scope.launch { messageTimelineStore.removeEphemeralPendingServerMarker(key) }
                synchronized(pendingStructuredInputResponders) {
                    if (_pendingStructuredInputRequests.value[key]?.threadId == threadId) {
                        pendingStructuredInputResponders.remove(key)
                        _pendingStructuredInputRequests.value = _pendingStructuredInputRequests.value - key
                        _pendingStructuredInputRequest.value = _pendingStructuredInputRequests.value.values.firstOrNull()
                    }
                }
            }
        }
        if (method in setOf("thread/replaced", "thread/archived", "thread/unarchived", "thread/deleted")) {
            message.params?.objectValue?.get("threadId")?.stringValue?.takeIf { it.isNotBlank() }?.let { id ->
                val generation = connectionGeneration
                val stamp = hostEventSequence.incrementAndGet()
                if (method == "thread/replaced") {
                    sourceReplacements[id] = stamp
                    hydratedThreadIds.remove(id)
                } else latestCatalogEvents[id] = stamp
                scope.launch {
                    try {
                        if (!sessionReady || connectionGeneration != generation) return@launch
                        if (method == "thread/replaced") {
                            if (_activeThreadId.value == id && sourceReplacements[id] == stamp)
                                syncThreadHistoryInternal(id, force = true, replaceInFlight = true)
                        } else {
                            val replay = message.params?.objectValue?.get("remodexReplayedEvent")?.boolValue == true
                            if (!replay) threadRefreshMutex.withLock {
                                if (!sessionReady || connectionGeneration != generation || latestCatalogEvents[id] != stamp) return@withLock
                                val rows = _threads.value
                                if (method == "thread/deleted") {
                                    loadingHistory.remove(id)
                                    hydratedThreadIds.remove(id)
                                    resumedThreadIds.remove(id)
                                    noteTurnFinished(id)
                                    publishThreads(rows.filterNot { it.id == id })
                                } else {
                                    val state = if (method == "thread/archived") com.remodex.mobile.core.model.CodexThreadSyncState.archivedLocal
                                        else com.remodex.mobile.core.model.CodexThreadSyncState.live
                                    if (method == "thread/unarchived") setScopedLocallyArchivedThreadId(id, false)
                                    publishThreads(rows.map { if (it.id == id) it.copy(syncState = state) else it })
                                }
                            }
                            if (latestCatalogEvents.remove(id, stamp)) {
                                refreshThreadsInternal(deletedOnHost = id.takeIf { method == "thread/deleted" }, expectedGeneration = generation)
                            }
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (failure: Exception) { Log.e("RemodexHistory", "operation=$method thread=$id", failure) }
                }
            }
        }
        if (method == "thread/queue/changed") refreshTurnQueue()
        if (method == "thread/status/changed") {
            val params = message.params?.objectValue
            val threadId = params?.get("threadId")?.stringValue
            val waiting = params?.get("status")?.objectValue?.get("activeFlags")?.arrayValue?.any { it.stringValue == "waitingOnUserInput" } == true
            if (threadId != null) _threads.value = _threads.value.map { if (it.id == threadId) it.copy(waitingOnUserInput = waiting) else it }
        }
        if (method == "thread/closed" || method == "thread/deleted" || method == "thread/archived") {
            message.params?.objectValue?.get("threadId")?.stringValue?.let { id ->
                resumedThreadIds.remove(id)
                synchronized(executionStates) { executionStates.value = executionStates.value - id }
            }
        }
        if (method == "thread/settings/updated" || method == "remodex/execution/updated") {
            val params = message.params?.objectValue
            val id = params?.get("threadId")?.stringValue
            val settings = params?.get("threadSettings")?.objectValue
            if (id != null && settings != null) observeExecution(id, settings)
        }
        if (supportsSettingsSync && method == "remodex/runtimeSettings/updated") {
            val p = message.params?.objectValue
            val id = p?.get("threadId")?.stringValue
            val settings = p?.get("runtimeSettings")?.objectValue
            if (id != null && settings != null) applyConfirmedSettings(id, settings)
        }
        if (method == "thread/goal/updated" || method == "thread/goal/cleared" || method == "turn/started" || method == "turn/completed") {
            message.params?.objectValue?.let { _conversationEvents.tryEmit(method to it) }
        }
        if (method in setOf("turn/started", "turn/completed", "item/autoApprovalReview/started", "item/autoApprovalReview/completed")) {
            message.params?.objectValue?.let { p ->
                liveFollowUps.observe(method, p, _runningTurnIdByThread.value[p["threadId"]?.stringValue])
            }
        }
        if (changeLedger != null && method in setOf("turn/started", "turn/completed", "item/completed", "turn/diff/updated")) {
            val p = message.params?.objectValue
            val thread = p?.get("threadId")?.stringValue
            if (p != null && thread != null && p["remodexReplayedEvent"]?.boolValue != true && p["remodexRolloutBootstrapReplay"]?.boolValue != true) {
                val device = resolvedMacScopedPersistenceDeviceId()
                val captured = turnPatches.observe(method, p, _threads.value.firstOrNull { it.id == thread }?.cwd)
                if (device != null && captured != null) scope.launch {
                    try {
                        changeLedger.update(device) { current ->
                            if (current.any { it.turnId == captured.turnId && it.threadId == thread }) current else current + captured
                        }
                    } catch (failure: java.lang.Exception) { Log.e("RemodexLedger", "persist captured change set", failure) }
                }
            }
        }
        handleSidebarNotification(method, message.params)
        incomingRouter.dispatchNotification(method, message.params)
        return
    }
    val id = message.id ?: return
    completePendingRpc(id, message)
}

internal fun CodexService.completePendingRpc(
    id: JSONValue,
    message: RPCMessage,
) {
    val key = idKey(id)
    val def = pendingRpc.remove(key) ?: return
    val err = message.error
    if (err != null) {
        def.completeExceptionally(CodexServiceError.RpcFailure(err))
    } else {
        def.complete(message)
    }
}

internal fun idKey(id: JSONValue): String =
    when (id) {
        is JSONValue.Str -> "s:${id.value}"
        is JSONValue.NumLong -> "i:${id.value}"
        is JSONValue.NumDouble -> "d:${id.value}"
        is JSONValue.Bool -> "b:${id.value}"
        is JSONValue.Null -> "null"
        else -> "complex:$id"
    }

internal suspend fun CodexService.initializeSession() {
    val appVersion = readAppVersion()
    val clientInfo =
        JSONValue.Obj(
            mapOf(
                "name" to JSONValue.Str("codexmobile_android"),
                "title" to JSONValue.Str("CodexMobile Android"),
                "version" to JSONValue.Str(appVersion),
            ),
        )
    val modern =
        JSONValue.Obj(
            mapOf(
                "clientInfo" to clientInfo,
                "capabilities" to
                    JSONValue.Obj(
                        mapOf("experimentalApi" to JSONValue.Bool(true)),
                    ),
            ),
        )
    val initialize = try {
        rpcRequestWhileHandshaking("initialize", modern)
    } catch (e: Exception) {
        if (!shouldRetryInitializeWithoutCapabilities(e)) {
            throw e
        }
        val legacy = JSONValue.Obj(mapOf("clientInfo" to clientInfo))
        rpcRequestWhileHandshaking("initialize", legacy)
    }
    supportsSettingsSync = initialize.result?.objectValue?.get("remodexRuntimeSettingsVersion")?.intValue == 2
    sendMessage(RPCMessage.notification(method = "initialized", params = null, includeJsonRpc = false))
}

private fun shouldRetryInitializeWithoutCapabilities(e: Throwable): Boolean {
    val rpc = (e as? CodexServiceError.RpcFailure)?.rpcError ?: return false
    if (rpc.code != -32600 && rpc.code != -32602) return false
    val msg = rpc.message.lowercase()
    if (!msg.contains("capabilities") && !msg.contains("experimentalapi")) return false
    return msg.contains("unknown") ||
        msg.contains("unexpected") ||
        msg.contains("unrecognized") ||
        msg.contains("invalid") ||
        msg.contains("unsupported") ||
        msg.contains("field")
}

internal suspend fun CodexService.rpcRequestWhileHandshaking(
    method: String,
    params: JSONValue?,
): RPCMessage {
    val id = JSONValue.Str(UUID.randomUUID().toString())
    val req = RPCMessage.request(id = id, method = method, params = params, includeJsonRpc = false)
    val deferred = CompletableDeferred<RPCMessage>()
    pendingRpc[idKey(id)] = deferred
    try {
        sendMessage(req)
        return deferred.await()
    } finally {
        pendingRpc.remove(idKey(id))
    }
}

internal fun CodexService.readAppVersion(): String = readRemodexAppVersionName(appContext)
