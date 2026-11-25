package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.config.FeatureFlags
import android.util.Log
import androidx.compose.runtime.produceState
import com.remodex.mobile.ui.agent.rememberTimelinePresentation
import com.remodex.mobile.ui.theme.conversationColors
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.remodex.mobile.ui.agent.PREDICTION_FOOTER_KEY
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.AppContainer
import com.remodex.mobile.R
import com.remodex.mobile.BuildConfig
import com.remodex.mobile.core.model.AIChangeSet
import com.remodex.mobile.core.model.projectIconSystemNameFor
import com.remodex.mobile.core.model.CodexAccessMode
import com.remodex.mobile.core.model.CodexCollaborationModeKind
import com.remodex.mobile.core.model.CodexFileAttachment
import com.remodex.mobile.core.model.CodexImageAttachment
import com.remodex.mobile.core.model.CodexMessageKind
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.GitBranchesWithStatusResult
import com.remodex.mobile.core.model.CodexModelOption
import com.remodex.mobile.core.model.CodexPluginMetadata
import com.remodex.mobile.core.model.CodexReviewTarget
import com.remodex.mobile.core.model.CodexServiceTier
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.TurnUsageSheetLogic
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.data.GitBranchDisplayMapper
import com.remodex.mobile.data.GitBranchPickerRules
import com.remodex.mobile.data.TurnWorktreePathRouting
import com.remodex.mobile.data.WorktreeFlowCoordinator
import com.remodex.mobile.data.WorktreeFlowHandoffOutcome
import com.remodex.mobile.data.gitWorkingDirectoryForGitActions
import com.remodex.mobile.data.loadGitBranchesWithStatus
import com.remodex.mobile.services.AiChangeSetRevertService
import com.remodex.mobile.services.CodexService
import com.remodex.mobile.services.GitActionsService
import androidx.compose.runtime.DisposableEffect
import com.remodex.mobile.ui.LocalAIChangeSetPersistence
import com.remodex.mobile.ui.agent.MessageList
import com.remodex.mobile.ui.home.RootReconnectRecoveryAction
import com.remodex.mobile.ui.home.RootReconnectUiState
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import com.remodex.mobile.ui.theme.RemodexPopupChrome
import com.remodex.mobile.services.CodexLookupService
import com.remodex.mobile.services.isPluginListUnsupported
import com.remodex.mobile.ui.theme.isAgentLightChrome
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import com.remodex.mobile.core.model.hasConversationContent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.rememberModalBottomSheetState

private const val TIMELINE_INITIAL_RENDER_TAIL = 48
private const val STARTUP_TRACE_TAG = "RemodexStartup"
private const val BETA_LONG_THREAD_MESSAGE_THRESHOLD = 18
private const val BETA_LONG_THREAD_SCROLL_DISTANCE_ITEMS = 6
private const val DEFAULT_MANUAL_PET_ID = "custom:relay"

private val TurnConversationComposerBottomLift = 8.dp

private data class PetSlashCommand(
    val petId: String?,
    val disable: Boolean,
)

private fun parsePetSlashCommand(text: String): PetSlashCommand? {
    val parts = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.firstOrNull()?.equals("/pet", ignoreCase = true) != true) return null
    val argument = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    if (argument?.equals("off", ignoreCase = true) == true ||
        argument?.equals("hide", ignoreCase = true) == true ||
        argument?.equals("disable", ignoreCase = true) == true
    ) {
        return PetSlashCommand(petId = null, disable = true)
    }
    return PetSlashCommand(petId = argument?.let(::normalizeManualPetId), disable = false)
}

private fun normalizeManualPetId(value: String): String =
    if (value.contains(":")) value else "custom:$value"

/** Shaves a few dp off IME bottom padding so the composer sits slightly closer to the keyboard. */

/**
 * Guscio conversazione: timeline + composer (testo, invio, allegati immagine).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TurnConversationPane(
    threadId: String,
    repository: CodexRepository,
    reconnectUiState: RootReconnectUiState = RootReconnectUiState(),
    onReconnectSavedPairing: () -> Unit = {},
    onWakeSavedComputer: () -> Unit = {},
    onOpenPairingScanner: () -> Unit = {},
    onGitContextChanged: () -> Unit = {},
    modifier: Modifier = Modifier,
    onRecoverDraft: ((deviceId: String, threadId: String) -> Unit)? = null,
) {
    val suggestionFlow = remember(repository, threadId) {
        repository.composerPredictions.map { it[threadId] }.distinctUntilChanged()
    }
    val currentSuggestion by suggestionFlow.collectAsStateWithLifecycle(initialValue = null)
    val ready by repository.isSessionReady.collectAsStateWithLifecycle()
    val availableModels by repository.availableModels.collectAsStateWithLifecycle()
    val isLoadingModels by repository.isLoadingModels.collectAsStateWithLifecycle()
    val selectedModelId by repository.selectedModelId.collectAsStateWithLifecycle()
    val selectedReasoningEffort by repository.selectedReasoningEffort.collectAsStateWithLifecycle()
    val selectedAccessMode by repository.selectedAccessMode.collectAsStateWithLifecycle()
    val selectedServiceTier by repository.selectedServiceTier.collectAsStateWithLifecycle()
    val conversationVisible by rememberUpdatedState(LocalTimelineRenderingEnabled.current)
    val displayVisibility = remember { snapshotFlow { conversationVisible } }
    val displayMessages = remember(repository, threadId, displayVisibility) {
        repository.messagesByThread.threadDisplayFlow(threadId).whileTimelineVisible(displayVisibility)
    }
    // Switching threads behind the drawer must reset the displayed snapshot immediately.
    val messages by key(threadId) {
        displayMessages.collectAsStateWithLifecycle(
            initialValue = repository.messagesByThread.value[threadId].orEmpty(),
        )
    }
    val displayCommandDetails = remember(repository, displayVisibility) {
        repository.commandExecutionDetailsByItemId.sampleTimelineDisplay().whileTimelineVisible(displayVisibility)
    }
    val commandExecutionDetailsByItemId by displayCommandDetails.collectAsStateWithLifecycle(
        initialValue = repository.commandExecutionDetailsByItemId.value,
    )
    val historyPaginationByThread by repository.threadHistoryPaginationByThread.collectAsStateWithLifecycle()
    val loadingOlderHistoryThreadIds by repository.loadingOlderHistoryThreadIds.collectAsStateWithLifecycle()
    val loadingHistoryThreadIds by repository.loadingHistoryThreadIds.collectAsStateWithLifecycle()
    val olderHistoryErrorByThread by repository.olderHistoryErrorByThread.collectAsStateWithLifecycle()
    val threads by repository.threads.collectAsStateWithLifecycle()
    val connectionState by repository.connectionState.collectAsStateWithLifecycle()
    val runningTurnByThread by repository.runningTurnIdByThread.collectAsStateWithLifecycle()
    val protectedRunningFallback by repository.protectedRunningFallbackThreadIds.collectAsStateWithLifecycle()
    val queueErrors by repository.queueErrors.collectAsStateWithLifecycle()
    val queuedDraftDepthByThread by repository.turnDraftQueueDepthByThread.collectAsStateWithLifecycle()
    val queuedDraftPreviewByThread by repository.turnDraftQueuePreviewByThread.collectAsStateWithLifecycle()
    val currentTrustedMacDeviceId by repository.currentTrustedMacDeviceId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val recoveryStore = remember(context) { MissingThreadDraftStore(context) }
    var missingDraft by remember(threadId, currentTrustedMacDeviceId) { mutableStateOf<MissingThreadDraft?>(null) }
    var keepEditing by remember(threadId, currentTrustedMacDeviceId) { mutableStateOf(false) }
    val aiChangeSetPersistence = LocalAIChangeSetPersistence.current
    var sending by remember(threadId) { mutableStateOf(false) }
    var compactBusy by remember(threadId) { mutableStateOf(false) }
    var goalsSupported by remember(repository, currentTrustedMacDeviceId, ready) { mutableStateOf(true) }
    var showGoal by remember(threadId) { mutableStateOf(false) }
    var lastError by remember(threadId) { mutableStateOf<String?>(null) }
    var retryFailedSend by remember(threadId, currentTrustedMacDeviceId) { mutableStateOf<FailedTurnSend?>(null) }
    var draft by remember(threadId, currentTrustedMacDeviceId) { mutableStateOf("") }
    var loadedDraftKey by remember(threadId, currentTrustedMacDeviceId) { mutableStateOf<String?>(null) }
    var pendingPlanModeEnabled by remember(threadId) { mutableStateOf<Boolean?>(null) }
    var hiddenPlanAccessoryMessageIds by rememberSaveable(threadId) { mutableStateOf<List<String>>(emptyList()) }
    var closedPlanAccessoryMessageIds by rememberSaveable(threadId) { mutableStateOf<List<String>>(emptyList()) }
    val attachmentState = remember(threadId, currentTrustedMacDeviceId) { mutableStateOf<List<TurnComposerAttachment>>(emptyList()) }
    var composerAttachments by attachmentState

    var mentionChips by remember(threadId) { mutableStateOf<List<ComposerMentionChipPayload>>(emptyList()) }
    var editFocusId by remember(threadId) { mutableStateOf<String?>(null) }
    var availableSkills by remember(threadId) { mutableStateOf<List<SkillAutocompleteSuggestion>>(emptyList()) }
    var availablePlugins by remember(threadId) { mutableStateOf<List<CodexPluginMetadata>>(emptyList()) }
    var pluginAutocompleteLoading by remember(threadId) { mutableStateOf(false) }
    var cachedPluginSearchIndexByRoot by remember(repository) { mutableStateOf<Map<String, List<CodexPluginMetadata>>>(emptyMap()) }
    var unsupportedPluginAutocompleteRoots by remember(repository) { mutableStateOf<Set<String>>(emptySet()) }
    var availableFileMatches by remember(threadId) { mutableStateOf<List<com.remodex.mobile.core.model.CodexFuzzyFileMatch>>(emptyList()) }
    var showForkThreadSheet by remember(threadId) { mutableStateOf(false) }
    var showFeedbackDialog by remember(threadId) { mutableStateOf(false) }
    var showWorktreeHandoffSheet by remember(threadId) { mutableStateOf(false) }
    var forkingThread by remember(threadId) { mutableStateOf(false) }
    var showPlanDetailsSheet by remember(threadId) { mutableStateOf(false) }
    var planDetailsMessage by remember(threadId) { mutableStateOf<com.remodex.mobile.core.model.CodexMessage?>(null) }
    var fullTimelineMessage by remember(threadId) { mutableStateOf<com.remodex.mobile.core.model.CodexMessage?>(null) }
    var reviewTargetName by rememberSaveable(threadId) { mutableStateOf<String?>(null) }
    var reviewBaseBranch by rememberSaveable(threadId) { mutableStateOf<String?>(null) }
    val lookupService = remember(repository) { CodexLookupService(repository) }
    val threadChangeSets by threadChangeLedger(aiChangeSetPersistence, currentTrustedMacDeviceId, threadId, 50)
    var applyingUndoChangeSetIds by remember(threadId) { mutableStateOf(emptySet<String>()) }
    val previewUndo = rememberRevertPreview(repository, currentTrustedMacDeviceId, threadId)
    var inlineUndoError by remember(threadId) { mutableStateOf<String?>(null) }

    val activeThread =
        remember(threadId, threads) {
            threads.firstOrNull { it.id == threadId }
        }
    val persistedPlanModeEnabled = activeThread?.collaborationMode == CodexCollaborationModeKind.plan
    val isPlanModeEnabled = pendingPlanModeEnabled ?: persistedPlanModeEnabled
    fun setPlanModeEnabled(enabled: Boolean) {
        pendingPlanModeEnabled = enabled
        scope.launch {
            runCatching {
                repository.setThreadCollaborationMode(
                    threadId = threadId,
                    mode = if (enabled) CodexCollaborationModeKind.plan else CodexCollaborationModeKind.default,
                )
            }.onFailure {
                pendingPlanModeEnabled = null
            }
        }
    }
    LaunchedEffect(threadId, persistedPlanModeEnabled) {
        pendingPlanModeEnabled = null
    }
    val gitCwd =
        remember(activeThread) {
            activeThread.gitWorkingDirectoryForGitActions()
        }
    var gitBranchPaneState by remember(threadId) { mutableStateOf<GitBranchPaneState>(GitBranchPaneState.UnavailableNoProject) }
    var gitBranchReloadNonce by remember(threadId) { mutableIntStateOf(0) }
    var isSwitchingGitBranch by remember(threadId) { mutableStateOf(false) }
    var isHandingOffWorktree by remember(threadId) { mutableStateOf(false) }
    var gitBranchCheckoutError by remember(threadId) { mutableStateOf<String?>(null) }
    var worktreeHandoffError by remember(threadId) { mutableStateOf<String?>(null) }

    suspend fun hydrateGitContextAfterMutation(targetThreadId: String = threadId) {
        runCatching { repository.syncThreadHistory(targetThreadId, force = true) }
        gitBranchReloadNonce++
        onGitContextChanged()
    }

    val reviewTarget =
        remember(reviewTargetName) {
            reviewTargetName?.takeIf { FeatureFlags.gitIntegrationEnabled }?.let { raw ->
                runCatching { CodexReviewTarget.valueOf(raw) }.getOrNull()
            }
        }
    val loadedGitBranchSummary =
        (gitBranchPaneState as? GitBranchPaneState.Loaded)?.summary
    val defaultReviewBaseBranch =
        remember(loadedGitBranchSummary) {
            reviewSelectableDefaultBranch(
                defaultBranch = loadedGitBranchSummary?.defaultBranch,
                availableBranches = loadedGitBranchSummary?.branches.orEmpty(),
            )
        }
    val resolvedReviewBaseBranch =
        remember(reviewBaseBranch, defaultReviewBaseBranch, loadedGitBranchSummary) {
            resolveReviewBaseBranch(
                selectedBaseBranch = reviewBaseBranch,
                defaultBranch = defaultReviewBaseBranch,
                availableBranches = loadedGitBranchSummary?.branches.orEmpty(),
            )
        }
    val localWorktreeHandoffTargetPath =
        remember(gitBranchPaneState) {
            val summary = (gitBranchPaneState as? GitBranchPaneState.Loaded)?.summary ?: return@remember null
            val preferredBranch = summary.defaultBranch ?: summary.currentBranch
            preferredBranch
                ?.let { summary.worktreePathByBranch[it]?.trim()?.takeIf { path -> path.isNotEmpty() } }
                ?: summary.worktreePathByBranch.values.firstOrNull { it.isNotBlank() }?.trim()
        }

    val isThreadRunning =
        remember(threadId, runningTurnByThread, protectedRunningFallback) {
            runningTurnByThread.containsKey(threadId) || protectedRunningFallback.contains(threadId)
        }
    val activeTurnId =
        remember(threadId, runningTurnByThread) {
            runningTurnByThread[threadId]
        }
    val queuedDraftCount =
        remember(threadId, queuedDraftDepthByThread) {
            queuedDraftDepthByThread[threadId] ?: 0
        }
    val queuedDraftPreviews =
        remember(threadId, queuedDraftPreviewByThread) {
            queuedDraftPreviewByThread[threadId].orEmpty()
        }
    val hasComposerDraftContent =
        remember(draft, composerAttachments, mentionChips, isPlanModeEnabled, reviewTarget) {
            draft.trim().isNotEmpty() ||
                composerAttachments.isNotEmpty() ||
                mentionChips.isNotEmpty() ||
                isPlanModeEnabled ||
                reviewTarget != null
        }
    val canRestoreQueuedDrafts =
        remember(isThreadRunning, sending, hasComposerDraftContent) {
            !sending && !hasComposerDraftContent
        }
    val branchPickerEnabled =
        remember(
            gitCwd,
            connectionState,
            ready,
            isThreadRunning,
            sending,
            isSwitchingGitBranch,
            isHandingOffWorktree,
            gitBranchPaneState,
        ) {
            gitCwd != null &&
                connectionState is ConnectionState.Connected &&
                ready &&
                !isThreadRunning &&
                !sending &&
                !isSwitchingGitBranch &&
                !isHandingOffWorktree &&
                gitBranchPaneState is GitBranchPaneState.Loaded
        }
    val connectionRecoverySnapshot =
        remember(connectionState, reconnectUiState) {
            TurnConnectionRecoverySnapshotBuilder.makeSnapshot(
                hasReconnectCandidate = true,
                connectionState = connectionState,
                reconnectUiState = reconnectUiState,
            )
        }

    val attachmentOverflowMessage = stringResource(R.string.turn_attachment_overflow, MAX_COMPOSER_ATTACHMENTS)
    val attachmentFileBinarySummary = stringResource(R.string.turn_attachment_file_binary_summary)
    val queuePlanHint = stringResource(R.string.queue_plan_hint)
    val queuedDraftRestoreBlockedMessage = stringResource(R.string.turn_queue_restore_requires_empty)
    val planApplyRequiresEmptyMessage = stringResource(R.string.turn_plan_apply_requires_empty)
    val reviewRunningUnavailableMessage = stringResource(R.string.turn_review_unavailable_running)
    val reviewRequiresEmptyMessage = stringResource(R.string.turn_review_requires_empty)
    val reviewNoDefaultBranchMessage = stringResource(R.string.turn_review_no_default_branch)
    val reviewNoBaseBranchAvailableMessage = stringResource(R.string.turn_review_no_base_branch_available)
    val reviewNoAttachmentsMessage = stringResource(R.string.turn_review_no_attachments)
    val handoffMissingBaseMessage = stringResource(R.string.turn_worktree_handoff_missing_base)
    val handoffMissingLocalMessage = stringResource(R.string.turn_worktree_handoff_missing_local)

    fun reviewDraftText(
        target: CodexReviewTarget,
        baseBranch: String?,
    ): String =
        when (target) {
            CodexReviewTarget.uncommittedChanges -> "Review current changes"
            CodexReviewTarget.baseBranch -> "Review against base branch ${baseBranch.orEmpty()}"
        }

    fun clearReviewTarget() {
        reviewTargetName = null
        reviewBaseBranch = null
    }

    fun selectReviewTarget(
        target: CodexReviewTarget,
        baseBranch: String? = null,
    ) {
        if (
            TurnComposerReviewModeRules.hasComposerContentConflictingWithReview(
                draftText = draft,
                mentionChipCount = mentionChips.size,
                readyAttachmentCount =
                    composerAttachments.count {
                        it.state is TurnComposerAttachmentState.ReadyImage ||
                            it.state is TurnComposerAttachmentState.ReadyFile
                    },
                hasBlockingAttachments =
                    composerAttachments.any {
                        it.state == TurnComposerAttachmentState.Loading || it.state is TurnComposerAttachmentState.Uploading ||
                            it.state is TurnComposerAttachmentState.Failed
                    },
                isPlanModeEnabled = isPlanModeEnabled,
            )
        ) {
            lastError = if (composerAttachments.isNotEmpty()) reviewNoAttachmentsMessage else reviewRequiresEmptyMessage
            return
        }
        reviewTargetName = target.name
        reviewBaseBranch =
            resolveReviewBaseBranch(
                selectedBaseBranch = baseBranch,
                defaultBranch = defaultReviewBaseBranch,
                availableBranches = loadedGitBranchSummary?.branches.orEmpty(),
            )
        draft = reviewDraftText(target, reviewBaseBranch)
        mentionChips = emptyList()
        setPlanModeEnabled(false)
        lastError = null
    }

    fun handoffCurrentThread(selectedBaseBranch: String? = null) {
        val cwd = gitCwd
        if (cwd == null || isThreadRunning || sending || isHandingOffWorktree) return
        val isWorktreeProject = activeThread?.isManagedWorktreeProject == true
        val baseBranch = selectedBaseBranch?.trim()?.takeIf { it.isNotEmpty() } ?: defaultReviewBaseBranch
        val localTargetPath = localWorktreeHandoffTargetPath
        val associatedWorktreePath = repository.associatedManagedWorktreePathFor(threadId)
        if (isWorktreeProject && localTargetPath == null) {
            worktreeHandoffError = handoffMissingLocalMessage
            return
        }
        if (!isWorktreeProject && baseBranch == null && associatedWorktreePath == null) {
            worktreeHandoffError = handoffMissingBaseMessage
            showWorktreeHandoffSheet = true
            return
        }
        scope.launch {
            isHandingOffWorktree = true
            worktreeHandoffError = null
            try {
                showWorktreeHandoffSheet = false
                val outcome =
                    runCatching {
                        val coordinator = WorktreeFlowCoordinator(repository)
                        if (isWorktreeProject) {
                            coordinator.handoffThreadToProjectPath(
                                threadId = threadId,
                                sourceProjectPath = cwd,
                                targetProjectPath = localTargetPath ?: error(handoffMissingLocalMessage),
                            )
                        } else {
                            coordinator.handoffThreadToWorktree(
                                threadId = threadId,
                                sourceProjectPath = cwd,
                                associatedWorktreePath = associatedWorktreePath,
                                baseBranchForNewWorktree = baseBranch,
                            )
                        }
                    }.getOrElse { e ->
                        worktreeHandoffError = GitBranchDisplayMapper.userVisibleMessage(e)
                        return@launch
                    }
                when (outcome) {
                    is WorktreeFlowHandoffOutcome.Moved -> {
                        repository.setActiveThreadId(outcome.move.thread.id)
                        gitBranchReloadNonce++
                    }
                    WorktreeFlowHandoffOutcome.MissingAssociatedWorktree -> {
                        worktreeHandoffError =
                            "The associated worktree is no longer available. Create a new managed worktree to continue."
                        showWorktreeHandoffSheet = true
                    }
                }
            } finally {
                isHandingOffWorktree = false
            }
        }
    }

    fun applyPlanToComposer() {
        if (hasComposerDraftContent) {
            lastError = planApplyRequiresEmptyMessage
        } else {
            draft = "Implement plan."
            setPlanModeEnabled(false)
            mentionChips = emptyList()
        }
    }

    val attachmentActions = key(threadId, currentTrustedMacDeviceId) {
        rememberComposerAttachmentActions(repository, attachmentState,
            blockedReason = reviewNoAttachmentsMessage.takeIf { reviewTarget != null },
            onError = { lastError = it })
    }

    if (ready && loadedDraftKey == "${currentTrustedMacDeviceId.orEmpty()}|$threadId") {
        IncomingDraftShare(repository, currentTrustedMacDeviceId, threadId, activeThread?.displayTitle ?: threadId,
            attachmentActions, enabled = !sending && reviewTarget == null,
            isCurrentTarget = { repository.activeThreadId.value == threadId },
            appendText = { draft = if (draft.isEmpty()) it else draft + "\n" + it })
    }

    if (showGoal) ThreadGoalDialog(repository, threadId, onUnsupported = { goalsSupported = false }) { showGoal = false }

    LaunchedEffect(threadId, ready) {
        if (ready) {
            try { repository.syncThreadHistory(threadId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* The history service retains the cause and exposes retry state. */ }
            if (availableModels.isEmpty()) {
                runCatching { repository.refreshModels() }
            }
        }
    }

    LaunchedEffect(threadId, ready, connectionState) {
        if (ready && connectionState is ConnectionState.Connected && repository.contextWindowUsageByThread.value[threadId] == null) {
            repository.refreshContextWindowUsage(threadId)
        }
    }

    LaunchedEffect(threadId, ready, connectionState, activeThread?.cwd) {
        if (!ready || connectionState !is ConnectionState.Connected) {
            availableSkills = emptyList()
            return@LaunchedEffect
        }
        availableSkills =
            runCatching {
                com.remodex.mobile.ui.turn.loadSkillAutocompleteSuggestions(repository, activeThread?.cwd)
            }.getOrDefault(emptyList())
    }

    fun restoreUnsentDraft(saved: MissingThreadDraft) {
        draft = saved.editableText
        mentionChips = restoreMentionChips(saved.skills, saved.mentions)
        composerAttachments = saved.composerAttachments()
        reviewTargetName = saved.review?.name
        reviewBaseBranch = saved.branch
        if (saved.plan) setPlanModeEnabled(true)
    }

    val sourceDeviceId = currentTrustedMacDeviceId
    suspend fun retainMissingDraft(error: Throwable, saved: MissingThreadDraft) {
        val retained = saved.copy(retentionId = UUID.randomUUID().toString())
        missingDraft = retained
        keepEditing = false
        retryFailedSend = null
        lastError = null
        val device = sourceDeviceId ?: return
        try {
            withContext(Dispatchers.IO) { recoveryStore.save(device, threadId, retained) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (storage: Exception) {
            error.addSuppressed(storage)
            Log.e("RemodexSend", "Missing target draft persistence failed for thread=$threadId", error)
            lastError = storage.message
        }
    }

    LaunchedEffect(threadId, currentTrustedMacDeviceId) {
        val draftKey = "${currentTrustedMacDeviceId.orEmpty()}|$threadId"
        loadedDraftKey = null
        lastError = null
        draft = runCatching { repository.loadComposerDraft(threadId) }.getOrDefault("")
        composerAttachments = emptyList()
        mentionChips = emptyList()
        val device = currentTrustedMacDeviceId
        if (device != null) {
            try {
                val saved = withContext(Dispatchers.IO) { recoveryStore.read(device, threadId) }
                if (saved != null) {
                    val previousText = draft
                    restoreUnsentDraft(saved)
                    if (!saved.transferred && previousText.isNotEmpty()) draft = previousText
                    if (saved.transferred) {
                        repository.saveComposerDraft(threadId, draft)
                        withContext(Dispatchers.IO) { recoveryStore.clearIfUnchanged(device, threadId, saved) }
                    } else {
                        missingDraft = saved
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.e("RemodexSend", "Could not restore retained draft for thread=$threadId", error)
                lastError = error.message
            }
        }
        loadedDraftKey = draftKey
    }

    LaunchedEffect(threadId, currentTrustedMacDeviceId, draft, loadedDraftKey) {
        val draftKey = "${currentTrustedMacDeviceId.orEmpty()}|$threadId"
        if (loadedDraftKey == draftKey) {
            runCatching { repository.saveComposerDraft(threadId, draft) }
        }
    }

    LaunchedEffect(threadId, gitCwd, connectionState, ready, gitBranchReloadNonce) {
        if (!FeatureFlags.gitIntegrationEnabled) return@LaunchedEffect
        when {
            gitCwd == null -> {
                gitBranchPaneState = GitBranchPaneState.UnavailableNoProject
                return@LaunchedEffect
            }
            connectionState !is ConnectionState.Connected -> {
                gitBranchPaneState = GitBranchPaneState.AwaitingBridge
                return@LaunchedEffect
            }
            !ready -> {
                gitBranchPaneState = GitBranchPaneState.Loading
                return@LaunchedEffect
            }
            else -> {
                gitBranchPaneState = GitBranchPaneState.Loading
                val result = loadGitBranchesWithStatus(repository, gitCwd)
                gitBranchPaneState =
                    result.fold(
                        onSuccess = {
                            GitBranchPaneState.Loaded(GitBranchDisplayMapper.summaryFrom(it))
                        },
                        onFailure = {
                            GitBranchPaneState.Failed(GitBranchDisplayMapper.userVisibleMessage(it))
                        },
                    )
            }
        }
    }

    val assistantUndoChangeSetsByMessageId =
        remember(messages, threadChangeSets) {
            assistantUndoChangeSetsByMessageId(messages = messages, changeSets = threadChangeSets)
        }
    var shouldAutoFollowBottom by rememberSaveable(threadId) { mutableStateOf(true) }
    var readingEarlier by remember(threadId) { mutableStateOf(false) }
    var visibleTailCount by rememberSaveable(threadId) { mutableIntStateOf(TIMELINE_INITIAL_RENDER_TAIL) }
    var firstRenderedMessageId by rememberSaveable(threadId) { mutableStateOf<String?>(null) }
    val visibleMessages = remember(messages, visibleTailCount, firstRenderedMessageId, shouldAutoFollowBottom) {
        messages.drop(timelineWindowStartIndex(messages, firstRenderedMessageId, visibleTailCount, shouldAutoFollowBottom))
    }
    androidx.compose.runtime.SideEffect {
        visibleMessages.firstOrNull()?.id?.let { if (firstRenderedMessageId != it) firstRenderedMessageId = it }
    }
    val hiddenEarlierCount = (messages.size - visibleMessages.size).coerceAtLeast(0)
    val historyPaginationState = historyPaginationByThread[threadId]
    val canLoadOlderRemoteHistory = historyPaginationState?.canLoadOlder == true
    val isLoadingOlderHistory = threadId in loadingOlderHistoryThreadIds
    val historyLoadError = olderHistoryErrorByThread[threadId]
    val olderHistoryError = historyLoadError.takeIf { historyPaginationState?.initialTurnsLoaded == true }
    val visiblePlanAccessoryMessage =
        remember(messages, hiddenPlanAccessoryMessageIds, closedPlanAccessoryMessageIds) {
            selectPlanBarMessage(
                messages = messages,
                hiddenMessageIds = hiddenPlanAccessoryMessageIds,
                closedMessageIds = closedPlanAccessoryMessageIds,
            )
        }
    LaunchedEffect(messages) {
        val planBarIds = messages.filter { it.shouldDisplayPlanBarAccessory() }.map { it.id }.toSet()
        hiddenPlanAccessoryMessageIds = hiddenPlanAccessoryMessageIds.filter { it in planBarIds }
        closedPlanAccessoryMessageIds = closedPlanAccessoryMessageIds.filter { it in planBarIds }
    }
    val trailingToken = remember(draft) { TurnComposerTrailingTokens.parseTrailingToken(draft) }
    val fileCandidateMessages = remember(messages, trailingToken?.payload?.kind) {
        if (trailingToken?.payload?.kind == ComposerMentionKind.File) {
            messages.filter { it.kind == CodexMessageKind.fileChange || it.kind == CodexMessageKind.commandExecution }
        } else emptyList()
    }
    val fileAutocompleteCandidates by produceState(emptyList<String>(), fileCandidateMessages) {
        value = withContext(Dispatchers.Default) { extractThreadFileAutocompleteCandidates(fileCandidateMessages) }
    }
    LaunchedEffect(threadId, ready, connectionState, activeThread?.cwd, trailingToken?.payload?.kind, trailingToken?.payload?.semanticValue) {
        val parse = trailingToken
        if (!ready || connectionState !is ConnectionState.Connected || parse?.payload?.kind != ComposerMentionKind.File) {
            availableFileMatches = emptyList()
            return@LaunchedEffect
        }
        val query = parse.payload.semanticValue.trim()
        val cwd = activeThread?.cwd?.trim()?.takeIf { it.isNotEmpty() }
        if (cwd.isNullOrEmpty() || query.isEmpty()) {
            availableFileMatches = emptyList()
            return@LaunchedEffect
        }
        availableFileMatches =
            runCatching {
                lookupService.fuzzyFileSearch(query = query, roots = listOf(cwd))
            }.getOrDefault(emptyList())
    }
    LaunchedEffect(threadId, ready, connectionState, activeThread?.cwd, trailingToken?.payload?.kind, trailingToken?.payload?.semanticValue) {
        val parse = trailingToken
        val shouldLoadPlugins =
            parse?.payload?.kind == ComposerMentionKind.Plugin ||
                (parse?.payload?.kind == ComposerMentionKind.File && isPluginAutocompleteQuery(parse.payload.semanticValue))
        if (!ready || connectionState !is ConnectionState.Connected || !shouldLoadPlugins) {
            availablePlugins = emptyList()
            pluginAutocompleteLoading = false
            return@LaunchedEffect
        }
        val cwd = activeThread?.cwd?.trim()?.takeIf { it.isNotEmpty() }
        if (cwd.isNullOrEmpty()) {
            availablePlugins = emptyList()
            pluginAutocompleteLoading = false
            return@LaunchedEffect
        }
        cachedPluginSearchIndexByRoot[cwd]?.let { cached ->
            availablePlugins = cached
            pluginAutocompleteLoading = false
            return@LaunchedEffect
        }
        if (unsupportedPluginAutocompleteRoots.contains(cwd)) {
            availablePlugins = emptyList()
            pluginAutocompleteLoading = false
            return@LaunchedEffect
        }
        pluginAutocompleteLoading = true
        runCatching {
            lookupService.listPlugins(cwds = listOf(cwd), forceReload = false)
        }.onSuccess { plugins ->
            cachedPluginSearchIndexByRoot = cachedPluginSearchIndexByRoot + (cwd to plugins)
            availablePlugins = plugins
        }.onFailure { error ->
            if (isPluginListUnsupported(error)) {
                unsupportedPluginAutocompleteRoots = unsupportedPluginAutocompleteRoots + cwd
            }
            availablePlugins = emptyList()
        }
        pluginAutocompleteLoading = false
    }
    val autocompleteState =
        remember(
            trailingToken,
            availableSkills,
            availablePlugins,
            pluginAutocompleteLoading,
            fileAutocompleteCandidates,
            goalsSupported,
            availableFileMatches,
            isThreadRunning,
        ) {
            com.remodex.mobile.ui.turn.buildComposerAutocompleteState(
                parse = trailingToken,
                skillSuggestions = availableSkills,
                pluginSuggestions = availablePlugins,
                fileCandidates = fileAutocompleteCandidates,
                fileMatches = availableFileMatches,
                isThreadRunning = isThreadRunning,
                isPluginLoading = pluginAutocompleteLoading,
                goalsSupported = goalsSupported,
            )
        }
    val presentation = rememberTimelinePresentation(threadId, visibleMessages, isThreadRunning, activeTurnId, allMessages = messages)
    val historyRevision = historyPaginationState?.initialLoadRevision ?: 0L
    val activityTimings = com.remodex.mobile.ui.agent.rememberActivityTimings(
        repository, currentTrustedMacDeviceId, threadId, ready, activeTurnId, presentation.messages, historyRevision,
    )
    val receivedHistory = remember(threadId, currentTrustedMacDeviceId, historyRevision) {
        repository.messagesByThread.value[threadId].orEmpty()
    }
    val seekConversation = remember(receivedHistory, historyRevision) {
        historyRevision > 0 && receivedHistory.asReversed().none { it.hasConversationContent() }
    }
    var seekFailure by remember(threadId, currentTrustedMacDeviceId, historyRevision) { mutableStateOf<String?>(null) }
    LaunchedEffect(repository, threadId, currentTrustedMacDeviceId, historyRevision, ready, conversationVisible) {
        if (!ready || !conversationVisible || !seekConversation) return@LaunchedEffect
        seekFailure = null
        try {
            loadInitialConversationContent(
                historyRevision,
                messages = { repository.messagesByThread.value[threadId].orEmpty() },
                pagination = { repository.threadHistoryPaginationByThread.value[threadId] },
                loadPage = {
                    repository.loadOlderThreadHistory(threadId)
                    repository.loadingOlderHistoryThreadIds.first { threadId !in it }
                    repository.olderHistoryErrorByThread.value[threadId]?.let { error(it) }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            seekFailure = repository.olderHistoryErrorByThread.value[threadId] ?: run {
                Log.e("RemodexHistoryUI", "thread=$threadId stage=initial-conversation-content", failure)
                "initial-conversation-content: ${failure.message ?: failure.javaClass.name}"
            }
        }
    }
    val seekingContent = seekConversation && canLoadOlderRemoteHistory && seekFailure == null &&
        presentation.messages.asReversed().none { it.hasConversationContent() }
    val expectedHistoryWindow = remember(receivedHistory, firstRenderedMessageId, visibleTailCount, shouldAutoFollowBottom) {
        receivedHistory.drop(timelineWindowStartIndex(receivedHistory, firstRenderedMessageId, visibleTailCount, shouldAutoFollowBottom))
    }
    var presentedHistoryRevision by remember(threadId, currentTrustedMacDeviceId) { androidx.compose.runtime.mutableLongStateOf(0L) }
    val historyReadyToPresent = remember(presentedHistoryRevision, historyRevision, receivedHistory, expectedHistoryWindow, messages, visibleMessages, presentation.messages) {
        presentedHistoryRevision >= historyRevision || hasPresentedHistoryWindow(
            receivedHistory.lastOrNull(), expectedHistoryWindow, messages, visibleMessages, presentation.messages,
        )
    }
    val reportHistoryPresentation by rememberUpdatedState(LocalHistoryPresentationReceipt.current)
    LaunchedEffect(threadId, historyRevision, historyReadyToPresent, seekingContent) {
        if (historyRevision > 0 && historyReadyToPresent && !seekingContent) {
            androidx.compose.runtime.withFrameNanos { }
            presentedHistoryRevision = historyRevision
            reportHistoryPresentation(HistoryPresentationReceipt(threadId, historyRevision))
        }
    }
    val initialHistoryPhase = historyLoadingPhase(
        connected = ready,
        loading = threadId in loadingHistoryThreadIds || (ready && seekingContent),
        loaded = historyPaginationState?.initialTurnsLoaded == true && seekFailure == null,
        presented = presentedHistoryRevision >= historyRevision,
        error = seekFailure ?: historyLoadError,
    )
    // The header and its offset must describe the published rows, not a page still being prepared.
    val displayedHiddenEarlierCount = remember(messages, presentation.messages, hiddenEarlierCount) {
        presentation.messages.firstOrNull()?.id?.let { first -> messages.indexOfFirst { it.id == first }.coerceAtLeast(0) }
            ?: hiddenEarlierCount
    }
    val listState = rememberLazyListState()
    val isTimelineDragged by listState.interactionSource.collectIsDraggedAsState()
    val followThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val latestMessageId = visibleMessages.lastOrNull()?.id
    var lastAutoScrollThreadId by remember { mutableStateOf<String?>(null) }
    var emittedStreamingResponseMission by rememberSaveable(threadId) { mutableStateOf(false) }
    var emittedCommandCardMission by rememberSaveable(threadId) { mutableStateOf(false) }
    var emittedFileChangeCardMission by rememberSaveable(threadId) { mutableStateOf(false) }
    var emittedLongThreadScrollMission by rememberSaveable(threadId) { mutableStateOf(false) }
    val shouldFollowBottom by remember(listState, followThresholdPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            info.totalItemsCount == 0 || timelineBottomOverflow(
                info.totalItemsCount, last?.index, last?.offset ?: 0, last?.size ?: 0,
                info.viewportEndOffset, info.afterContentPadding,
            )?.let { it <= followThresholdPx } == true
        }
    }
    val hasTimelineItems by remember(listState) { derivedStateOf { listState.layoutInfo.totalItemsCount > 0 } }
    ThreadReadObserver(
        repository = repository,
        threadId = threadId,
        atBottom = shouldFollowBottom && hasTimelineItems,
        contentCurrent = presentation.messages === visibleMessages &&
            messages.lastOrNull() === repository.messagesByThread.value[threadId]?.lastOrNull(),
    )
    val historyLoading = rememberTimelineHistoryLoading(
        threadId, repository, listState, messages, visibleMessages, presentation,
        displayedHiddenEarlierCount, hiddenEarlierCount, canLoadOlderRemoteHistory,
        isLoadingOlderHistory, olderHistoryError, ready,
        historyRevision = historyRevision,
        onReadHistory = { shouldAutoFollowBottom = false },
        onReveal = { firstId, count ->
            // A short initial page can be both at the top and bottom. Revealing history
            // must keep the reader's anchor instead of rearming latest-message follow.
            shouldAutoFollowBottom = false
            firstRenderedMessageId = firstId
            visibleTailCount = count
        },
    )
    val timelineListItemOffset = if (historyLoading.showHeader) 1 else 0
    val readingOlderHistory by rememberUpdatedState(historyLoading.busy)
    val lastTimelineListIndex = presentation.lastListIndex(timelineListItemOffset)
    LaunchedEffect(threadId, lastTimelineListIndex) {
        if (lastTimelineListIndex >= 0 && lastAutoScrollThreadId != threadId) {
            listState.scrollToItem(lastTimelineListIndex)
            lastAutoScrollThreadId = threadId
        }
    }
    var awaitingUserScrollEnd by remember(threadId) { mutableStateOf(false) }
    LaunchedEffect(threadId, listState) {
        snapshotFlow { Triple(!listState.canScrollForward, isTimelineDragged, listState.isScrollInProgress) }
            .distinctUntilChanged()
            .collect { (isAtBottom, isDragging, isScrolling) ->
                if (isDragging) {
                    awaitingUserScrollEnd = true
                    shouldAutoFollowBottom = false
                } else if (awaitingUserScrollEnd && !isScrolling) {
                    awaitingUserScrollEnd = false
                    shouldAutoFollowBottom = isAtBottom && !readingOlderHistory
                }
            }
    }

    LaunchedEffect(threadId, visibleMessages, emittedStreamingResponseMission) {
        if (emittedStreamingResponseMission) return@LaunchedEffect
        val streamingAssistantVisible =
            visibleMessages.any { message ->
                message.role == CodexMessageRole.assistant &&
                    message.kind == CodexMessageKind.chat &&
                    message.isStreaming
            }
        if (!streamingAssistantVisible) return@LaunchedEffect
        emittedStreamingResponseMission = true
        AppContainer.betaEngagementRepository.recordMissionEvent(
            eventType = "streaming_response_seen",
            screen = "conversation",
            refreshAfter = false,
        )
    }
    LaunchedEffect(threadId, visibleMessages, emittedCommandCardMission) {
        if (emittedCommandCardMission) return@LaunchedEffect
        val hasCommandCard =
            visibleMessages.any { message ->
                message.kind == CodexMessageKind.commandExecution
            }
        if (!hasCommandCard) return@LaunchedEffect
        emittedCommandCardMission = true
        AppContainer.betaEngagementRepository.recordMissionEvent(
            eventType = "command_card_checked",
            screen = "conversation",
            refreshAfter = false,
        )
    }
    LaunchedEffect(threadId, visibleMessages, emittedFileChangeCardMission) {
        if (emittedFileChangeCardMission) return@LaunchedEffect
        val hasFileChangeCard =
            visibleMessages.any { message ->
                message.kind == CodexMessageKind.fileChange
            }
        if (!hasFileChangeCard) return@LaunchedEffect
        emittedFileChangeCardMission = true
        AppContainer.betaEngagementRepository.recordMissionEvent(
            eventType = "file_change_card_checked",
            screen = "conversation",
            refreshAfter = false,
        )
    }
    LaunchedEffect(threadId, listState, messages.size, emittedLongThreadScrollMission) {
        if (emittedLongThreadScrollMission || messages.size < BETA_LONG_THREAD_MESSAGE_THRESHOLD) return@LaunchedEffect
        val initialIndex = listState.firstVisibleItemIndex
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { (firstIndex, isScrolling) ->
                if (!isScrolling || emittedLongThreadScrollMission) return@collect
                val scrolledEnough = kotlin.math.abs(firstIndex - initialIndex) >= BETA_LONG_THREAD_SCROLL_DISTANCE_ITEMS
                if (!scrolledEnough) return@collect
                emittedLongThreadScrollMission = true
                AppContainer.betaEngagementRepository.recordMissionEvent(
                    eventType = "scroll_long_thread_checked",
                    screen = "conversation",
                    refreshAfter = false,
                )
            }
    }
    // One owner for automatic following: never realign a visible tall row to its start.
    LaunchedEffect(threadId, listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            timelineBottomScroll(
                following = shouldAutoFollowBottom && !readingEarlier && lastAutoScrollThreadId == threadId,
                dragging = isTimelineDragged,
                scrolling = listState.isScrollInProgress,
                canScrollForward = listState.canScrollForward,
                totalItemsCount = info.totalItemsCount,
                lastVisibleIndex = last?.index,
                lastOffset = last?.offset ?: 0,
                lastSize = last?.size ?: 0,
                viewportEnd = info.viewportEndOffset,
                afterPadding = info.afterContentPadding,
            )
        }.distinctUntilChanged().collect { target ->
            when (target) {
                is TimelineBottomScroll.ByPixels -> listState.scrollBy(target.pixels.toFloat())
                is TimelineBottomScroll.ToItem -> listState.scrollToItem(target.index)
                null -> Unit
            }
        }
    }
    val readyComposerImageAttachments =
        remember(composerAttachments) {
            composerAttachments.mapNotNull { attachment ->
                (attachment.state as? TurnComposerAttachmentState.ReadyImage)?.attachment
            }
        }
    val readyComposerFileAttachments =
        remember(composerAttachments) {
            composerAttachments.mapNotNull { attachment ->
                (attachment.state as? TurnComposerAttachmentState.ReadyFile)?.attachment
            }
        }
    val runtimeLoadingLabel = stringResource(R.string.turn_runtime_loading)
    val runtimeModelFallbackLabel = stringResource(R.string.turn_runtime_model_fallback)
    val runtimeNoModelsLabel = stringResource(R.string.turn_runtime_no_models)
    val runtimeAutoLabel = stringResource(R.string.turn_runtime_auto)
    val runtimeNormalLabel = stringResource(R.string.turn_runtime_normal)
    val reasoningEffortTitles =
        ReasoningEffortTitleStrings(
            low = stringResource(R.string.turn_runtime_reasoning_title_low),
            medium = stringResource(R.string.turn_runtime_reasoning_title_medium),
            high = stringResource(R.string.turn_runtime_reasoning_title_high),
            xhigh = stringResource(R.string.turn_runtime_reasoning_title_xhigh),
        )
    val runtimeControls =
        remember(
            availableModels,
            isLoadingModels,
            selectedModelId,
            selectedReasoningEffort,
            selectedAccessMode,
            selectedServiceTier,
            runtimeLoadingLabel,
            runtimeModelFallbackLabel,
            runtimeNoModelsLabel,
            runtimeAutoLabel,
            runtimeNormalLabel,
            reasoningEffortTitles,
        ) {
            buildRuntimeControlsState(
                models = availableModels,
                isLoadingModels = isLoadingModels,
                selectedModelId = selectedModelId,
                selectedReasoningEffort = selectedReasoningEffort,
                selectedAccessMode = selectedAccessMode,
                selectedServiceTier = selectedServiceTier,
                loadingLabel = runtimeLoadingLabel,
                modelFallbackLabel = runtimeModelFallbackLabel,
                noModelsLabel = runtimeNoModelsLabel,
                autoLabel = runtimeAutoLabel,
                normalTierLabel = runtimeNormalLabel,
                reasoningEffortTitles = reasoningEffortTitles,
            )
        }
    val draftWithMentions =
        remember(draft, mentionChips) {
            com.remodex.mobile.ui.turn.mergeMentionChipsIntoDraft(draft, mentionChips)
        }
    val structuredSkillMentions =
        remember(mentionChips) {
            com.remodex.mobile.ui.turn.mentionChipsToSkillMentions(mentionChips)
        }
    val structuredFileMentions =
        remember(mentionChips) {
            com.remodex.mobile.ui.turn.mentionChipsToFileMentions(mentionChips)
        }
    val canStageOffline = !ready && currentTrustedMacDeviceId != null && reviewTarget == null && missingDraft == null
    val composerModel =
        remember(
            ready, canStageOffline,
            sending,
            draftWithMentions,
            composerAttachments,
            mentionChips,
            isThreadRunning,
        ) {
            listOf<TurnComposerEvent>(
                    TurnComposerEvent.SetEnabled(ready),
                    TurnComposerEvent.SetSending(sending),
                    TurnComposerEvent.SetDraftText(draftWithMentions),
                    TurnComposerEvent.SetReadyAttachmentCount(
                        composerAttachments.count {
                            it.state is TurnComposerAttachmentState.ReadyImage ||
                                it.state is TurnComposerAttachmentState.ReadyFile
                        },
                    ),
                    TurnComposerEvent.SetHasBlockingAttachments(
                        composerAttachments.any {
                            it.state == TurnComposerAttachmentState.Loading || it.state is TurnComposerAttachmentState.Uploading ||
                                it.state is TurnComposerAttachmentState.Failed
                        },
                    ),
                    TurnComposerEvent.SetThreadRunning(isThreadRunning),

                )
                .fold(TurnComposerModel(allowOfflineQueue = canStageOffline)) { state, evt ->
                    TurnComposerReducer.reduce(state, evt)
                }
        }
    val composerLocks = remember(composerModel) { composerModel.deriveInteractionLocks() }
    LaunchedEffect(draft, composerAttachments, mentionChips, isPlanModeEnabled) { retryFailedSend = null }


    fun dispatchTurn(
        text: String,
        attachments: List<com.remodex.mobile.core.model.CodexImageAttachment>,
        skillMentions: List<com.remodex.mobile.core.model.CodexTurnSkillMention>,
        fileMentions: List<com.remodex.mobile.core.model.CodexTurnMention>,
        collaborationMode: CodexCollaborationModeKind?,
        requestId: String = UUID.randomUUID().toString(),
        attempt: Int = 1,
        composerText: String? = null,
        files: List<com.remodex.mobile.core.model.CodexFileAttachment> = emptyList(),
    ) {
        if (sending) return
        retryFailedSend = null
        lastError = null
        if (text.trim().isEmpty() &&
            attachments.isEmpty() &&
            skillMentions.isEmpty() &&
            fileMentions.isEmpty()
        ) return
        if (isThreadRunning || !ready) {
            if (collaborationMode == CodexCollaborationModeKind.plan) {
                lastError = queuePlanHint
                return
            }
            sending = true
            scope.launch {
                runCatching {
                    repository.enqueueTurnDraft(
                        threadId = threadId,
                        text = text,
                        attachments = attachments,
                        skillMentions = skillMentions,
                        fileMentions = fileMentions,
                        collaborationMode = collaborationMode,
                        composerText = composerText,
                        files = files,
                        deviceId = currentTrustedMacDeviceId, requestId = requestId,
                    )
                }.onSuccess {
                    AppContainer.betaEngagementRepository.recordMissionEvent(
                        eventType = "queued_draft_used",
                        screen = "composer",
                    )
                    sending = false
                    draft = ""
                    composerAttachments = emptyList()
                    mentionChips = emptyList()
                    if (collaborationMode == CodexCollaborationModeKind.plan) {
                        setPlanModeEnabled(false)
                    }
                }.onFailure { e ->
                    sending = false
                    if (e is CancellationException) throw e
                    Log.e("RemodexSend", "Message failed stage=enqueue-turn thread=$threadId request=$requestId attempt=$attempt", e)
                    lastError = com.remodex.mobile.ui.turn.formatTurnSendError(e)
                    retryFailedSend = FailedTurnSend(text, attachments, skillMentions, fileMentions, collaborationMode, requestId, attempt, composerText, files)
                }
            }
            return
        }

        sending = true
        scope.launch {
            runCatching {
                repository.startTurn(
                    threadId = threadId,
                    text = text,
                    attachments = attachments,
                    skillMentions = skillMentions,
                    fileMentions = fileMentions,
                    collaborationMode = collaborationMode,
                )
            }
                .onSuccess {
                    AppContainer.betaEngagementRepository.recordMissionEvent(
                        eventType = "main_flow_completed",
                        screen = "conversation",
                    )
                    AppContainer.betaEngagementRepository.recordMissionEvent(
                        eventType = "message_sent",
                        screen = "conversation",
                    )
                    if (collaborationMode == CodexCollaborationModeKind.plan) {
                        AppContainer.betaEngagementRepository.recordMissionEvent(
                            eventType = "plan_mode_used",
                            screen = "composer",
                        )
                    }
                    if (attachments.isNotEmpty()) {
                        AppContainer.betaEngagementRepository.recordMissionEvent(
                            eventType = "image_attachment_sent",
                            screen = "composer",
                        )
                    }
                    if (fileMentions.isNotEmpty()) {
                        AppContainer.betaEngagementRepository.recordMissionEvent(
                            eventType = "file_attachment_sent",
                            screen = "composer",
                        )
                    }
                    sending = false
                    draft = ""
                    composerAttachments = emptyList()
                    mentionChips = emptyList()
                    if (collaborationMode == CodexCollaborationModeKind.plan) setPlanModeEnabled(false)
                }
                .onFailure { e ->
                    sending = false
                    if (e is CancellationException) throw e
                    Log.e("RemodexSend", "Message failed stage=start-turn thread=$threadId request=$requestId attempt=$attempt", e)
                    if (e.isMissingSendTarget()) {
                        val saved = MissingThreadDraft(text, attachments, skillMentions, fileMentions,
                            plan = collaborationMode == CodexCollaborationModeKind.plan,
                            cwd = activeThread?.gitWorkingDirectory, model = activeThread?.model,
                            composerText = composerText, files = files)
                        retainMissingDraft(e, saved)
                        return@onFailure
                    }
                    lastError = com.remodex.mobile.ui.turn.formatTurnSendError(e)
                    retryFailedSend = FailedTurnSend(text, attachments, skillMentions, fileMentions, collaborationMode, requestId, attempt, composerText, files)
                }
        }
    }

    fun stopActiveTurn() {
        scope.launch {
            runCatching {
                repository.interruptTurn(
                    threadId = threadId,
                    turnId = activeTurnId,
                )
            }.onFailure { e ->
                lastError = com.remodex.mobile.ui.turn.formatTurnSendError(e)
            }
        }
    }

    val imeBottomInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    var compactComposer by remember(threadId) { mutableStateOf(false) }
    var composerHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val density = LocalDensity.current
    val keyboardHeightPx by rememberUpdatedState(with(density) { imeBottomInset.toPx() })
    val readingScroll = remember(threadId, listState, density) {
        object : NestedScrollConnection {
            private var distance = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    readingEarlier = available.y > 0f && listState.canScrollBackward
                    if (available.y < 0f && compactComposer &&
                        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key == PREDICTION_FOOTER_KEY) {
                        compactComposer = false
                    }
                    if (readingEarlier && listState.canScrollBackward) shouldAutoFollowBottom = false
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (consumed.y <= 0f || compactComposer) {
                    distance = 0f
                } else {
                    distance += consumed.y
                    // Leave enough room for the viewport to grow without pushing its first line.
                    val threshold = keyboardHeightPx + composerHeightPx - with(density) { 40.dp.toPx() }
                    if (distance > threshold.coerceAtLeast(with(density) { 16.dp.toPx() }) &&
                        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key != PREDICTION_FOOTER_KEY) {
                        compactComposer = true
                        distance = 0f
                    }
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(threadId, compactComposer) {
        if (compactComposer) {
            snapshotFlow { !readingEarlier && !listState.canScrollForward && !listState.isScrollInProgress }.first { it }
            shouldAutoFollowBottom = true
            compactComposer = false
        }
    }
    // The bridge returns only the latest successful turn for each conversation.
    val predictionThreadKey = "${currentTrustedMacDeviceId.orEmpty()}|$threadId"
    val prediction = currentSuggestion?.takeIf { repository.composerPredictions.value[threadId] == it }
    val canFillPrediction = ready && !sending && !hasComposerDraftContent &&
        !composerLocks.textInputLocked && draft.isEmpty() && loadedDraftKey == predictionThreadKey
    val dismissedPredictions = remember(context) { context.getSharedPreferences("composer_prediction_dismissals", android.content.Context.MODE_PRIVATE) }
    var predictionDismissed by remember(predictionThreadKey, prediction?.sourceTurnId) {
        mutableStateOf(prediction != null && dismissedPredictions.getString(predictionThreadKey, null) == prediction.sourceTurnId)
    }
    var predictionDetailsOpen by remember(predictionThreadKey, prediction?.sourceTurnId) { mutableStateOf(false) }
    fun dismissPredictionPreview() {
        if (prediction != null && !predictionDismissed) {
            predictionDismissed = true
            dismissedPredictions.edit().putString(predictionThreadKey, prediction.sourceTurnId).apply()
        }
    }
    val openPrediction = { predictionDetailsOpen = true }
    val fillPrediction = {
        if (prediction != null && canFillPrediction && draft.isEmpty() && repository.composerPredictions.value[threadId] == prediction) {
            dismissPredictionPreview()
            draft = prediction.text
            compactComposer = false
            editFocusId = prediction.sourceTurnId
            predictionDetailsOpen = false
            repository.dismissComposerPrediction(threadId, prediction.sourceTurnId)
        }
    }
    if (predictionDetailsOpen && prediction != null) {
        ComposerPredictionSheet(prediction.text, { predictionDetailsOpen = false }, fillPrediction, canFillPrediction)
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val showPrediction = prediction != null && !prediction.collapsedByTyping && canFillPrediction &&
        !predictionDismissed && conversationVisible && LocalConversationVisible.current &&
        lifecycle.isAtLeast(Lifecycle.State.RESUMED) && presentation.messages.isNotEmpty() && lastAutoScrollThreadId == threadId
    // Animate a new arrival at rest just once. While reading, reveal the footer using list scrolling alone.
    val predictionArrival = if (showPrediction) remember(predictionThreadKey, prediction?.sourceTurnId) {
        MutableTransitionState(readingEarlier || listState.isScrollInProgress || listState.canScrollForward).apply { targetState = true }
    } else null
    val footerInView by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key == PREDICTION_FOOTER_KEY }
    }
    val chatColors = MaterialTheme.colorScheme.conversationColors()
    Column(modifier.fillMaxSize().background(chatColors.reading)) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CompositionLocalProvider(LocalThreadGitProjectRoot provides activeThread?.gitWorkingDirectory) {
                    ConversationImageGalleryHost(threadId, messages) {
                    MessageList(
                        presentation = presentation,
                        activityTimings = activityTimings,
                        showEmptyContent = initialHistoryPhase == HistoryLoadingPhase.Ready,
                        listState = listState,
                        predictionFooter = if (predictionArrival != null && prediction != null) {
                            {
                                androidx.compose.animation.AnimatedVisibility(visibleState = predictionArrival, enter = expandVertically(expandFrom = Alignment.Top)) {
                                    val viewportHeight by remember(listState) {
                                        derivedStateOf { listState.layoutInfo.viewportSize.height }
                                    }
                                    PredictionCard(prediction.text, with(LocalDensity.current) { viewportHeight.toDp() },
                                        openPrediction, fillPrediction, ::dismissPredictionPreview)
                                }
                            }
                        } else null,
                        commandExecutionDetailsByItemId = commandExecutionDetailsByItemId,
                        onOpenFullMessage = { fullTimelineMessage = it },
                        onOpenPlanDetails = {
                            planDetailsMessage = it
                            showPlanDetailsSheet = true
                            lastError = null
                            scope.launch {
                                AppContainer.betaEngagementRepository.recordMissionEvent(
                                    eventType = "plan_rendering_checked",
                                    screen = "conversation",
                                    refreshAfter = false,
                                )
                            }
                        },
                        onApplyPlan = {
                            applyPlanToComposer()
                        },
                        canApplyPlan = !isThreadRunning && !sending,
                        isAssistantTurnActive = isThreadRunning,
                        activeTurnId = activeTurnId,
                        hiddenEarlierCount = displayedHiddenEarlierCount,
                        canLoadEarlierMessages = ready || hiddenEarlierCount > 0,
                        isLoadingOlderHistory = isLoadingOlderHistory || historyLoading.busy,
                        olderHistoryError = olderHistoryError ?: historyLoading.error,
                        contentPadding =
                            PaddingValues(
                                top = 8.dp,
                                bottom = 12.dp,
                                start = 14.dp,
                                end = 14.dp,
                            ),
                        onLoadEarlierMessages = historyLoading.load.takeIf { historyLoading.showHeader },
                        assistantUndoChangeSetsByMessageId = assistantUndoChangeSetsByMessageId,
                    applyingUndoChangeSetIds = applyingUndoChangeSetIds,
                    onUndoAssistantChanges = { changeSet ->
                        val workingDirectory = changeSet.repoRoot ?: activeThread?.cwd
                        if (workingDirectory.isNullOrBlank()) {
                            inlineUndoError = context.getString(R.string.turn_usage_revert_reason_missing_cwd)
                            return@MessageList
                        }
                        previewUndo(changeSet, workingDirectory) {
                        scope.launch {
                            applyingUndoChangeSetIds = applyingUndoChangeSetIds + changeSet.id
                            inlineUndoError = null
                            try {
                                val result = AiChangeSetRevertService(repository).applyTracked(changeSet, workingDirectory,
                                    aiChangeSetPersistence, currentTrustedMacDeviceId)
                                if (!result.success) inlineUndoError = result.unsupportedReasons.firstOrNull()
                                    ?: result.conflicts.firstOrNull()?.message ?: context.getString(R.string.turn_message_action_undo_failed)
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) {
                                android.util.Log.e("RemodexLedger", "apply change set", failure)
                                inlineUndoError = formatTurnSendError(failure)
                            } finally {
                                applyingUndoChangeSetIds = applyingUndoChangeSetIds - changeSet.id
                            }
                        }
                        }
                    },
                    onForkThread = {
                        showForkThreadSheet = true
                        lastError = null
                    },
                    forkThreadEnabled =
                        ready &&
                            connectionState is ConnectionState.Connected &&
                            !isThreadRunning &&
                            !forkingThread,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .nestedScroll(readingScroll)
                            .then(historyLoading.modifier),
                    )
                }
                }
                ConversationHistoryStatus(
                    phase = initialHistoryPhase,
                    error = seekFailure ?: historyLoadError,
                    canRetry = ready && threadId !in loadingHistoryThreadIds,
                    onRetry = {
                        scope.launch {
                            try { repository.syncThreadHistory(threadId, force = true) }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                // Service failures are already recorded with the RPC code and stage.
                                if (repository.olderHistoryErrorByThread.value[threadId].isNullOrBlank()) {
                                    Log.e("RemodexHistoryUI", "operation=retry-initial-history thread=$threadId stage=request attempt=1", failure)
                                }
                            }
                        }
                    },
                    modifier = Modifier.align(if (presentation.items.isEmpty()) Alignment.Center else Alignment.TopCenter)
                        .padding(top = if (presentation.items.isEmpty()) 0.dp else 8.dp, start = 16.dp, end = 16.dp),
                )
                JumpToLatestButton(
                    visible = !footerInView && showJumpToLatest(
                        ready = lastAutoScrollThreadId == threadId,
                        following = shouldAutoFollowBottom,
                        canScrollForward = listState.canScrollForward,
                    ),
                    onClick = {
                        awaitingUserScrollEnd = false
                        readingEarlier = false
                        scope.launch {
                            listState.stopScroll()
                            compactComposer = false
                            shouldAutoFollowBottom = true
                        }
                    },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                )
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = TurnConversationComposerBottomLift),
            ) {
        if (ready && goalsSupported && conversationVisible) key(currentTrustedMacDeviceId) {
            ActiveGoalButton(repository, threadId, open = { showGoal = true }, unsupported = { goalsSupported = false })
        }
        ThreadFollowUpActions(repository, threadId) { lastError = it }
        lastError?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        missingDraft?.takeUnless { keepEditing }?.let { saved ->
            Text(stringResource(R.string.removed_thread_draft_saved), modifier = Modifier.padding(horizontal = 12.dp))
            Row {
                TextButton(enabled = onRecoverDraft != null && !sending && currentTrustedMacDeviceId != null, onClick = {
                    if (sending) return@TextButton
                    val device = currentTrustedMacDeviceId ?: return@TextButton
                    sending = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { recoveryStore.saveIfAbsent(device, threadId, saved) }
                            if (repository.currentTrustedMacDeviceId.value == device &&
                                repository.activeThreadId.value == threadId
                            ) onRecoverDraft?.invoke(device, threadId)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            Log.e("RemodexSend", "Could not open retained draft stage=save-before-navigation thread=$threadId", error)
                            lastError = formatTurnSendError(error)
                        } finally {
                            sending = false
                        }
                    }
                }) { Text(stringResource(R.string.removed_thread_new_chat)) }
                TextButton(enabled = !sending, onClick = { keepEditing = true }) {
                    Text(stringResource(R.string.removed_thread_stay))
                }
            }
        }
        retryFailedSend?.let { retry ->
            TextButton(
                onClick = {
                    dispatchTurn(
                        text = retry.text,
                        attachments = retry.attachments,
                        skillMentions = retry.skillMentions,
                        fileMentions = retry.fileMentions,
                        collaborationMode = retry.collaborationMode,
                        requestId = retry.requestId,
                        attempt = retry.attempt + 1,
                        composerText = retry.composerText,
                        files = retry.files,
                    )
                },
                enabled = ready && !sending,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.message_retry_send))
            }
        }
        gitBranchCheckoutError?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        worktreeHandoffError?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        inlineUndoError?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        connectionRecoverySnapshot?.let { snapshot ->
            TurnConnectionRecoveryCard(
                snapshot = snapshot,
                onTap = {
                    when {
                        reconnectUiState.recoveryAction == RootReconnectRecoveryAction.ScanNewQr -> onOpenPairingScanner()
                        reconnectUiState.wakeDisplayAvailable -> onWakeSavedComputer()
                        else -> onReconnectSavedPairing()
                    }
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (queuedDraftCount > 0 || queueErrors[threadId] != null) {
            fun queueAction(action: suspend () -> Unit) {
                if (sending) return
                sending = true
                scope.launch {
                    try { action() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        Log.e("RemodexQueue", "queue action thread=$threadId", failure)
                        lastError = formatTurnSendError(failure)
                    } finally { sending = false }
                }
            }
            com.remodex.mobile.ui.turn.QueuedDraftsCard(
                previews = queuedDraftPreviews,
                totalCount = queuedDraftCount,
                canRestore = canRestoreQueuedDrafts,
                error = queueErrors[threadId], onRetry = repository::refreshTurnQueue,
                canSteer = ready && activeTurnId != null && !sending, enabled = !sending,
                onSteer = { id -> activeTurnId?.let { turn -> queueAction {
                    repository.steerQueuedTurn(threadId, id, turn, currentTrustedMacDeviceId)
                } } },
                onRestore = { draftId ->
                    queueAction {
                        if (!canRestoreQueuedDrafts) {
                            lastError = queuedDraftRestoreBlockedMessage
                            return@queueAction
                        }
                        val restored = repository.removeQueuedTurnDraft(threadId, draftId, currentTrustedMacDeviceId, forEdit = true)
                        if (restored == null) return@queueAction
                        mentionChips =
                            com.remodex.mobile.ui.turn.restoreMentionChips(
                                skillMentions = restored.skillMentions,
                                fileMentions = restored.fileMentions,
                            )
                        draft = restored.composerText ?: stripMergedMentionPrefix(
                            text = restored.text,
                            skillMentions = restored.skillMentions,
                            fileMentions = restored.fileMentions,
                        )
                        val restoredAttachments =
                            restored.attachments.map { TurnComposerAttachment(state = TurnComposerAttachmentState.ReadyImage(it)) } +
                                restored.files.map { TurnComposerAttachment(state = TurnComposerAttachmentState.ReadyFile(it)) }
                        composerAttachments = restoredAttachments.take(MAX_COMPOSER_ATTACHMENTS)
                        compactComposer = false
                        editFocusId = draftId
                        if (restoredAttachments.size > MAX_COMPOSER_ATTACHMENTS) {
                            lastError = attachmentOverflowMessage
                        }
                    }
                },
                onRemove = { draftId ->
                    queueAction {
                        repository.removeQueuedTurnDraft(threadId, draftId, currentTrustedMacDeviceId)
                    }
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        reviewTarget?.let { target ->
            com.remodex.mobile.ui.turn.TurnReviewAccessoryCard(
                target = target,
                selectedBaseBranch = resolvedReviewBaseBranch,
                availableBranches = loadedGitBranchSummary?.branches.orEmpty(),
                currentBranch = loadedGitBranchSummary?.currentBranch,
                defaultBranch = defaultReviewBaseBranch,
                onSelectCurrentChanges = {
                    selectReviewTarget(CodexReviewTarget.uncommittedChanges)
                },
                onSelectBaseBranch = { branch ->
                    selectReviewTarget(CodexReviewTarget.baseBranch, branch)
                },
                onDismiss = {
                    clearReviewTarget()
                    draft = ""
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        val onGitCheckout: (String) -> Unit =
            checkout@{ selectedBranch ->
            val cwd = gitCwd
            val loaded = gitBranchPaneState as? GitBranchPaneState.Loaded
            if (cwd == null || loaded == null) return@checkout
            val summary = loaded.summary
            val elsewhereSet = summary.branchesCheckedOutElsewhere.toSet()
            val elsewherePathRaw = summary.worktreePathByBranch[selectedBranch]?.trim()
            val selectedElsewhereUnresolved =
                elsewhereSet.contains(selectedBranch) &&
                    elsewherePathRaw.isNullOrEmpty()
            if (selectedElsewhereUnresolved) {
                gitBranchCheckoutError =
                    context.getString(R.string.git_branch_checkout_elsewhere_blocked)
                return@checkout
            }
            if (
                elsewhereSet.contains(selectedBranch) &&
                    !elsewherePathRaw.isNullOrEmpty()
            ) {
                val targetComparable =
                    TurnWorktreePathRouting.comparableGitProjectPath(
                        CodexThread.normalizeProjectPath(elsewherePathRaw) ?: elsewherePathRaw,
                    )
                        ?: return@checkout
                val cwdComparable =
                    TurnWorktreePathRouting.comparableGitProjectPath(activeThread?.cwd ?: cwd)
                if (cwdComparable != null && targetComparable == cwdComparable) {
                    gitBranchCheckoutError = null
                    return@checkout
                }
                val siblingElsewhere =
                    TurnWorktreePathRouting.liveThreadAtProjectPath(
                        elsewherePathRaw,
                        threads,
                        threadId,
                    )
                scope.launch {
                    isSwitchingGitBranch = true
                    gitBranchCheckoutError = null
                    runCatching {
                        val targetNormalized =
                            CodexThread.normalizeProjectPath(elsewherePathRaw) ?: elsewherePathRaw
                        if (siblingElsewhere != null) {
                            repository.setActiveThreadId(siblingElsewhere.id)
                            hydrateGitContextAfterMutation(siblingElsewhere.id)
                        } else {
                            repository.moveThreadToProjectPath(threadId, targetNormalized)
                            hydrateGitContextAfterMutation()
                        }
                    }.onFailure { e ->
                        gitBranchCheckoutError = GitBranchDisplayMapper.userVisibleMessage(e)
                    }
                    isSwitchingGitBranch = false
                }
                return@checkout
            }
            scope.launch {
                isSwitchingGitBranch = true
                gitBranchCheckoutError = null
                runCatching { GitActionsService(repository, cwd).checkout(selectedBranch) }
                    .onSuccess { hydrateGitContextAfterMutation() }
                    .onFailure { e ->
                        gitBranchCheckoutError = GitBranchDisplayMapper.userVisibleMessage(e)
                    }
                isSwitchingGitBranch = false
            }
        }
        val onGitCreateBranch: (String) -> Unit =
            createBranch@{ branchName ->
                val cwd = gitCwd ?: return@createBranch
                val loaded = gitBranchPaneState as? GitBranchPaneState.Loaded ?: return@createBranch
                if (branchName.isBlank()) return@createBranch
                scope.launch {
                    isSwitchingGitBranch = true
                    gitBranchCheckoutError = null
                    runCatching {
                        GitActionsService(repository, cwd).createBranch(branchName.trim())
                    }.onSuccess {
                        val createdBranch = it.branch.trim()
                        val previous = loaded.summary
                        val nextSummary =
                            GitBranchDisplayMapper.summaryFrom(
                                GitBranchesWithStatusResult(
                                    branches = (previous.branches + createdBranch).filter { branch -> branch.isNotBlank() }.distinct(),
                                    branchesCheckedOutElsewhere = previous.branchesCheckedOutElsewhere.toSet(),
                                    worktreePathByBranch = previous.worktreePathByBranch,
                                    localCheckoutPath = null,
                                    currentBranch = createdBranch.ifEmpty { previous.currentBranch },
                                    defaultBranch = previous.defaultBranch,
                                    status = it.status,
                                ),
                            )
                        gitBranchPaneState = GitBranchPaneState.Loaded(nextSummary)
                        hydrateGitContextAfterMutation()
                    }.onFailure { e ->
                        gitBranchCheckoutError = GitBranchDisplayMapper.userVisibleMessage(e)
                    }
                    isSwitchingGitBranch = false
                }
            }
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                var changingAccess by remember(threadId) { mutableStateOf(false) }
                var accessError by remember(threadId) { mutableStateOf<String?>(null) }
                BindSessionEnvironment(threadId) {
                    val states by repository.threadExecutionStates.collectAsStateWithLifecycle()
                    val connected = ready && connectionState is ConnectionState.Connected
                    var readError by remember(threadId, connected) { mutableStateOf<String?>(null) }
                    LaunchedEffect(repository, threadId, connected) {
                        if (connected) reportSettingFailure({ readError = it }, "thread/read execution settings") { repository.refreshThreadExecution(threadId) }
                    }
                    val execution = states[threadId].takeIf { connected }
                    val directory = execution?.primaryEnvironmentDirectory ?: execution?.cwd ?: activeThread?.cwd
                    SessionEnvironmentSections(
                        directory = directory,
                        execution = execution,
                        readError = readError,
                        threadId = threadId,
                        isWorktreeProject = projectIconSystemNameFor(directory) == "arrow.triangle.branch",
                        worktreeHandoffEnabled =
                            gitCwd != null &&
                                gitBranchPaneState is GitBranchPaneState.Loaded &&
                                ready &&
                                connectionState is ConnectionState.Connected &&
                                !isThreadRunning &&
                                !sending &&
                                !isSwitchingGitBranch,
                        isHandingOffWorktree = isHandingOffWorktree,
                        onWorktreeHandoff = {
                            showWorktreeHandoffSheet = true
                            worktreeHandoffError = null
                        },
                        accessError = accessError,
                        accessPickerEnabled =
                            ready && !changingAccess &&
                                !composerLocks.runtimeControlsLocked &&
                                runtimeControls.accessMode.enabled,
                        onSelectAccessMode = select@{ mode ->
                            if (changingAccess) return@select
                            changingAccess = true
                            accessError = null
                            scope.launch {
                                try {
                                    repository.setThreadAccessMode(threadId, mode)
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    android.util.Log.e("RemodexAccess", "stage=set_access_mode", failure)
                                    accessError = failure.localizedMessage ?: failure.javaClass.name
                                } finally {
                                    changingAccess = false
                                }
                            }
                        },
                        gitBranchPaneState = gitBranchPaneState,
                        branchPickerEnabled = branchPickerEnabled,
                        isSwitchingGitBranch = isSwitchingGitBranch,
                        onRefreshGitBranches = { gitBranchReloadNonce++ },
                        onCheckoutGitBranch = onGitCheckout,
                        onCreateGitBranch = onGitCreateBranch,
                        onOpenBranchSelector = {
                            scope.launch {
                                AppContainer.betaEngagementRepository.recordMissionEvent(
                                    eventType = "branch_selector_opened",
                                    screen = "branch_selector",
                                )
                            }
                        },
                    )
                }
                TurnComposerBar(
                    compact = compactComposer,
                    editFocusId = editFocusId,
                    onEditFocused = { editFocusId = null },
                    isFollowUp = messages.isNotEmpty(),
                    onExpand = { compactComposer = false },
                    draft = draft,
                    onOpenPrediction = openPrediction.takeIf { prediction != null },
                    attachments = composerAttachments,
                    model = composerModel,
                    isPlanModeEnabled = isPlanModeEnabled,
                    runtimeControls = runtimeControls,
                    mentionChips = mentionChips,
                    autocomplete = autocompleteState,
                    modifier = Modifier.fillMaxWidth().onSizeChanged { composerHeightPx = it.height },
            onDraftChange = { next ->
                if (draft.isEmpty() && next.isNotEmpty()) {
                    dismissPredictionPreview()
                    repository.dismissPredictionForTyping(threadId)
                }
                draft = next
                val target = reviewTarget
                if (target != null && next.trim() != reviewDraftText(target, reviewBaseBranch)) {
                    clearReviewTarget()
                }
            },
            onPickImages = attachmentActions.pickImages,
            onPickFiles = attachmentActions.pickFiles,
            onTakePhoto = attachmentActions.takePhoto,
            onSetPlanModeEnabled = { setPlanModeEnabled(it) },
            onOpenModelPicker = {
                if (ready && availableModels.isEmpty() && !isLoadingModels) scope.launch {
                    try { repository.refreshModels() }
                    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (failure: Exception) { lastError = failure.localizedMessage }
                }
            },
            onSelectModel = { option ->
                repository.setSelectedModelId(option.id)
            },
            onSelectReasoningEffort = { option ->
                repository.setSelectedReasoningEffort(option.id.takeUnless { it == TURN_COMPOSER_RUNTIME_AUTO_ID })
            },
            onSelectAccessMode = { option ->
                CodexAccessMode.entries.firstOrNull { it.name == option.id }?.let { mode ->
                    scope.launch { reportSettingFailure({ lastError = it }) { repository.setThreadAccessMode(threadId, mode) } }
                }
            },
            onSelectServiceTier = { option ->
                val tier = CodexServiceTier.entries.firstOrNull { it.name == option.id }
                repository.setSelectedServiceTier(tier)
            },
            onRetryAttachment = attachmentActions.retry,
            onRemoveAttachment = attachmentActions.remove,
            onRemoveMentionChip = { chip ->
                mentionChips = mentionChips.filterNot { it == chip }
            },
            onSelectAutocomplete = { item ->
                val originalDraft = draft
                val replaced =
                    TurnComposerTrailingTokens.replaceTrailingSegment(
                        text = draft,
                        replacement = item.replacementText,
                        parse = trailingToken,
                    )
                draft = replaced.text
                when (item.payload.kind) {
                    ComposerMentionKind.File,
                    ComposerMentionKind.Skill,
                    ComposerMentionKind.Plugin,
                    -> {
                        if (mentionChips.none { it.kind == item.payload.kind && it.semanticValue == item.payload.semanticValue }) {
                            mentionChips = mentionChips + item.payload
                        }
                    }
                    ComposerMentionKind.SlashCommand -> {
                        when {
                            item.payload.semanticValue.equals("fork", ignoreCase = true) -> {
                                draft = replaced.text.removeSuffix("/fork ").trimEnd()
                                showForkThreadSheet = true
                                lastError = null
                            }
                            item.payload.semanticValue.equals("feedback", ignoreCase = true) -> {
                                draft = replaced.text.removeSuffix("/feedback ").trimEnd()
                                showFeedbackDialog = true
                                lastError = null
                            }
                            item.payload.semanticValue.equals("goal", ignoreCase = true) -> {
                                draft = TurnComposerTrailingTokens.replaceTrailingSegment(originalDraft, parse = trailingToken).text
                                showGoal = true
                            }
                            item.payload.semanticValue.equals("compact", ignoreCase = true) -> {
                                draft = TurnComposerTrailingTokens.replaceTrailingSegment(originalDraft, parse = trailingToken).text
                                if (!compactBusy && ready) {
                                    compactBusy = true
                                    scope.launch {
                                        try {
                                            repository.sendRequest("thread/compact/start", JSONValue.Obj(mapOf("threadId" to JSONValue.Str(threadId))))
                                        } catch (cancel: CancellationException) { throw cancel }
                                        catch (failure: Exception) {
                                            android.util.Log.e("RemodexCompact", "thread/compact/start", failure)
                                            lastError = formatTurnSendError(failure)
                                        } finally { compactBusy = false }
                                    }
                                }
                                mentionChips = mentionChips.filterNot { chip ->
                                    chip.kind == ComposerMentionKind.SlashCommand &&
                                        chip.semanticValue.equals("compact", ignoreCase = true)
                                }
                                lastError = null
                            }
                            item.payload.semanticValue.equals("review", ignoreCase = true) -> {
                                selectReviewTarget(CodexReviewTarget.uncommittedChanges)
                            }
                            item.payload.semanticValue.equals("review-base", ignoreCase = true) -> {
                                val branch = defaultReviewBaseBranch
                                if (branch == null) {
                                    draft = replaced.text.removeSuffix("/review-base ").trimEnd()
                                    lastError = reviewNoDefaultBranchMessage
                                } else {
                                    selectReviewTarget(CodexReviewTarget.baseBranch, branch)
                                }
                            }
                        }
                    }
                }
            },
            onStopTurn = { stopActiveTurn() },
            onSend = {
                lastError = null
                val petSlashCommand = parsePetSlashCommand(draft)
                if (petSlashCommand != null) {
                    val petStore = AppContainer.petCompanionStore
                    if (petSlashCommand.disable) {
                        petStore.setEnabled(false)
                    } else {
                        val targetPetId =
                            petSlashCommand.petId
                                ?: petStore.selectedPetId.value
                                ?: petStore.availablePets.value.firstOrNull()?.id
                                ?: DEFAULT_MANUAL_PET_ID
                        petStore.selectPet(targetPetId)
                        petStore.restoreCachedPet(targetPetId)
                        petStore.setEnabled(true)
                        if (ready && connectionState is ConnectionState.Connected) {
                            (repository as? CodexService)?.let { codexService ->
                                scope.launch {
                                    runCatching {
                                        petStore.loadPetsIfNeeded(codexService)
                                        petStore.loadSelectedPet(codexService)
                                    }.onFailure { error ->
                                        petStore.setErrorMessage("Could not load pet: ${error.message ?: "unknown error"}")
                                    }
                                }
                            }
                        }
                    }
                    petStore.setErrorMessage(null)
                    draft = ""
                    mentionChips = emptyList()
                    return@TurnComposerBar
                }
                val activeReviewTarget = reviewTarget
                if (activeReviewTarget != null) {
                    if (isThreadRunning) {
                        lastError = reviewRunningUnavailableMessage
                        return@TurnComposerBar
                    }
                    val activeReviewBaseBranch = resolvedReviewBaseBranch
                    if (activeReviewTarget.name == "baseBranch" && activeReviewBaseBranch == null) {
                        lastError = reviewNoBaseBranchAvailableMessage
                        return@TurnComposerBar
                    }
                    if (composerAttachments.isNotEmpty()) {
                        lastError = reviewNoAttachmentsMessage
                        return@TurnComposerBar
                    }
                    sending = true
                    scope.launch {
                        runCatching {
                            repository.startReview(
                                threadId = threadId,
                                target = activeReviewTarget,
                                baseBranch = activeReviewBaseBranch,
                            )
                        }.onSuccess {
                            AppContainer.betaEngagementRepository.recordMissionEvent(
                                eventType = "review_flow_started",
                                screen = "review",
                            )
                            sending = false
                            draft = ""
                            mentionChips = emptyList()
                            clearReviewTarget()
                        }.onFailure { e ->
                            sending = false
                            if (e is CancellationException) throw e
                            if (e.isMissingSendTarget()) {
                                Log.e("RemodexSend", "Missing review target thread=$threadId", e)
                                retainMissingDraft(e, MissingThreadDraft(draft, review = activeReviewTarget, branch = activeReviewBaseBranch,
                                    cwd = activeThread?.gitWorkingDirectory, model = activeThread?.model))
                                return@onFailure
                            }
                            lastError = com.remodex.mobile.ui.turn.formatTurnSendError(e)
                        }
                    }
                    return@TurnComposerBar
                }
                val draftText =
                    com.remodex.mobile.ui.turn.appendFileAttachmentsToDraft(
                        baseText = draftWithMentions,
                        files = readyComposerFileAttachments,
                        binarySummary = attachmentFileBinarySummary,
                    )
                val attachmentsToSend = readyComposerImageAttachments
                val collaborationMode =
                    if (isPlanModeEnabled) {
                        CodexCollaborationModeKind.plan
                    } else {
                        null
                    }
                dispatchTurn(
                    text = draftText,
                    attachments = attachmentsToSend,
                    skillMentions = structuredSkillMentions,
                    fileMentions = structuredFileMentions,
                    collaborationMode = collaborationMode,
                    composerText = draft,
                    files = readyComposerFileAttachments,
                )
            },
                )
            }
        }
    }
    com.remodex.mobile.ui.turn.ForkThreadActionSheet(
        visible = showForkThreadSheet,
        projectPath = activeThread?.cwd,
        inProgress = forkingThread,
        onDismiss = {
            if (!forkingThread) showForkThreadSheet = false
        },
        onConfirm = {
            scope.launch {
                forkingThread = true
                runCatching {
                    val forked = repository.forkThread(threadId, targetProjectPath = activeThread?.cwd)
                    repository.setActiveThreadId(forked.id)
                    showForkThreadSheet = false
                    lastError = null
                }.onFailure { e ->
                    lastError = com.remodex.mobile.ui.turn.formatTurnSendError(e)
                }
                forkingThread = false
            }
        },
    )
    if (showFeedbackDialog) {
        com.remodex.mobile.ui.turn.TurnFeedbackDialog(
            onDismiss = { showFeedbackDialog = false },
            onSubmit = {
                showFeedbackDialog = false
            },
        )
    }
    com.remodex.mobile.ui.turn.WorktreeHandoffActionSheet(
        visible = showWorktreeHandoffSheet,
        isWorktreeProject = activeThread?.isManagedWorktreeProject == true,
        inProgress = isHandingOffWorktree,
        availableBaseBranches = loadedGitBranchSummary?.branches.orEmpty(),
        defaultBaseBranch = defaultReviewBaseBranch,
        currentBranch = loadedGitBranchSummary?.currentBranch,
        sourceProjectPath = gitCwd,
        localTargetPath = localWorktreeHandoffTargetPath,
        associatedWorktreePath = repository.associatedManagedWorktreePathFor(threadId),
        hasAssociatedWorktree = repository.associatedManagedWorktreePathFor(threadId) != null,
        errorMessage = worktreeHandoffError,
        onDismiss = {
            if (!isHandingOffWorktree) showWorktreeHandoffSheet = false
        },
        onConfirm = { selectedBaseBranch ->
            handoffCurrentThread(selectedBaseBranch)
        },
    )
    com.remodex.mobile.ui.turn.PlanDetailsActionSheet(
        visible = showPlanDetailsSheet,
        message = planDetailsMessage ?: visiblePlanAccessoryMessage,
        canApplyPlan = !isThreadRunning && !sending,
        onDismiss = {
            showPlanDetailsSheet = false
            planDetailsMessage = null
        },
        onApplyPlan = {
            applyPlanToComposer()
            if (!hasComposerDraftContent) {
                showPlanDetailsSheet = false
                planDetailsMessage = null
            }
        },
    )
    FullTimelineMessageSheet(
        message = fullTimelineMessage,
        onDismiss = { fullTimelineMessage = null },
    )
}
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FullTimelineMessageSheet(
    message: com.remodex.mobile.core.model.CodexMessage?,
    onDismiss: () -> Unit,
) {
    if (message == null) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    RemodexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.turn_message_full_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TurnRichMarkdownBody(
                markdown = message.visibleAssistantText().trim(),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                keyPrefix = "full-${message.id}",
                assistantProse = message.role == CodexMessageRole.assistant && message.kind == CodexMessageKind.chat,
            )
        }
    }
}

private fun assistantUndoChangeSetsByMessageId(
    messages: List<com.remodex.mobile.core.model.CodexMessage>,
    changeSets: List<AIChangeSet>,
): Map<String, AIChangeSet> {
    if (messages.isEmpty() || changeSets.isEmpty()) return emptyMap()
    val readyChangeSets =
        changeSets.filter { TurnUsageSheetLogic.revertPrimaryEnabled(it, runtimeRevertRpcAvailable = true) }
    val byAssistantMessageId =
        readyChangeSets
            .mapNotNull { changeSet ->
                changeSet.assistantMessageId
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { it to changeSet }
            }
            .toMap()
    val byTurnId = readyChangeSets.associateBy { it.turnId }
    val mapped =
        messages
        .asSequence()
        .filter { it.role == com.remodex.mobile.core.model.CodexMessageRole.assistant }
        .mapNotNull { message ->
            val changeSet =
                byAssistantMessageId[message.id]
                    ?: message.itemId?.let { byAssistantMessageId[it] }
                    ?: message.turnId?.let { byTurnId[it] }
            changeSet?.let { message.id to it }
        }
        .toMap()
    if (mapped.isNotEmpty()) return mapped
    val latestAssistant = messages.lastOrNull { it.role == com.remodex.mobile.core.model.CodexMessageRole.assistant }
    val latestReady = readyChangeSets.maxByOrNull { it.createdAt }
    return if (latestAssistant != null && latestReady != null) {
        mapOf(latestAssistant.id to latestReady)
    } else {
        emptyMap()
    }
}
