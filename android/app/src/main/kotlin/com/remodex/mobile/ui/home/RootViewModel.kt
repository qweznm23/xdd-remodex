package com.remodex.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.remodex.mobile.AppContainer
import com.remodex.mobile.core.persistence.OnboardingPreferences
import com.remodex.mobile.core.persistence.SessionPersistence
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.core.transport.isBridgeUnavailableClose
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.pairing.reconnectUsingSavedRelaySnapshot
import com.remodex.mobile.pairing.retryTemporaryBridgeAbsence
import com.remodex.mobile.services.DesktopHandoffService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class RootPhase {
    Onboarding,
    PairingScan,
    PairingCode,
    Main,
}

/**
 * Root shell: onboarding / pairing / main, auto-connect, restore thread.
 * Mirrors [ContentViewModel.swift](CodexMobile/CodexMobile/Views/Home/ContentViewModel.swift) in a reduced form.
 */
class RootViewModel(
    private val repository: CodexRepository,
    private val sessionPersistence: SessionPersistence,
    private val onboardingPreferences: OnboardingPreferences,
) : ViewModel() {
    private var lastAutoConnectAttemptMs = 0L
    private var restoredActiveThread = false
    private var autoReconnectJob: Job? = null
    private var manualReconnectJob: Job? = null
    private val trustedReconnectFailureBudget = RootTrustedReconnectFailureBudget()

    private val _phase = MutableStateFlow(computeInitialPhase())
    val phase: StateFlow<RootPhase> = _phase.asStateFlow()

    private val _reconnectUiState = MutableStateFlow(RootReconnectUiState())
    val reconnectUiState: StateFlow<RootReconnectUiState> = _reconnectUiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.connectionState.collect { state ->
                scheduleAutoReconnectAfterDropIfNeeded(state)
            }
        }
    }

    private fun computeInitialPhase(): RootPhase {
        if (FORCE_ONBOARDING_ON_EVERY_COLD_START || !onboardingPreferences.hasSeenOnboarding()) {
            return RootPhase.Onboarding
        }
        if (!hasRelayPairing()) {
            return RootPhase.PairingScan
        }
        return RootPhase.Main
    }

    private fun hasRelayPairing(): Boolean {
        val s = sessionPersistence.loadRelaySnapshot()
        return !s.relaySessionId.isNullOrBlank() && !s.relayUrl.isNullOrBlank()
    }

    fun finishOnboarding() {
        onboardingPreferences.setHasSeenOnboarding(true)
        viewModelScope.launch {
            AppContainer.betaEngagementRepository.recordMissionEvent(
                eventType = "onboarding_completed",
                screen = "onboarding",
                refreshAfter = false,
            )
        }
        _phase.value = RootPhase.PairingScan
    }

    fun notifyPairingSuccess() {
        stopAutoReconnectForManualControl()
        trustedReconnectFailureBudget.reset()
        _reconnectUiState.value = RootReconnectUiState()
        _phase.value = RootPhase.Main
    }

    fun closePairingScanner() {
        _phase.value = RootPhase.Main
    }

    fun openPairingScanner() {
        stopAutoReconnectForManualControl()
        _phase.value = RootPhase.PairingScan
    }

    fun openPairingCode() {
        stopAutoReconnectForManualControl()
        _phase.value = RootPhase.PairingCode
    }

    fun onAppLaunched() {
        autoReconnectJob?.cancel()
        autoReconnectJob = viewModelScope.launch {
            restoreActiveThreadIfNeeded(allowBeforeSessionReady = true)
            attemptAutoConnectIfNeeded(minIntervalMs = 0L)
        }
    }

    fun onAppForegrounded() {
        if (autoReconnectJob?.isActive == true || manualReconnectJob?.isActive == true) return
        autoReconnectJob = viewModelScope.launch {
            if (repository.isSessionReady.value) {
                restoreActiveThreadIfNeeded()
                val active = repository.activeThreadId.value
                if (!active.isNullOrBlank()) {
                    repository.setActiveThreadId(active)
                    runCatching { repository.refreshUsageStatus(active) }
                } else {
                    repository.refreshThreads()
                }
                return@launch
            }
            attemptAutoConnectIfNeeded(minIntervalMs = 2_000L)
        }
    }

    fun reconnectSavedPairingManually() {
        val previousAuto = stopAutoReconnectForManualControl()
        if (manualReconnectJob?.isActive == true) return
        manualReconnectJob =
            viewModelScope.launch {
                previousAuto?.join()
                attemptSavedPairingReconnect(RootReconnectAttempt.Manual)
            }
    }

    fun wakeSavedComputerDisplay() {
        val previousAuto = stopAutoReconnectForManualControl()
        if (manualReconnectJob?.isActive == true) return
        manualReconnectJob =
            viewModelScope.launch {
                previousAuto?.join()
                wakeSavedComputerDisplayThenReconnect()
            }
    }

    /** Best-effort reconnect after npm bridge update (parity iOS `retryBridgeConnectionAfterUpdate`). */
    fun retryBridgeConnectionAfterUpdate() {
        reconnectSavedPairingManually()
    }

    suspend fun restoreActiveThreadIfNeeded(allowBeforeSessionReady: Boolean = false) {
        if (!allowBeforeSessionReady && !repository.isSessionReady.value) return
        val pending = AppContainer.consumePendingOpenThreadFromNotification()
        if (pending != null) {
            repository.setActiveThreadId(pending)
            restoredActiveThread = true
            return
        }
        if (restoredActiveThread) return
        val id = repository.activeThreadId.value ?: return
        repository.setActiveThreadId(id)
        restoredActiveThread = true
    }

    fun persistActiveThreadId(threadId: String?) {
        viewModelScope.launch {
            repository.setActiveThreadId(threadId)
        }
    }

    private suspend fun attemptAutoConnectIfNeeded(minIntervalMs: Long) {
        val snap = sessionPersistence.loadRelaySnapshot()
        val hasRelayPairing = !snap.relayUrl.isNullOrBlank() && !snap.relaySessionId.isNullOrBlank()
        if (!shouldAttemptSavedPairingReconnect(
                phase = _phase.value,
                hasRelayPairing = hasRelayPairing,
                sessionReady = repository.isSessionReady.value,
                connectionState = repository.connectionState.value,
            )
        ) {
            return
        }
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0 && now - lastAutoConnectAttemptMs < minIntervalMs) return
        lastAutoConnectAttemptMs = now
        attemptSavedPairingReconnect(RootReconnectAttempt.Auto)
    }

    private fun scheduleAutoReconnectAfterDropIfNeeded(connectionState: ConnectionState) {
        val snap = sessionPersistence.loadRelaySnapshot()
        val hasRelayPairing = !snap.relayUrl.isNullOrBlank() && !snap.relaySessionId.isNullOrBlank()
        val bridgeWentAway = isBridgeUnavailableClose((connectionState as? ConnectionState.Error)?.relayCloseCode)
        val previousAuto = autoReconnectJob
        val previousManual = manualReconnectJob
        if (!shouldScheduleAutoReconnectAfterDrop(
                phase = _phase.value,
                hasRelayPairing = hasRelayPairing,
                sessionReady = repository.isSessionReady.value,
                connectionState = connectionState,
                reconnectAlreadyActive = previousAuto?.isActive == true || previousManual?.isActive == true,
            )
        ) {
            return
        }
        autoReconnectJob =
            viewModelScope.launch {
                if (bridgeWentAway) {
                    // A drop from an established socket may race the foreground job's completion.
                    previousAuto?.join()
                    previousManual?.join()
                    if (repository.connectionState.value !== connectionState) return@launch
                }
                delay(AUTO_RECONNECT_AFTER_DROP_DELAY_MS)
                attemptAutoConnectIfNeeded(minIntervalMs = if (bridgeWentAway) 0L else AUTO_RECONNECT_MIN_INTERVAL_MS)
            }
    }

    private suspend fun attemptSavedPairingReconnect(attempt: RootReconnectAttempt) {
        val snap = sessionPersistence.loadRelaySnapshot()
        val hasRelayPairing = !snap.relayUrl.isNullOrBlank() && !snap.relaySessionId.isNullOrBlank()
        if (!shouldAttemptSavedPairingReconnect(
                phase = _phase.value,
                hasRelayPairing = hasRelayPairing,
                sessionReady = repository.isSessionReady.value,
                connectionState = repository.connectionState.value,
                forceReconnect = attempt == RootReconnectAttempt.Manual,
            )
        ) {
            return
        }
        val attemptState = RootReconnectUiState(attempt = attempt)
        _reconnectUiState.value = attemptState
        try {
            runCatching {
                when (attempt) {
                    RootReconnectAttempt.Auto,
                    RootReconnectAttempt.Manual -> {
                        retryTemporaryBridgeAbsence { index ->
                            val current = sessionPersistence.loadRelaySnapshot()
                            if (index > 0) {
                                val originalDevice = snap.relayMacDeviceId ?: snap.lastTrustedMacDeviceId
                                val targetDevice = current.relayMacDeviceId ?: current.lastTrustedMacDeviceId
                                val stillOffline = shouldAttemptSavedPairingReconnect(
                                    _phase.value, !current.relaySessionId.isNullOrBlank() && !current.relayUrl.isNullOrBlank(),
                                    repository.isSessionReady.value, repository.connectionState.value,
                                )
                                if (!stillOffline || targetDevice != originalDevice || current.relayUrl != snap.relayUrl) {
                                    throw kotlinx.coroutines.CancellationException("Reconnect target or connection state changed")
                                }
                            }
                            reconnectUsingSavedRelaySnapshot(repository, current,
                                sessionPersistence.loadLocalRelayHostOverride().orEmpty())
                        }
                    }
                    RootReconnectAttempt.WakeDisplay ->
                        DesktopHandoffService(
                            repository = repository,
                            savedRelaySnapshotProvider = { sessionPersistence.loadRelaySnapshot() },
                        ).wakeDisplay()
                }
            }.fold(
                onSuccess = {
                    trustedReconnectFailureBudget.reset()
                    _reconnectUiState.value = RootReconnectUiState()
                    if (attempt != RootReconnectAttempt.WakeDisplay) {
                        viewModelScope.launch {
                            AppContainer.betaEngagementRepository.recordMissionEvent(
                                eventType = "reconnect_completed",
                                screen = "connection",
                            )
                        }
                    }
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    val recoveryDecision = trustedReconnectFailureBudget.record(error)
                    if (recoveryDecision.shouldDropSavedRelaySession) {
                        sessionPersistence.clearRelaySession()
                        _phase.value = RootPhase.PairingScan
                    }
                    _reconnectUiState.value =
                        RootReconnectUiState(
                            recoveryAction = recoveryDecision.recoveryAction,
                            wakeDisplayAvailable =
                                recoveryDecision.recoveryAction == RootReconnectRecoveryAction.RetrySavedPairing &&
                                error !is com.remodex.mobile.core.transport.ConnectionStageTimeout,
                            lastErrorMessage =
                                if (recoveryDecision.shouldDropSavedRelaySession) {
                                    recoveryDecision.fallbackMessage
                                } else {
                                    error.message?.trim()?.takeIf { it.isNotEmpty() }
                                        ?: recoveryDecision.fallbackMessage
                                },
                        )
                },
            )
        } finally {
            // A cancelled attempt must not leave the spinner active or overwrite a newer attempt.
            if (_reconnectUiState.value === attemptState) {
                _reconnectUiState.value = RootReconnectUiState()
            }
        }
    }

    private suspend fun wakeSavedComputerDisplayThenReconnect() {
        val snap = sessionPersistence.loadRelaySnapshot()
        val hasRelayPairing = !snap.relayUrl.isNullOrBlank() && !snap.relaySessionId.isNullOrBlank()
        if (!hasRelayPairing) {
            _reconnectUiState.value =
                RootReconnectUiState(
                    recoveryAction = RootReconnectRecoveryAction.ScanNewQr,
                    lastErrorMessage = "Reconnect to your paired computer or scan a new QR code first.",
                )
            return
        }
        _reconnectUiState.value =
            RootReconnectUiState(
                attempt = RootReconnectAttempt.WakeDisplay,
                wakeDisplayAvailable = true,
                lastErrorMessage = "Waking your computer...",
            )
        runCatching {
            DesktopHandoffService(
                repository = repository,
                savedRelaySnapshotProvider = { sessionPersistence.loadRelaySnapshot() },
            ).wakeDisplay()
        }.fold(
            onSuccess = {
                if (repository.isSessionReady.value) {
                    _reconnectUiState.value = RootReconnectUiState()
                } else {
                    attemptSavedPairingReconnect(RootReconnectAttempt.Manual)
                }
            },
            onFailure = { error ->
                val recoveryDecision = trustedReconnectFailureBudget.record(error)
                if (recoveryDecision.shouldDropSavedRelaySession) {
                    sessionPersistence.clearRelaySession()
                    _phase.value = RootPhase.PairingScan
                }
                _reconnectUiState.value =
                    RootReconnectUiState(
                        recoveryAction = recoveryDecision.recoveryAction,
                        wakeDisplayAvailable =
                            recoveryDecision.recoveryAction == RootReconnectRecoveryAction.RetrySavedPairing,
                        lastErrorMessage =
                            if (recoveryDecision.shouldDropSavedRelaySession) {
                                recoveryDecision.fallbackMessage
                            } else {
                                error.message?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: recoveryDecision.fallbackMessage
                            },
                    )
            },
        )
    }

    private fun stopAutoReconnectForManualControl(): Job? {
        val previous = autoReconnectJob
        previous?.cancel()
        autoReconnectJob = null
        if (_reconnectUiState.value.attempt == RootReconnectAttempt.Auto) {
            _reconnectUiState.value = RootReconnectUiState()
        }
        return previous
    }

    companion object {
        /** When true, always open the 5-step onboarding on process start (QA only). */
        private const val FORCE_ONBOARDING_ON_EVERY_COLD_START = false

        private const val AUTO_RECONNECT_AFTER_DROP_DELAY_MS = 1_000L
        private const val AUTO_RECONNECT_MIN_INTERVAL_MS = 5_000L

        fun factory(): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(RootViewModel::class.java)) {
                        return RootViewModel(
                            AppContainer.codexRepository,
                            AppContainer.sessionPersistence,
                            OnboardingPreferences(AppContainer.appContext),
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel: $modelClass")
                }
            }
    }
}
