package com.remodex.mobile.services

import android.util.Log
import com.remodex.mobile.core.transport.awaitConnectionStage
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.core.transport.SecureControlMultiplexer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mirrors [CodexService+Connection.swift](../../../../../../../../CodexMobile/CodexMobile/Services/CodexService+Connection.swift):
 * connect / disconnect lifecycle and session reset.
 */
internal suspend fun CodexService.connectImpl(
    serverUrl: String,
    token: String,
    role: String?,
) = connectionLifecycleMutex.withLock { withContext(Dispatchers.IO) {
    withContext(NonCancellable) { resetBridgeSession(preservePresentationState = true) }
    _connectionState.value = ConnectionState.Connecting
    controlMux = SecureControlMultiplexer(json) { }
    val inbound = Channel<String>(capacity = 64)
    wireInbound = inbound
    wireJob =
        scope.launch {
            for (line in inbound) {
                try {
                    processWireText(line)
                } catch (_: CancellationException) {
                    break
                } catch (error: Exception) {
                    Log.e("RemodexConnection", "Receive failed stage=wire_dispatch generation=$connectionGeneration", error)
                }
            }
        }

    var stage = "websocket_open"
    try {
        awaitConnectionStage(stage, 15_000L) {
            openWebSocketAwaitOpen(parseRelayHttpUrl(serverUrl), token, role)
        }
        stage = "secure_handshake"
        awaitConnectionStage(stage, 30_000L) { performSecureHandshake() }
        stage = "initialize"
        awaitConnectionStage(stage, 20_000L) { initializeSession() }
    } catch (t: Throwable) {
        closingByClient = true
        try {
            withContext(NonCancellable) { resetBridgeSession(preservePresentationState = true) }
        } catch (cleanup: Throwable) {
            t.addSuppressed(cleanup)
        } finally {
            closingByClient = false
        }
        if (t !is CancellationException) {
            Log.e("RemodexConnection", "Connection failed stage=$stage attempt=1", t)
        }
        _connectionState.value = if (t is CancellationException) ConnectionState.Offline else
            ConnectionState.Error(
                t.message?.trim()?.takeIf { it.isNotEmpty() } ?: "Connection failed",
            )
        throw t
    }
    sessionReady = true
    _isSessionReady.value = true
    _connectionState.value = ConnectionState.Connected
    scope.launch(Dispatchers.IO) {
        runCatching {
            refreshThreadsInternal()
            refreshInactiveRunningThreadStatesInternal()
        }
    }
    scope.launch(Dispatchers.IO) {
        runCatching { refreshModelsInternal() }
    }
    scope.launch(Dispatchers.IO) {
        runCatching { refreshRateLimitsInternal() }
    }
    _activeThreadId.value?.let { activeId ->
        scope.launch(Dispatchers.IO) {
            catchUpThreadAfterSelectionOrReconnect(activeId)
            refreshInactiveRunningThreadStatesInternal()
        }
    }
    Unit
} }

internal suspend fun CodexService.disconnectImpl(preservePresentationState: Boolean = false) =
    connectionLifecycleMutex.withLock { withContext(NonCancellable + Dispatchers.IO) {
        closingByClient = true
        try {
            resetBridgeSession(preservePresentationState = preservePresentationState)
            _connectionState.value = ConnectionState.Offline
        } finally {
            closingByClient = false
        }
    } }

internal suspend fun CodexService.setActiveThreadIdImpl(threadId: String?) {
    _activeThreadId.value = threadId?.trim()?.takeIf { it.isNotEmpty() }
    val id = _activeThreadId.value
    syncActiveThreadRuntimeSelection()
    persistActiveThreadId(id)
    if (id != null && sessionReady) {
        scope.launch(Dispatchers.IO) {
            catchUpThreadAfterSelectionOrReconnect(id)
        }
    }
}

internal suspend fun CodexService.resetBridgeSession(preservePresentationState: Boolean = false) {
    connectionGeneration++
    predictionStore.disconnected()
    val oldSocket = webSocket
    webSocket = null
    oldSocket?.cancel()
    sessionReady = false
    _isSessionReady.value = false
    supportsServiceTier = true
    supportsTurnCollaborationMode = true
    supportsThreadFork = true
    _bridgeUpdatePrompt.value = null
    hasPresentedServiceTierBridgeUpdatePrompt = false
    hasPresentedThreadForkBridgeUpdatePrompt = false
    if (!preservePresentationState) {
        _threads.value = emptyList()
        _activeThreadId.value = null
    }
    if (!preservePresentationState) {
        synchronized(runningTurnStateLock) {
            _runningTurnIdByThread.value = emptyMap()
            _protectedRunningFallbackThreadIds.value = emptySet()
        }
    }
    clearPendingServerRequests()
    messageTimelineStore.invalidateLiveQuestions()
    hydratedThreadIds.clear()
    resumedThreadIds.clear()
    _threadHistoryPaginationByThread.value = emptyMap()
    _loadingOlderHistoryThreadIds.value = emptySet()
    _olderHistoryErrorByThread.value = emptyMap()
    authoritativeProjectPathByThreadId.clear()
    associatedManagedWorktreePathByThreadId.clear()
    val device = resolvedMacScopedPersistenceDeviceId()
    associatedManagedWorktreePathByThreadId.putAll(
        if (device != null) {
            macScopedSessionStore.loadAssociatedManagedWorktreePaths(device)
        } else {
            sessionPersistence.loadAssociatedManagedWorktreePaths()
        },
    )
    turnDraftQueueStore.clear()
    testRpcRequestHandler = null
    loadingHistory.clear()
    liveFollowUps.clear()
    turnPatches.clear()
    catalogVerifier.reset()
    sourceReplacements.clear()
    latestCatalogEvents.clear()
    supportsSettingsSync = false
    incomingRouter.resetCaches()
    if (!preservePresentationState) {
        commandExecutionDetailsStore.clear()
    }
    wireJob?.cancelAndJoin()
    wireJob = null
    replayHistoryInvalidated = false
    wireInbound?.cancel(CancellationException("Connection generation replaced"))
    wireInbound = null
    secureSession = null
    controlMux?.reset()
    controlMux = null
    pendingRpc.values.forEach { d ->
        d.cancel(CancellationException("disconnected"))
    }
    pendingRpc.clear()
    clearAccountQuota()
    _contextWindowUsageByThread.value = emptyMap()
    _contextWindowUsageLoadingThreads.value = emptySet()
    _contextWindowUsageErrorByThread.value = emptyMap()
}

internal fun CodexService.scheduleWireDrop(message: String, generation: Long = connectionGeneration, relayCloseCode: Int? = null) {
    if (closingByClient) return
    synchronized(wireDropHandling) {
        if (wireDropGeneration == generation) return
        wireDropGeneration = generation
    }
    scope.launch(Dispatchers.IO) {
        try {
            connectionLifecycleMutex.withLock {
                if (connectionGeneration != generation || !sessionReady) return@withLock
                withContext(NonCancellable) { resetBridgeSession(preservePresentationState = true) }
                _connectionState.value = ConnectionState.Error(message, relayCloseCode)
            }
        } finally {
            synchronized(wireDropHandling) {
                if (wireDropGeneration == generation) wireDropGeneration = null
            }
        }
    }
}
