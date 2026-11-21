package com.remodex.mobile.data

import com.remodex.mobile.core.model.CodexBridgeUpdatePrompt
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexImageAttachment
import com.remodex.mobile.core.model.CodexAccessMode
import com.remodex.mobile.core.model.CodexCollaborationModeKind
import com.remodex.mobile.core.model.CommandExecutionDetails
import com.remodex.mobile.core.model.ContextWindowUsage
import com.remodex.mobile.core.model.CodexModelOption
import com.remodex.mobile.core.model.CodexRateLimitBucket
import com.remodex.mobile.core.model.CodexReviewTarget
import com.remodex.mobile.core.model.CodexServiceTier
import com.remodex.mobile.core.model.CodexTurnMention
import com.remodex.mobile.core.model.CodexTurnSkillMention
import com.remodex.mobile.core.model.CodexPairingQRPayload
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexTrustedMacRecord
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.PendingApprovalDecision
import com.remodex.mobile.core.model.PendingApprovalRequest
import com.remodex.mobile.core.model.PendingStructuredInputRequest
import com.remodex.mobile.core.model.RPCMessage
import com.remodex.mobile.core.model.ThreadHistoryPaginationState
import com.remodex.mobile.core.model.UsageStatusRefreshPolicy
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.core.model.GitStackedActionProgressEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Facade for bridge WebSocket, secure transport, and JSON-RPC.
 */
data class ComposerPrediction(val sourceTurnId: String, val text: String, val collapsedByTyping: Boolean = false)
private val emptyFollowUps = kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<ThreadFollowUp>>>(emptyMap())
private val emptyPredictions = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ComposerPrediction>>(emptyMap())

interface CodexRepository {
    val threadFollowUps: StateFlow<Map<String, List<ThreadFollowUp>>> get() = emptyFollowUps
    suspend fun performFollowUp(threadId: String, row: ThreadFollowUp): Unit = throw UnsupportedOperationException()
    val conversationEvents: kotlinx.coroutines.flow.Flow<Pair<String, Map<String, JSONValue>>>
        get() = kotlinx.coroutines.flow.emptyFlow()
    val composerPredictions: StateFlow<Map<String, ComposerPrediction>> get() = emptyPredictions
    fun dismissPredictionForTyping(threadId: String) = Unit
    fun dismissComposerPrediction(threadId: String, turnId: String) = Unit

    val isSessionReady: StateFlow<Boolean>

    val connectionState: StateFlow<ConnectionState>

    /** Sidebar threads from the thread list RPC plus incremental thread lifecycle notifications. */
    val threads: StateFlow<List<CodexThread>>

    /** Local selection; does not call the bridge until send/UI layers do. */
    val activeThreadId: StateFlow<String?>

    val sidebarActivity: StateFlow<com.remodex.mobile.core.model.CodexSidebarState>
        get() = EmptySidebarActivity
    suspend fun setThreadPinned(threadId: String, deviceId: String, pinned: Boolean) {}
    suspend fun markThreadRead(threadId: String, deviceId: String, revision: String) {}


    /** Per-thread message rows (persisted + live notifications). */
    val messagesByThread: StateFlow<Map<String, List<CodexMessage>>>
    val questionThreadIds: StateFlow<Set<String>> get() = EmptyQuestionThreads
    val conversationQuestionsByThread: StateFlow<Map<String, List<com.remodex.mobile.core.model.CodexStructuredUserInputRequest>>> get() = EmptyConversationQuestions

    /** Cursor-backed history state for large threads. */
    val threadHistoryPaginationByThread: StateFlow<Map<String, ThreadHistoryPaginationState>>

    val loadingOlderHistoryThreadIds: StateFlow<Set<String>>
    val loadingHistoryThreadIds: StateFlow<Set<String>> get() = EmptyLoadingHistoryThreads

    val olderHistoryErrorByThread: StateFlow<Map<String, String>>

    /** Live structured command metadata keyed by command item id; timeline text remains authoritative history. */
    val commandExecutionDetailsByItemId: StateFlow<Map<String, CommandExecutionDetails>>

    /** Local queued draft count per thread (J22 foundation). */
    val turnDraftQueueDepthByThread: StateFlow<Map<String, Int>>

    val turnDraftQueuePreviewByThread: StateFlow<Map<String, List<QueuedTurnDraftPreview>>>

    /** One-shot UI request to open the branch picker for a newly-created repo-bound thread. */
    val pendingBranchPickerThreadId: StateFlow<String?>

    /** Thread id → turn id in esecuzione (per UI Stop). */
    val runningTurnIdByThread: StateFlow<Map<String, String>>

    /**
     * Thread con run attivo ma senza `turnId` ancora noto (parity iOS `protectedRunningFallback` / `runningThreadIDs`).
     */
    val protectedRunningFallbackThreadIds: StateFlow<Set<String>>

    val availableModels: StateFlow<List<CodexModelOption>>

    val isLoadingModels: StateFlow<Boolean>

    val modelsErrorMessage: StateFlow<String?>

    /** Account rate limits from the bridge (`account/rateLimits/read` + push updates). */
    val rateLimitBuckets: StateFlow<List<CodexRateLimitBucket>>

    val accountUsageDetails: StateFlow<com.remodex.mobile.core.model.AccountUsageDetails>
        get() = EmptyAccountUsageDetails

    val isLoadingRateLimits: StateFlow<Boolean>

    val rateLimitsErrorMessage: StateFlow<String?>

    /** True after a successful read or an `account/rateLimits/updated` merge. */
    val hasResolvedRateLimitsSnapshot: StateFlow<Boolean>

    /** Cached `thread/contextWindow/read` snapshots keyed by thread id. */
    val contextWindowUsageByThread: StateFlow<Map<String, ContextWindowUsage>>

    /** Thread ids currently waiting on `thread/contextWindow/read`. */
    val contextWindowUsageLoadingThreads: StateFlow<Set<String>>

    /** Per-thread last refresh error (cleared on success). */
    val contextWindowUsageErrorByThread: StateFlow<Map<String, String>>

    val selectedModelId: StateFlow<String?>

    val selectedReasoningEffort: StateFlow<String?>

    val selectedAccessMode: StateFlow<CodexAccessMode>

    val selectedServiceTier: StateFlow<CodexServiceTier?>

    val pendingApprovalRequest: StateFlow<PendingApprovalRequest?>

    val pendingStructuredInputRequest: StateFlow<PendingStructuredInputRequest?>
    val pendingStructuredInputRequests: StateFlow<Map<String, PendingStructuredInputRequest>>
        get() = EmptyPendingQuestions
    suspend fun answerConversationQuestions(threadId: String, request: com.remodex.mobile.core.model.CodexStructuredUserInputRequest, answers: Map<String, List<String>>, allowHistorical: Boolean = false) {
        throw UnsupportedOperationException("Question replies are unavailable")
    }
    suspend fun skipConversationQuestions(threadId: String, request: com.remodex.mobile.core.model.CodexStructuredUserInputRequest) {
        throw UnsupportedOperationException("Question skipping is unavailable")
    }

    /** Recoverable npm / pairing prompts from the service layer (bridge upgrade, unsupported runtime fields). */
    val bridgeUpdatePrompt: StateFlow<CodexBridgeUpdatePrompt?>

    /** Trusted Mac registry records surfaced for My Devices UI. */
    val trustedDevices: StateFlow<List<CodexTrustedMacRecord>>

    val switchingDeviceId: StateFlow<String?>

    val deviceSwitchNotice: StateFlow<String?>

    val currentTrustedMacDeviceId: StateFlow<String?>

    val previousTrustedMacDeviceId: StateFlow<String?>

    val fileTransferBaseUrl: String? get() = null

    val relayMacDeviceId: StateFlow<String?>

    /**
     * Relay `x-role`: `mac` (bridge) or `iphone` (mobile client). When [role] is null, Android sends
     * `iphone` for compatibility with hosted relay deployments that have not rolled out the Android alias.
     * Non-empty [token] is also sent as `Authorization: Bearer`.
     */
    suspend fun connect(
        serverUrl: String,
        token: String,
        role: String? = null,
    )

    suspend fun disconnect()

    /** Resolve the current relay session before reconnecting a previously paired device. */
    suspend fun resolveReconnectSnapshot(
        snapshot: com.remodex.mobile.core.persistence.RelaySessionSnapshot,
        relayHostOverride: String = "",
    ): com.remodex.mobile.core.persistence.RelaySessionSnapshot = snapshot

    suspend fun setActiveThreadId(threadId: String?)

    suspend fun refreshModels()

    /** Fetches rate limit buckets via `account/rateLimits/read` (with param-shape retry). */
    suspend fun refreshRateLimits()

    /** Fetches context window usage via `thread/contextWindow/read` for [threadId]. */
    suspend fun refreshContextWindowUsage(threadId: String)

    /** Refreshes the combined usage status for the current thread: context window + account rate limits. */
    suspend fun refreshUsageStatus(threadId: String?, force: Boolean = false) {
        val tid = threadId?.trim().orEmpty()
        if (tid.isNotEmpty()) {
            refreshContextWindowUsage(tid)
        }
        refreshRateLimits()
    }

    /** Mirrors iOS `shouldAutoRefreshUsageStatus(threadId:)`. */
    fun shouldAutoRefreshUsageStatus(threadId: String?): Boolean =
        UsageStatusRefreshPolicy.shouldAutoRefresh(
            sessionReady = isSessionReady.value,
            connected = connectionState.value is ConnectionState.Connected,
            threadId = threadId,
            contextWindowUsageByThread = contextWindowUsageByThread.value,
            hasResolvedRateLimitsSnapshot = hasResolvedRateLimitsSnapshot.value,
        )

    suspend fun setSelectedModelId(modelId: String?)

    suspend fun setSelectedReasoningEffort(reasoningEffort: String?)

    suspend fun setSelectedAccessMode(accessMode: CodexAccessMode)

    suspend fun setSelectedServiceTier(serviceTier: CodexServiceTier?)

    /** Persists the composer collaboration mode as thread-local UI/runtime state. */
    suspend fun setThreadCollaborationMode(
        threadId: String,
        mode: CodexCollaborationModeKind,
    ) = Unit

    suspend fun resolvePendingApproval(
        requestId: String,
        decision: PendingApprovalDecision,
    )

    suspend fun resolvePendingStructuredInput(
        requestId: String,
        answersByQuestionId: Map<String, List<String>>,
    )

    /** Clears [bridgeUpdatePrompt] after the user dismisses the sheet. */
    fun dismissBridgeUpdatePrompt()

    /** Best-effort `thread/list` refresh (active + archived); no-op when disconnected. */
    suspend fun refreshThreads()

    /** Loads [thread/read] (includeTurns) and merges into [messagesByThread]. */
    suspend fun syncThreadHistory(
        threadId: String,
        force: Boolean = false,
    )

    suspend fun refreshConversation(threadId: String) = syncThreadHistory(threadId, force = true)

    /** Loads the next older [thread/read] page when the bridge/runtime exposes a cursor. */
    suspend fun loadOlderThreadHistory(threadId: String) {
        syncThreadHistory(threadId, force = true)
    }

    suspend fun sendRequest(
        method: String,
        params: JSONValue?,
    ): RPCMessage

    suspend fun renameThread(
        threadId: String,
        name: String,
    ): Unit = throw UnsupportedOperationException("renameThread is not implemented by this repository")

    /** Removes a chat from Android's local list without mutating the paired desktop runtime. */
    suspend fun deleteThreadLocally(threadId: String): Unit =
        throw UnsupportedOperationException("deleteThreadLocally is not implemented by this repository")

    suspend fun unarchiveThread(threadId: String): Unit = throw UnsupportedOperationException("thread/unarchive")

    /** Archives every root thread in a sidebar project group so the folder disappears from the live list. */
    suspend fun archiveThreadGroup(threadIds: List<String>): List<String> =
        throw UnsupportedOperationException("archiveThreadGroup is not implemented by this repository")

    /** Removes every thread in a sidebar group without issuing per-thread RPC mutations. */
    suspend fun deleteLocalThreadGroup(threadIds: List<String>): List<String> =
        throw UnsupportedOperationException("deleteLocalThreadGroup is not implemented by this repository")

    /**
     * WAV clip to transcript. Current bridges own `voice/transcribe`; older bridges fall back to
     * `voice/resolveAuth` plus the legacy direct ChatGPT upload.
     */
    suspend fun transcribeBridgeVoiceWav(
        wavBytes: ByteArray,
        durationSeconds: Double,
    ): String

    suspend fun loadComposerDraft(threadId: String): String = ""

    suspend fun saveComposerDraft(
        threadId: String,
        draft: String,
    ) = Unit

    suspend fun clearComposerDraft(threadId: String) = saveComposerDraft(threadId, "")

    /**
     * Creates a thread via `thread/start` (parity with iOS `startThreadImpl`).
     * Updates [threads] and [activeThreadId] on success.
     */
    suspend fun startThread(
        model: String? = null,
        cwd: String? = null,
        serviceTier: String? = null,
    ): CodexThread

    /** Prepare an editable recovered draft before exposing the new conversation as active. */
    suspend fun startThreadForRecoveredDraft(
        model: String?,
        cwd: String?,
        prepare: (String) -> Unit,
    ): CodexThread = throw UnsupportedOperationException("Draft recovery is unavailable")

    fun requestBranchPickerForThread(threadId: String) {}

    fun consumeBranchPickerRequest(threadId: String) {}

    /**
     * Same-thread rebind: updates local [threads] + forces [thread/resume] with the preferred cwd
     * while [authoritative] guards prevent stale [thread/list]/[thread/read] from snapping cwd back
     * until the runtime matches. Parity: iOS [CodexService.moveThreadToProjectPath].
     */
    suspend fun moveThreadToProjectPath(
        threadId: String,
        projectPath: String,
    ): CodexThread

    /**
     * Forks an existing thread via `thread/fork` and opens the fork as active.
     * [targetProjectPath] lets callers preserve/project-bind local cwd metadata.
     */
    suspend fun forkThread(
        sourceThreadId: String,
        targetProjectPath: String? = null,
    ): CodexThread = throw UnsupportedOperationException("thread/fork is not implemented by this repository")

    /** In-flight rebind: preferred cwd, or null if none (parity [currentAuthoritativeProjectPath]). */
    fun currentAuthoritativeProjectPathFor(threadId: String): String?

    /** In-memory only until J.7c persistence (parity [associatedManagedWorktreePath] on iOS). */
    fun associatedManagedWorktreePathFor(threadId: String): String?

    /** Invia messaggio utente con `turn/start` (input item `type: text`). */
    suspend fun startTurn(
        threadId: String,
        text: String,
        attachments: List<CodexImageAttachment> = emptyList(),
        skillMentions: List<CodexTurnSkillMention> = emptyList(),
        fileMentions: List<CodexTurnMention> = emptyList(),
        collaborationMode: CodexCollaborationModeKind? = null,
    )

    /** Adds user input to the active regular turn (`turn/steer`). */
    suspend fun steerTurn(
        threadId: String,
        expectedTurnId: String,
        text: String,
        attachments: List<CodexImageAttachment> = emptyList(),
        skillMentions: List<CodexTurnSkillMention> = emptyList(),
        fileMentions: List<CodexTurnMention> = emptyList(),
    )

    /** Starts a native `review/start` turn. Attachments and plan mode are intentionally not supported. */
    suspend fun startReview(
        threadId: String,
        target: CodexReviewTarget,
        baseBranch: String? = null,
    ): Unit = throw UnsupportedOperationException("review/start is not implemented by this repository")

    /** Queue a local draft when a thread is already running (J22 foundation). */
    suspend fun enqueueTurnDraft(
        threadId: String,
        text: String,
        attachments: List<CodexImageAttachment> = emptyList(),
        skillMentions: List<CodexTurnSkillMention> = emptyList(),
        fileMentions: List<CodexTurnMention> = emptyList(),
        collaborationMode: CodexCollaborationModeKind? = null,
        prepend: Boolean = false,
    ) = Unit

    /** Pops the next queued local draft for [threadId], or null if empty. */
    suspend fun pollTurnDraft(threadId: String): QueuedTurnDraft? = null
    suspend fun removeQueuedTurnDraft(
        threadId: String,
        draftId: String,
    ): QueuedTurnDraft? = null

    /** Interrompe il turno attivo (`turn/interrupt`). [turnId] opzionale se già noto. */
    suspend fun interruptTurn(
        threadId: String,
        turnId: String? = null,
    )

    suspend fun sendNotification(
        method: String,
        params: JSONValue?,
    )

    /** Progress events for in-flight `git/runStackedAction` (`git/stackedAction/progress`). */
    val gitStackedActionProgress: Flow<GitStackedActionProgressEvent>
        get() = emptyFlow()

    suspend fun switchToTrustedDevice(deviceId: String) = Unit

    suspend fun switchToScannedDevice(payload: CodexPairingQRPayload) = Unit

    suspend fun cancelDeviceSwitch() = Unit

    fun setDeviceMenuVisible(
        deviceId: String,
        visible: Boolean,
    ) = Unit

    fun forgetTrustedDevice(deviceId: String) = Unit
}

private val EmptySidebarActivity = kotlinx.coroutines.flow.MutableStateFlow(com.remodex.mobile.core.model.CodexSidebarState())

private val EmptyAccountUsageDetails = kotlinx.coroutines.flow.MutableStateFlow(com.remodex.mobile.core.model.AccountUsageDetails())

private val EmptyPendingQuestions = kotlinx.coroutines.flow.MutableStateFlow<Map<String, PendingStructuredInputRequest>>(emptyMap())

private val EmptyQuestionThreads = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
private val EmptyLoadingHistoryThreads = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

private val EmptyConversationQuestions = kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<com.remodex.mobile.core.model.CodexStructuredUserInputRequest>>>(emptyMap())
