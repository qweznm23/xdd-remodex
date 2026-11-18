package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.config.FeatureFlags
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.runtime.produceState
import com.remodex.mobile.ui.agent.rememberTimelinePresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.AppContainer
import com.remodex.mobile.R
import com.remodex.mobile.BuildConfig
import com.remodex.mobile.core.model.AIChangeSet
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
import com.remodex.mobile.data.QueuedTurnDraftPreview
import com.remodex.mobile.data.TurnWorktreePathRouting
import com.remodex.mobile.data.WorktreeFlowCoordinator
import com.remodex.mobile.data.WorktreeFlowHandoffOutcome
import com.remodex.mobile.data.gitWorkingDirectoryForGitActions
import com.remodex.mobile.data.loadGitBranchesWithStatus
import com.remodex.mobile.services.AiChangeSetRevertService
import com.remodex.mobile.services.CodexService
import com.remodex.mobile.services.GitActionsService
import com.remodex.mobile.data.TurnAttachmentCodec
import com.remodex.mobile.services.WorkspaceFileUploadService
import com.remodex.mobile.services.MAX_FILE_UPLOAD_BYTES
import androidx.compose.runtime.DisposableEffect
import com.remodex.mobile.data.TurnFileAttachmentCodec
import com.remodex.mobile.ui.LocalAIChangeSetPersistence
import com.remodex.mobile.ui.agent.MessageList
import com.remodex.mobile.ui.home.RootReconnectRecoveryAction
import com.remodex.mobile.ui.home.RootReconnectUiState
import com.remodex.mobile.ui.theme.AgentLightColors
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import com.remodex.mobile.ui.theme.RemodexPopupChrome
import com.remodex.mobile.services.CodexLookupService
import com.remodex.mobile.services.isPluginListUnsupported
import com.remodex.mobile.ui.theme.isAgentLightChrome
import com.remodex.mobile.core.voice.BridgeVoiceRecorder
import com.remodex.mobile.core.voice.VoiceDraftAppend
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.rememberModalBottomSheetState

private const val MAX_COMPOSER_ATTACHMENTS = 4
private const val TIMELINE_INITIAL_RENDER_TAIL = 48
private const val STARTUP_TRACE_TAG = "RemodexStartup"
private const val SMART_SCROLL_FADE_JUMP_DISTANCE_ITEMS = 24
private const val BETA_LONG_THREAD_MESSAGE_THRESHOLD = 18
private const val BETA_LONG_THREAD_SCROLL_DISTANCE_ITEMS = 6
private const val DEFAULT_MANUAL_PET_ID = "custom:relay"
private val TurnConversationMessageListTopPadding = 84.dp
private val TurnConversationMessageListBottomPadding = 200.dp
private val TurnConversationSmartScrollBottomPadding = 172.dp
private val TurnConversationComposerBottomLift = 20.dp

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

private data class PendingSteerDraft(
    val id: String = UUID.randomUUID().toString(),
    val draftText: String,
    val wireText: String,
    val attachments: List<CodexImageAttachment>,
    val skillMentions: List<com.remodex.mobile.core.model.CodexTurnSkillMention>,
    val fileMentions: List<com.remodex.mobile.core.model.CodexTurnMention>,
    val mentionChips: List<ComposerMentionChipPayload>,
    val composerAttachments: List<TurnComposerAttachment>,
)
/** Shaves a few dp off IME bottom padding so the composer sits slightly closer to the keyboard. */

/**
 * Guscio conversazione: timeline + composer (testo, invio, allegati immagine).
 */
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
    val hasResolvedRateLimits by repository.hasResolvedRateLimitsSnapshot.collectAsStateWithLifecycle()
    val isLoadingRateLimits by repository.isLoadingRateLimits.collectAsStateWithLifecycle()
    val rateLimitsError by repository.rateLimitsErrorMessage.collectAsStateWithLifecycle()
    val runningTurnByThread by repository.runningTurnIdByThread.collectAsStateWithLifecycle()
    val protectedRunningFallback by repository.protectedRunningFallbackThreadIds.collectAsStateWithLifecycle()
    val queuedDraftDepthByThread by repository.turnDraftQueueDepthByThread.collectAsStateWithLifecycle()
    val queuedDraftPreviewByThread by repository.turnDraftQueuePreviewByThread.collectAsStateWithLifecycle()
    val currentTrustedMacDeviceId by repository.currentTrustedMacDeviceId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val recoveryStore = remember(context) { MissingThreadDraftStore(java.io.File(context.noBackupFilesDir, "unsent-turns"), AppContainer.messagePersistence::messageHistoryKey) }
    var missingDraft by remember(threadId, currentTrustedMacDeviceId) { mutableStateOf<MissingThreadDraft?>(null) }
    var keepEditing by remember(threadId, currentTrustedMacDeviceId) { mutableStateOf(false) }
    val aiChangeSetPersistence = LocalAIChangeSetPersistence.current
    var sending by remember(threadId) { mutableStateOf(false) }
    var lastError by remember(threadId) { mutableStateOf<String?>(null) }
    var retryFailedSend by remember(threadId, currentTrustedMacDeviceId) { mutableStateOf<FailedTurnSend?>(null) }
    var draft by remember(threadId, currentTrustedMacDeviceId) { mutableStateOf("") }
    var loadedDraftKey by remember(threadId, currentTrustedMacDeviceId) { mutableStateOf<String?>(null) }
    var pendingPlanModeEnabled by remember(threadId) { mutableStateOf<Boolean?>(null) }
    var hiddenPlanAccessoryMessageIds by rememberSaveable(threadId) { mutableStateOf<List<String>>(emptyList()) }
    var closedPlanAccessoryMessageIds by rememberSaveable(threadId) { mutableStateOf<List<String>>(emptyList()) }
    var composerAttachments by remember(threadId) { mutableStateOf<List<TurnComposerAttachment>>(emptyList()) }
    val fileUploadService = remember(repository) { WorkspaceFileUploadService(repository) }
    val attachmentUploads = remember(threadId, currentTrustedMacDeviceId) {
        AttachmentUploadRetryController(
            scope,
            onState = { id, state ->
                composerAttachments = composerAttachments.map { if (it.id == id) it.copy(state = state) else it }
            },
            onFailure = { id, attempt, failure ->
                val detail = (failure as? com.remodex.mobile.services.FileTransferFailure)?.let { "stage=${it.stage} code=${it.code}" }
                    ?: "stage=read-or-upload category=unknown"
                Log.e("RemodexUpload", "Attachment failed $detail attachment=$id attempt=$attempt", failure)
                lastError = failure.message ?: failure.javaClass.name
            },
        )
    }
    DisposableEffect(attachmentUploads) { onDispose { attachmentUploads.clear() } }
    LaunchedEffect(composerAttachments.map { it.id }) {
        attachmentUploads.retain(composerAttachments.mapTo(hashSetOf()) { it.id })
    }

    var mentionChips by remember(threadId) { mutableStateOf<List<ComposerMentionChipPayload>>(emptyList()) }
    var pendingSteerDrafts by remember(threadId) { mutableStateOf<List<PendingSteerDraft>>(emptyList()) }
    var editingSteerDraftId by remember(threadId) { mutableStateOf<String?>(null) }
    var availableSkills by remember(threadId) { mutableStateOf<List<SkillAutocompleteSuggestion>>(emptyList()) }
    var availablePlugins by remember(threadId) { mutableStateOf<List<CodexPluginMetadata>>(emptyList()) }
    var pluginAutocompleteLoading by remember(threadId) { mutableStateOf(false) }
    var cachedPluginSearchIndexByRoot by remember(repository) { mutableStateOf<Map<String, List<CodexPluginMetadata>>>(emptyMap()) }
    var unsupportedPluginAutocompleteRoots by remember(repository) { mutableStateOf<Set<String>>(emptySet()) }
    var availableFileMatches by remember(threadId) { mutableStateOf<List<com.remodex.mobile.core.model.CodexFuzzyFileMatch>>(emptyList()) }
    var voicePhase by remember(threadId) { mutableStateOf(TurnVoicePhase.Idle) }
    var voiceAudioLevels by remember(threadId) { mutableStateOf<List<Float>>(emptyList()) }
    var voiceRecordingDurationSeconds by remember(threadId) { mutableStateOf(0.0) }
    var showForkThreadSheet by remember(threadId) { mutableStateOf(false) }
    var showFeedbackDialog by remember(threadId) { mutableStateOf(false) }
    var showWorktreeHandoffSheet by remember(threadId) { mutableStateOf(false) }
    var forkingThread by remember(threadId) { mutableStateOf(false) }
    var showPlanDetailsSheet by remember(threadId) { mutableStateOf(false) }
    var planDetailsMessage by remember(threadId) { mutableStateOf<com.remodex.mobile.core.model.CodexMessage?>(null) }
    var fullTimelineMessage by remember(threadId) { mutableStateOf<com.remodex.mobile.core.model.CodexMessage?>(null) }
    var reviewTargetName by rememberSaveable(threadId) { mutableStateOf<String?>(null) }
    var reviewBaseBranch by rememberSaveable(threadId) { mutableStateOf<String?>(null) }
    val voiceRecorder = remember(threadId) { BridgeVoiceRecorder() }
    val lookupService = remember(repository) { CodexLookupService(repository) }
    var transcribeJob by remember(threadId) { mutableStateOf<Job?>(null) }
    var threadChangeSets by remember(threadId, currentTrustedMacDeviceId) {
        mutableStateOf(
            TurnUsageSheetLogic.recentChangeSetsForThread(
                threadId,
                aiChangeSetPersistence.load(currentTrustedMacDeviceId),
                limit = 50,
            ),
        )
    }
    var applyingUndoChangeSetIds by remember(threadId) { mutableStateOf(emptySet<String>()) }
    var inlineUndoError by remember(threadId) { mutableStateOf<String?>(null) }

    fun appendVoiceAudioLevel(level: Float) {
        scope.launch {
            if (voicePhase != TurnVoicePhase.Recording) return@launch
            voiceAudioLevels = (voiceAudioLevels + level.coerceIn(0f, 1f)).takeLast(240)
        }
    }

    fun resetVoiceMeteringState() {
        voiceAudioLevels = emptyList()
        voiceRecordingDurationSeconds = 0.0
    }

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
    var isBranchPickerOpen by rememberSaveable(threadId) { mutableStateOf(false) }
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
            !isThreadRunning && !sending && !hasComposerDraftContent
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

    val attachmentLimitMessage = stringResource(R.string.turn_attachment_limit, MAX_COMPOSER_ATTACHMENTS)
    val attachmentOverflowMessage = stringResource(R.string.turn_attachment_overflow, MAX_COMPOSER_ATTACHMENTS)
    val attachmentLoadFailedMessage = stringResource(R.string.turn_attachment_load_failed)
    val attachmentFileTooLargeMessage = stringResource(R.string.turn_attachment_file_too_large, MAX_FILE_UPLOAD_BYTES / (1024 * 1024))
    val attachmentFileBinarySummary = stringResource(R.string.turn_attachment_file_binary_summary)
    val attachmentCameraUnavailableMessage = stringResource(R.string.turn_attachment_camera_unavailable)
    val attachmentCameraPermissionDeniedMessage = stringResource(R.string.turn_attachment_camera_permission_denied)
    val voiceMicDeniedMessage = stringResource(R.string.turn_voice_mic_permission_denied)
    val voiceRecorderFailedMessage = stringResource(R.string.turn_voice_recorder_failed)
    val voiceNoAudioMessage = stringResource(R.string.turn_voice_no_audio)
    val voiceTranscriptionFailedMessage = stringResource(R.string.turn_voice_transcription_failed)
    val queuedDraftSendFailedMessage = stringResource(R.string.turn_queue_send_failed)
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

    val voiceInteractionEnabled =
        remember(ready, connectionState, sending) {
            ready && connectionState is ConnectionState.Connected && !sending
        }

    fun remainingAttachmentSlots(): Int = (MAX_COMPOSER_ATTACHMENTS - composerAttachments.size).coerceAtLeast(0)

    fun appendLoadingAttachment(): String =
        UUID.randomUUID().toString().also { attachmentId ->
            composerAttachments = composerAttachments.withLoadingAttachment(attachmentId)
        }

    fun updateImageAttachmentResult(
        attachmentId: String,
        attachment: CodexImageAttachment?,
    ) {
        composerAttachments =
            composerAttachments.withImageAttachmentResult(
                attachmentId = attachmentId,
                attachment = attachment,
                failedMessage = attachmentLoadFailedMessage,
            )
        if (attachment == null) {
            lastError = attachmentLoadFailedMessage
        }
    }

    fun loadSelectedAttachment(attachmentId: String, uri: android.net.Uri, imageOnly: Boolean) {
        attachmentUploads.add(attachmentId) { onProgress ->
            val contentType = withContext(Dispatchers.IO) { context.contentResolver.getType(uri).orEmpty() }
            if (imageOnly || contentType.startsWith("image/")) {
                val image = withContext(Dispatchers.IO) { TurnAttachmentCodec.makeAttachment(context, uri) }
                    ?: throw java.io.IOException("$attachmentLoadFailedMessage (stage=image_decode_or_size)")
                TurnComposerAttachmentState.ReadyImage(image)
            } else {
                val file = TurnFileAttachmentCodec.uploadAttachment(context, uri, fileUploadService, attachmentFileTooLargeMessage) { bytes, total ->
                    withContext(Dispatchers.Main) { onProgress(bytes, total) }
                }
                TurnComposerAttachmentState.ReadyFile(file)
            }
        }
    }

    val photoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            val remainingSlots = remainingAttachmentSlots()
            if (remainingSlots <= 0) {
                lastError = attachmentLimitMessage
                return@rememberLauncherForActivityResult
            }
            val acceptedUris = uris.take(remainingSlots)
            if (acceptedUris.size < uris.size) {
                lastError = attachmentOverflowMessage
            }
            acceptedUris.forEach { uri ->
                val attachmentId = appendLoadingAttachment()
                loadSelectedAttachment(attachmentId, uri, imageOnly = true)
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            val remainingSlots = remainingAttachmentSlots()
            if (remainingSlots <= 0) {
                lastError = attachmentLimitMessage
                return@rememberLauncherForActivityResult
            }
            val acceptedUris = uris.take(remainingSlots)
            if (acceptedUris.size < uris.size) {
                lastError = attachmentOverflowMessage
            }
            acceptedUris.forEach { uri ->
                val attachmentId = appendLoadingAttachment()
                loadSelectedAttachment(attachmentId, uri, imageOnly = false)
            }
        }

    val cameraPreviewLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
            if (bitmap == null) return@rememberLauncherForActivityResult
            if (remainingAttachmentSlots() <= 0) {
                lastError = attachmentLimitMessage
                return@rememberLauncherForActivityResult
            }
            val attachmentId = appendLoadingAttachment()
            scope.launch {
                val attachment =
                    withContext(Dispatchers.IO) {
                        TurnAttachmentCodec.makeAttachment(bitmap.toJpegByteArray() ?: return@withContext null)
                    }
                updateImageAttachmentResult(attachmentId, attachment)
            }
        }

    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cameraPreviewLauncher.launch(null)
            } else {
                lastError = attachmentCameraPermissionDeniedMessage
            }
        }

    val audioPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                scope.launch {
                    if (voicePhase != TurnVoicePhase.Idle) return@launch
                    resetVoiceMeteringState()
                    val ok =
                        withContext(Dispatchers.IO) {
                            voiceRecorder.start(::appendVoiceAudioLevel)
                        }
                    if (ok) {
                        voicePhase = TurnVoicePhase.Recording
                    } else {
                        resetVoiceMeteringState()
                        lastError = voiceRecorderFailedMessage
                    }
                }
            } else {
                lastError = voiceMicDeniedMessage
            }
        }

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

    LaunchedEffect(threadId, ready, connectionState, hasResolvedRateLimits, rateLimitsError, isLoadingRateLimits) {
        if (ready &&
            connectionState is ConnectionState.Connected &&
            rateLimitsError == null &&
            !isLoadingRateLimits &&
            repository.shouldAutoRefreshUsageStatus(threadId)
        ) {
            runCatching { repository.refreshUsageStatus(threadId) }
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
        draft = stripMergedMentionPrefix(saved.text, saved.skills, saved.mentions)
        mentionChips = restoreMentionChips(saved.skills, saved.mentions)
        composerAttachments = saved.images.map { TurnComposerAttachment(state = TurnComposerAttachmentState.ReadyImage(it)) }
        reviewTargetName = saved.review?.name
        reviewBaseBranch = saved.branch
        if (saved.plan) setPlanModeEnabled(true)
    }

    val sourceDeviceId = currentTrustedMacDeviceId
    suspend fun retainMissingDraft(error: Throwable, saved: MissingThreadDraft) {
        missingDraft = saved
        keepEditing = false
        retryFailedSend = null
        lastError = null
        val device = sourceDeviceId ?: return
        try {
            withContext(Dispatchers.IO) { recoveryStore.save(device, threadId, saved) }
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
        transcribeJob?.cancel()
        transcribeJob = null
        voiceRecorder.cancel()
        voicePhase = TurnVoicePhase.Idle
        resetVoiceMeteringState()
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
                        withContext(Dispatchers.IO) { recoveryStore.clear(device, threadId) }
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

    LaunchedEffect(threadId, voicePhase) {
        if (voicePhase != TurnVoicePhase.Recording) return@LaunchedEffect
        val startedAtNanos = System.nanoTime()
        while (isActive && voicePhase == TurnVoicePhase.Recording) {
            voiceRecordingDurationSeconds = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0
            delay(100)
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

    LaunchedEffect(threadId, messages.size, currentTrustedMacDeviceId) {
        threadChangeSets =
            TurnUsageSheetLogic.recentChangeSetsForThread(
                threadId,
                aiChangeSetPersistence.load(currentTrustedMacDeviceId),
                limit = 50,
            )
    }
    val assistantUndoChangeSetsByMessageId =
        remember(messages, threadChangeSets) {
            assistantUndoChangeSetsByMessageId(messages = messages, changeSets = threadChangeSets)
        }
    var shouldAutoFollowBottom by rememberSaveable(threadId) { mutableStateOf(true) }
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
            )
        }
    val presentation = rememberTimelinePresentation(threadId, visibleMessages, isThreadRunning, activeTurnId)
    val historyRevision = historyPaginationState?.initialLoadRevision ?: 0L
    val receivedHistory = remember(threadId, currentTrustedMacDeviceId, historyRevision) {
        repository.messagesByThread.value[threadId].orEmpty()
    }
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
    LaunchedEffect(threadId, historyRevision, historyReadyToPresent) {
        if (historyRevision > 0 && historyReadyToPresent) {
            androidx.compose.runtime.withFrameNanos { }
            presentedHistoryRevision = historyRevision
            reportHistoryPresentation(HistoryPresentationReceipt(threadId, historyRevision))
        }
    }
    val initialHistoryPhase = historyLoadingPhase(
        connected = ready,
        loading = threadId in loadingHistoryThreadIds,
        loaded = historyPaginationState?.initialTurnsLoaded == true,
        presented = presentedHistoryRevision >= historyRevision,
        error = historyLoadError,
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
    var timelineContentVisible by remember(threadId) { mutableStateOf(true) }
    var smartScrollJob by remember(threadId) { mutableStateOf<Job?>(null) }
    DisposableEffect(threadId) {
        onDispose { smartScrollJob?.cancel() }
    }
    var emittedStreamingResponseMission by rememberSaveable(threadId) { mutableStateOf(false) }
    var emittedCommandCardMission by rememberSaveable(threadId) { mutableStateOf(false) }
    var emittedFileChangeCardMission by rememberSaveable(threadId) { mutableStateOf(false) }
    var emittedLongThreadScrollMission by rememberSaveable(threadId) { mutableStateOf(false) }
    val timelineContentAlpha by animateFloatAsState(
        targetValue = if (timelineContentVisible) 1f else 0.18f,
        label = "timeline-content-alpha",
    )
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
    val timelineListItemOffset =
        if (displayedHiddenEarlierCount > 0 || canLoadOlderRemoteHistory || !olderHistoryError.isNullOrBlank()) {
            1
        } else {
            0
        }
    val chatAnchors =
        remember(presentation.items, timelineListItemOffset) {
            buildChatAnchorsFromItems(
                items = presentation.items,
                listItemOffset = timelineListItemOffset,
            )
        }
    val smartScrollNavigationState by remember(listState, chatAnchors, followThresholdPx) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            buildSmartScrollNavigationState(
                totalItemsCount = info.totalItemsCount,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                lastVisibleItemIndex = last?.index,
                anchors = chatAnchors,
                isNearBottom = timelineBottomOverflow(info.totalItemsCount, last?.index,
                    last?.offset ?: 0, last?.size ?: 0, info.viewportEndOffset, info.afterContentPadding)
                    ?.let { it <= followThresholdPx } == true,
            )
        }
    }
    val historyLoading = rememberTimelineHistoryLoading(
        threadId, repository, listState, messages, visibleMessages, presentation,
        timelineListItemOffset, hiddenEarlierCount, canLoadOlderRemoteHistory,
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
    val lastTimelineListIndex = presentation.lastListIndex(timelineListItemOffset)
    LaunchedEffect(threadId, lastTimelineListIndex) {
        if (lastTimelineListIndex >= 0 && lastAutoScrollThreadId != threadId) {
            listState.scrollToItem(lastTimelineListIndex)
            lastAutoScrollThreadId = threadId
        }
    }
    LaunchedEffect(threadId, listState) {
        var awaitingUserScrollEnd = false
        snapshotFlow { Triple(!listState.canScrollForward, isTimelineDragged, listState.isScrollInProgress) }
            .distinctUntilChanged()
            .collect { (isAtBottom, isDragging, isScrolling) ->
                if (isDragging) {
                    awaitingUserScrollEnd = true
                    shouldAutoFollowBottom = false
                } else if (awaitingUserScrollEnd && !isScrolling) {
                    awaitingUserScrollEnd = false
                    shouldAutoFollowBottom = isAtBottom
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
                following = shouldAutoFollowBottom && lastAutoScrollThreadId == threadId,
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
    val composerModel =
        remember(
            ready,
            sending,
            draftWithMentions,
            composerAttachments,
            mentionChips,
            voicePhase,
            isThreadRunning,
            transcribeJob,
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
                    TurnComposerEvent.SetVoicePhase(voicePhase),
                    TurnComposerEvent.SetThreadRunning(isThreadRunning),
                    TurnComposerEvent.SetTranscribing(
                        voicePhase == TurnVoicePhase.Transcribing || transcribeJob != null,
                    ),
                )
                .fold(TurnComposerModel()) { state, evt ->
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
        fromQueue: Boolean,
        requestId: String = UUID.randomUUID().toString(),
        attempt: Int = 1,
    ) {
        if (sending) return
        retryFailedSend = null
        lastError = null
        if (text.trim().isEmpty() &&
            attachments.isEmpty() &&
            skillMentions.isEmpty() &&
            fileMentions.isEmpty()
        ) return
        if (!fromQueue && isThreadRunning) {
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
                    retryFailedSend = FailedTurnSend(text, attachments, skillMentions, fileMentions, collaborationMode, requestId, attempt)
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
                    if (!fromQueue) {
                        draft = ""
                        composerAttachments = emptyList()
                        mentionChips = emptyList()
                        if (collaborationMode == CodexCollaborationModeKind.plan) {
                            setPlanModeEnabled(false)
                        }
                    }
                }
                .onFailure { e ->
                    sending = false
                    if (e is CancellationException) throw e
                    Log.e("RemodexSend", "Message failed stage=start-turn thread=$threadId request=$requestId attempt=$attempt", e)
                    if (e.isMissingSendTarget()) {
                        val saved = MissingThreadDraft(text, attachments, skillMentions, fileMentions,
                            plan = collaborationMode == CodexCollaborationModeKind.plan,
                            cwd = activeThread?.gitWorkingDirectory, model = activeThread?.model)
                        retainMissingDraft(e, saved)
                        if (fromQueue && draft.isBlank()) restoreUnsentDraft(saved)
                        return@onFailure
                    }
                    if (fromQueue) {
                        runCatching {
                            repository.enqueueTurnDraft(
                                threadId = threadId,
                                text = text,
                                attachments = attachments,
                                skillMentions = skillMentions,
                                fileMentions = fileMentions,
                                collaborationMode = collaborationMode,
                                prepend = true,
                            )
                        }.onFailure { restoreFailure ->
                            if (restoreFailure is CancellationException) {
                                restoreFailure.addSuppressed(e)
                                throw restoreFailure
                            }
                            e.addSuppressed(restoreFailure)
                            Log.e("RemodexSend", "Failed to restore queued message stage=enqueue-after-send-failure thread=$threadId request=$requestId attempt=$attempt", e)
                        }
                        lastError = "${queuedDraftSendFailedMessage}: ${com.remodex.mobile.ui.turn.formatTurnSendError(e)}"
                    } else {
                        lastError = com.remodex.mobile.ui.turn.formatTurnSendError(e)
                        retryFailedSend = FailedTurnSend(text, attachments, skillMentions, fileMentions, collaborationMode, requestId, attempt)
                    }
                }
        }
    }

    fun stageCurrentDraftForSteering() {
        val draftText =
            com.remodex.mobile.ui.turn.appendFileAttachmentsToDraft(
                baseText = draftWithMentions,
                files = readyComposerFileAttachments,
                binarySummary = attachmentFileBinarySummary,
            )
        if (draftText.trim().isEmpty() &&
            readyComposerImageAttachments.isEmpty() &&
            structuredSkillMentions.isEmpty() &&
            structuredFileMentions.isEmpty()
        ) return
        val next =
            PendingSteerDraft(
                draftText = draft,
                wireText = draftText,
                attachments = readyComposerImageAttachments,
                skillMentions = structuredSkillMentions,
                fileMentions = structuredFileMentions,
                mentionChips = mentionChips,
                composerAttachments = composerAttachments,
            )
        val editingId = editingSteerDraftId
        pendingSteerDrafts =
            if (editingId != null && pendingSteerDrafts.any { it.id == editingId }) {
                pendingSteerDrafts.map { queued ->
                    if (queued.id == editingId) next.copy(id = editingId) else queued
                }
            } else {
                pendingSteerDrafts + next
            }
        editingSteerDraftId = null
        draft = ""
        composerAttachments = emptyList()
        mentionChips = emptyList()
    }

    fun sendPendingSteerDraft(pending: PendingSteerDraft) {
        if (sending) return
        val expectedTurnId = activeTurnId
        val sendAsSteer = isThreadRunning && expectedTurnId != null
        scope.launch {
            runCatching {
                if (sendAsSteer) {
                    repository.steerTurn(
                        threadId = threadId,
                        expectedTurnId = expectedTurnId,
                        text = pending.wireText,
                        attachments = pending.attachments,
                        skillMentions = pending.skillMentions,
                        fileMentions = pending.fileMentions,
                    )
                } else {
                    sending = true
                    repository.startTurn(
                        threadId = threadId,
                        text = pending.wireText,
                        attachments = pending.attachments,
                        skillMentions = pending.skillMentions,
                        fileMentions = pending.fileMentions,
                    )
                }
            }.onSuccess {
                pendingSteerDrafts = pendingSteerDrafts.filterNot { it.id == pending.id }
                if (editingSteerDraftId == pending.id) {
                    editingSteerDraftId = null
                }
                AppContainer.betaEngagementRepository.recordMissionEvent(
                    eventType = "turn_steered",
                    screen = "composer",
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                if (e.isMissingSendTarget()) {
                    Log.e("RemodexSend", "Missing queued turn target thread=$threadId", e)
                    val saved = MissingThreadDraft(pending.wireText, pending.attachments, pending.skillMentions, pending.fileMentions,
                        cwd = activeThread?.gitWorkingDirectory, model = activeThread?.model)
                    retainMissingDraft(e, saved)
                    pendingSteerDrafts = pendingSteerDrafts.filterNot { it.id == pending.id }
                    if (draft.isBlank()) restoreUnsentDraft(saved)
                    return@onFailure
                }
                lastError = com.remodex.mobile.ui.turn.formatTurnSendError(e)
            }.also {
                if (!sendAsSteer) {
                    sending = false
                }
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

    var wasThreadRunningForSteerQueue by remember(threadId) { mutableStateOf(false) }
    LaunchedEffect(threadId, isThreadRunning, sending) {
        if (isThreadRunning) {
            wasThreadRunningForSteerQueue = true
        } else if (wasThreadRunningForSteerQueue && !sending) {
            wasThreadRunningForSteerQueue = false
            pendingSteerDrafts.firstOrNull()?.let { pending ->
                sendPendingSteerDraft(pending)
            }
        }
    }

    LaunchedEffect(threadId, isThreadRunning, sending, queuedDraftCount, hasComposerDraftContent, pendingSteerDrafts.size) {
        if (
            isThreadRunning ||
            sending ||
            pendingSteerDrafts.isNotEmpty() ||
            missingDraft != null ||
            queuedDraftCount <= 0 ||
            hasComposerDraftContent
        ) return@LaunchedEffect
        val queued = runCatching { repository.pollTurnDraft(threadId) }.getOrNull() ?: return@LaunchedEffect
        dispatchTurn(
            text = queued.text,
            attachments = queued.attachments,
            skillMentions = queued.skillMentions,
            fileMentions = queued.fileMentions,
            collaborationMode = queued.collaborationMode,
            fromQueue = true,
        )
    }

    val imeBottomInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val isImeVisible = imeBottomInset > 0.dp
    // The bridge returns only the latest successful turn for each conversation.
    val predictionPrefs = remember(context) {
        context.getSharedPreferences("collapsed_composer_predictions", android.content.Context.MODE_PRIVATE)
    }
    val predictionThreadKey = "${currentTrustedMacDeviceId.orEmpty()}|$threadId"
    var collapsedTurn by remember(predictionPrefs, predictionThreadKey) {
        mutableStateOf(predictionPrefs.getString(predictionThreadKey, null))
    }
    fun collapseSuggestion(turnId: String) {
        if (collapsedTurn == turnId) return
        collapsedTurn = turnId
        predictionPrefs.edit().putString(predictionThreadKey, turnId).apply()
    }
    val timelineSettled = lastAutoScrollThreadId == threadId && hasTimelineItems
    val readerAway = isTimelineDragged || !shouldAutoFollowBottom
    val verifiedSuggestion = currentSuggestion?.takeIf { repository.composerPredictions.value[threadId] == it }
    LaunchedEffect(predictionThreadKey, verifiedSuggestion, readerAway, isImeVisible, draft.isNotEmpty()) {
        verifiedSuggestion?.let {
            if (readerAway || isImeVisible || draft.isNotEmpty() || it.collapsedByTyping) collapseSuggestion(it.sourceTurnId)
        }
    }
    // Only the scrollable end margin reserves room for a three-line preview. Its size
    // stays independent of result arrival, dismissal, and the manual full-text sheet.
    val previewRoom = 65.dp + with(LocalDensity.current) { MaterialTheme.typography.bodyMedium.lineHeight.toDp() * 3 }
    val inlineAllowed = timelineSettled && shouldFollowBottom && !readerAway
    val previewAtBottom = inlineAllowed && ready && !isThreadRunning && !sending && !hasComposerDraftContent &&
        !isImeVisible && verifiedSuggestion != null && !verifiedSuggestion.collapsedByTyping && collapsedTurn != verifiedSuggestion.sourceTurnId &&
        loadedDraftKey == predictionThreadKey
    val navigationBottom = TurnConversationSmartScrollBottomPadding + if (previewAtBottom) previewRoom else 0.dp
    Box(
        modifier =
            modifier
                .fillMaxSize()
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                CompositionLocalProvider(LocalThreadGitProjectRoot provides activeThread?.gitWorkingDirectory) {
                    ConversationImageGalleryHost(threadId, messages) {
                    MessageList(
                        presentation = presentation,
                        showEmptyContent = initialHistoryPhase == HistoryLoadingPhase.Ready,
                        listState = listState,
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
                        canLoadOlderRemoteHistory = canLoadOlderRemoteHistory,
                        isLoadingOlderHistory = isLoadingOlderHistory || historyLoading.busy,
                        olderHistoryError = olderHistoryError ?: historyLoading.error,
                        contentPadding =
                            PaddingValues(
                                top = TurnConversationMessageListTopPadding,
                                bottom = TurnConversationMessageListBottomPadding + previewRoom,
                                start = 2.dp,
                                end = 2.dp,
                            ),
                        onLoadEarlierMessages = historyLoading.load.takeIf {
                            hiddenEarlierCount > 0 || canLoadOlderRemoteHistory || !olderHistoryError.isNullOrBlank() || historyLoading.error != null
                        },
                        assistantUndoChangeSetsByMessageId = assistantUndoChangeSetsByMessageId,
                    applyingUndoChangeSetIds = applyingUndoChangeSetIds,
                    onUndoAssistantChanges = { changeSet ->
                        val workingDirectory = changeSet.repoRoot ?: activeThread?.cwd
                        if (workingDirectory.isNullOrBlank()) {
                            inlineUndoError = context.getString(R.string.turn_usage_revert_reason_missing_cwd)
                            return@MessageList
                        }
                        scope.launch {
                            applyingUndoChangeSetIds = applyingUndoChangeSetIds + changeSet.id
                            inlineUndoError = null
                            runCatching {
                                AiChangeSetRevertService(repository).apply(
                                    changeSet = changeSet,
                                    workingDirectory = workingDirectory,
                                )
                            }.onSuccess { applyResult ->
                                if (applyResult.success) {
                                    aiChangeSetPersistence.save(
                                        TurnUsageSheetLogic.markChangeSetReverted(
                                            changeSets = aiChangeSetPersistence.load(currentTrustedMacDeviceId),
                                            changeSetId = changeSet.id,
                                            now = Instant.now(),
                                        ),
                                        currentTrustedMacDeviceId,
                                    )
                                } else {
                                    val message =
                                        applyResult.unsupportedReasons.firstOrNull()
                                            ?: applyResult.conflicts.firstOrNull()?.message
                                            ?: context.getString(R.string.turn_message_action_undo_failed)
                                    inlineUndoError = message
                                    aiChangeSetPersistence.save(
                                        TurnUsageSheetLogic.recordChangeSetRevertError(
                                            changeSets = aiChangeSetPersistence.load(currentTrustedMacDeviceId),
                                            changeSetId = changeSet.id,
                                            message = message,
                                            now = Instant.now(),
                                        ),
                                        currentTrustedMacDeviceId,
                                    )
                                }
                            }.onFailure { error ->
                                val message =
                                    error.message?.ifBlank { null }
                                        ?: context.getString(R.string.turn_message_action_undo_failed)
                                inlineUndoError = message
                                aiChangeSetPersistence.save(
                                    TurnUsageSheetLogic.recordChangeSetRevertError(
                                        changeSets = aiChangeSetPersistence.load(currentTrustedMacDeviceId),
                                        changeSetId = changeSet.id,
                                        message = message,
                                        now = Instant.now(),
                                    ),
                                    currentTrustedMacDeviceId,
                                )
                            }
                            threadChangeSets =
                                TurnUsageSheetLogic.recentChangeSetsForThread(
                                    threadId,
                                    aiChangeSetPersistence.load(currentTrustedMacDeviceId),
                                    limit = 50,
                                )
                            applyingUndoChangeSetIds = applyingUndoChangeSetIds - changeSet.id
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
                            .then(historyLoading.modifier)
                            .graphicsLayer {
                                alpha = timelineContentAlpha
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                    )
                }
                }
                ConversationHistoryStatus(
                    phase = initialHistoryPhase,
                    error = historyLoadError,
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
                        .padding(top = if (presentation.items.isEmpty()) 0.dp else TurnConversationMessageListTopPadding, start = 16.dp, end = 16.dp),
                )
                ConversationQuestionInbox(threadId,
                    Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = navigationBottom))
                SmartScrollNavigationCta(
                    state = smartScrollNavigationState,
                    onNavigate = { requestedIndex ->
                        val targetIndex =
                            requestedIndex.coerceIn(
                                minimumValue = 0,
                                maximumValue = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0),
                            )
                        val previousNavigation = smartScrollJob
                        shouldAutoFollowBottom = false
                        smartScrollJob = scope.launch {
                            previousNavigation?.cancelAndJoin()
                            try {
                                val distanceItems = kotlin.math.abs(targetIndex - listState.firstVisibleItemIndex)
                                if (distanceItems >= SMART_SCROLL_FADE_JUMP_DISTANCE_ITEMS) {
                                    timelineContentVisible = false
                                    delay(90L)
                                    listState.scrollToItem(targetIndex)
                                    delay(120L)
                                } else {
                                    listState.animateScrollToItem(targetIndex)
                                }
                                shouldAutoFollowBottom =
                                    targetIndex >= (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            } finally {
                                timelineContentVisible = true
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = navigationBottom),
                )
            }
            Column(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .zIndex(1f)
                        .padding(bottom = TurnConversationComposerBottomLift),
            ) {
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
                TextButton(enabled = ready && !sending && currentTrustedMacDeviceId != null, onClick = {
                    if (sending) return@TextButton
                    val device = currentTrustedMacDeviceId ?: return@TextButton
                    sending = true
                    scope.launch {
                        try {
                            repository.startThreadForRecoveredDraft(saved.model, saved.cwd) { newId ->
                                check(repository.currentTrustedMacDeviceId.value == device && repository.activeThreadId.value == threadId) {
                                    "Draft recovery source is no longer active"
                                }
                                recoveryStore.save(device, newId, saved.copy(transferred = true))
                            }
                            withContext(Dispatchers.IO) { recoveryStore.clear(device, threadId) }
                            missingDraft = null
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            Log.e("RemodexSend", "Create conversation for retained draft thread=$threadId", error)
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
                onClick = { dispatchTurn(retry.text, retry.attachments, retry.skillMentions, retry.fileMentions, retry.collaborationMode, fromQueue = false, requestId = retry.requestId, attempt = retry.attempt + 1) },
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
        if (queuedDraftCount > 0) {
            com.remodex.mobile.ui.turn.QueuedDraftsCard(
                previews = queuedDraftPreviews,
                totalCount = queuedDraftCount,
                canRestore = canRestoreQueuedDrafts,
                onRestore = { draftId ->
                    scope.launch {
                        if (!canRestoreQueuedDrafts) {
                            lastError = queuedDraftRestoreBlockedMessage
                            return@launch
                        }
                        val restored = runCatching { repository.removeQueuedTurnDraft(threadId, draftId) }.getOrNull()
                        if (restored == null) return@launch
                        mentionChips =
                            com.remodex.mobile.ui.turn.restoreMentionChips(
                                skillMentions = restored.skillMentions,
                                fileMentions = restored.fileMentions,
                            )
                        draft =
                            com.remodex.mobile.ui.turn.stripMergedMentionPrefix(
                                text = restored.text,
                                skillMentions = restored.skillMentions,
                                fileMentions = restored.fileMentions,
                            )
                        composerAttachments =
                            restored.attachments.take(MAX_COMPOSER_ATTACHMENTS).map {
                                TurnComposerAttachment(
                                    state = TurnComposerAttachmentState.ReadyImage(it),
                                )
                            }
                        if (restored.attachments.size > MAX_COMPOSER_ATTACHMENTS) {
                            lastError = attachmentOverflowMessage
                        }
                    }
                },
                onRemove = { draftId ->
                    scope.launch {
                        runCatching { repository.removeQueuedTurnDraft(threadId, draftId) }
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
        val pendingSteers = pendingSteerDrafts
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (pendingSteers.isNotEmpty()) {
                    PendingSteerDraftQueue(
                        pendingDrafts = pendingSteers,
                        onSteer = { pending -> sendPendingSteerDraft(pending) },
                        onEdit = { pending ->
                            editingSteerDraftId = pending.id
                            draft = pending.draftText
                            mentionChips = pending.mentionChips
                            composerAttachments = pending.composerAttachments
                        },
                        onDelete = { pending ->
                            pendingSteerDrafts = pendingSteerDrafts.filterNot { it.id == pending.id }
                            if (editingSteerDraftId == pending.id) {
                                editingSteerDraftId = null
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val prediction = verifiedSuggestion?.takeIf {
                    ready && !isThreadRunning && !sending && !hasComposerDraftContent && draft.isEmpty() &&
                        loadedDraftKey == "${currentTrustedMacDeviceId.orEmpty()}|$threadId"
                }
                TurnComposerBar(
                    draft = draft,
                    prediction = prediction,
                    predictionCollapsed = prediction?.collapsedByTyping == true || prediction?.sourceTurnId == collapsedTurn,
                    allowInlinePrediction = inlineAllowed,
                    onCollapsePrediction = { prediction?.let { collapseSuggestion(it.sourceTurnId) } },
                    isImeVisible = isImeVisible,
                    onAcceptPrediction = {
                        if (prediction != null && draft.isEmpty() && repository.composerPredictions.value[threadId] == prediction) {
                            collapseSuggestion(prediction.sourceTurnId)
                            draft = prediction.text
                            repository.dismissComposerPrediction(threadId, prediction.sourceTurnId)
                            true
                        } else false
                    },
                    attachments = composerAttachments,
                    model = composerModel,
                    isPlanModeEnabled = isPlanModeEnabled,
                    runtimeControls = runtimeControls,
                    mentionChips = mentionChips,
                    autocomplete = autocompleteState,
                    modifier = Modifier.fillMaxWidth(),
            onDraftChange = { next ->
                if (draft.isEmpty() && next.isNotEmpty()) {
                    val suggestion = repository.composerPredictions.value[threadId]
                    if (suggestion != null) collapseSuggestion(suggestion.sourceTurnId)
                    else repository.dismissPredictionForTyping(threadId)
                }
                draft = next
                val target = reviewTarget
                if (target != null && next.trim() != reviewDraftText(target, reviewBaseBranch)) {
                    clearReviewTarget()
                }
            },
            onPickImages = {
                if (reviewTarget != null) {
                    lastError = reviewNoAttachmentsMessage
                    return@TurnComposerBar
                }
                if (composerAttachments.size >= MAX_COMPOSER_ATTACHMENTS) {
                    lastError = attachmentLimitMessage
                } else {
                    photoPickerLauncher.launch("image/*")
                }
            },
            onPickFiles = {
                if (reviewTarget != null) {
                    lastError = reviewNoAttachmentsMessage
                    return@TurnComposerBar
                }
                if (composerAttachments.size >= MAX_COMPOSER_ATTACHMENTS) {
                    lastError = attachmentLimitMessage
                } else {
                    filePickerLauncher.launch(arrayOf("*/*"))
                }
            },
            onTakePhoto = {
                if (reviewTarget != null) {
                    lastError = reviewNoAttachmentsMessage
                    return@TurnComposerBar
                }
                if (composerAttachments.size >= MAX_COMPOSER_ATTACHMENTS) {
                    lastError = attachmentLimitMessage
                    return@TurnComposerBar
                }
                val hasCamera =
                    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                if (!hasCamera) {
                    lastError = attachmentCameraUnavailableMessage
                    return@TurnComposerBar
                }
                val hasPermission =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    cameraPreviewLauncher.launch(null)
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onSetPlanModeEnabled = { setPlanModeEnabled(it) },
            onSelectModel = { option ->
                scope.launch { runCatching { repository.setSelectedModelId(option.id) } }
            },
            onSelectReasoningEffort = { option ->
                scope.launch {
                    runCatching {
                        repository.setSelectedReasoningEffort(option.id.takeUnless { it == TURN_COMPOSER_RUNTIME_AUTO_ID })
                    }
                }
            },
            onSelectAccessMode = { option ->
                CodexAccessMode.entries.firstOrNull { it.name == option.id }?.let { mode ->
                    scope.launch { runCatching { repository.setSelectedAccessMode(mode) } }
                }
            },
            onSelectServiceTier = { option ->
                val tier = CodexServiceTier.entries.firstOrNull { it.name == option.id }
                scope.launch { runCatching { repository.setSelectedServiceTier(tier) } }
            },
            onRetryAttachment = { attachmentId ->
                lastError = null
                attachmentUploads.retry(attachmentId)
            },
            onRemoveAttachment = { attachmentId ->
                attachmentUploads.remove(attachmentId)
                composerAttachments = composerAttachments.filterNot { it.id == attachmentId }
            },
            onRemoveMentionChip = { chip ->
                mentionChips = mentionChips.filterNot { it == chip }
            },
            onSelectAutocomplete = { item ->
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
                            item.payload.semanticValue.equals("compact", ignoreCase = true) -> {
                                draft = (replaced.text.trimEnd() + " /compact").trim()
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
            voiceUiEnabled = voiceInteractionEnabled,
            voiceAudioLevels = voiceAudioLevels,
            voiceRecordingDurationSeconds = voiceRecordingDurationSeconds,
            onVoiceClick = {
                when (voicePhase) {
                    TurnVoicePhase.Idle -> {
                        if (voiceInteractionEnabled) {
                            val hasAudioPermission =
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                            if (!hasAudioPermission) {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                scope.launch {
                                    if (voicePhase != TurnVoicePhase.Idle) return@launch
                                    resetVoiceMeteringState()
                                    val ok =
                                        withContext(Dispatchers.IO) {
                                            voiceRecorder.start(::appendVoiceAudioLevel)
                                        }
                                    if (ok) {
                                        voicePhase = TurnVoicePhase.Recording
                                    } else {
                                        resetVoiceMeteringState()
                                        lastError = voiceRecorderFailedMessage
                                    }
                                }
                            }
                        }
                    }
                    TurnVoicePhase.Recording -> {
                        scope.launch {
                            if (voicePhase != TurnVoicePhase.Recording) return@launch
                            val encoded =
                                withContext(Dispatchers.IO) {
                                    voiceRecorder.stopAndEncodeWav()
                                }
                            val pair =
                                encoded.getOrElse { err ->
                                    voicePhase = TurnVoicePhase.Idle
                                    resetVoiceMeteringState()
                                    if (err.message != "not_recording") {
                                        lastError =
                                            when (err.message) {
                                                "empty_audio" -> voiceNoAudioMessage
                                                else -> voiceRecorderFailedMessage
                                            }
                                    }
                                    return@launch
                                }
                            voicePhase = TurnVoicePhase.Transcribing
                            resetVoiceMeteringState()
                            transcribeJob =
                                scope.launch {
                                    try {
                                        val text =
                                            repository.transcribeBridgeVoiceWav(
                                                pair.first,
                                                pair.second,
                                            )
                                        if (isActive) {
                                            draft = VoiceDraftAppend.append(draft, text)
                                            AppContainer.betaEngagementRepository.recordMissionEvent(
                                                eventType = "voice_input_used",
                                                screen = "composer",
                                            )
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        if (isActive) {
                                            lastError = formatVoiceTranscriptionError(e, voiceTranscriptionFailedMessage)
                                        }
                                    } finally {
                                        transcribeJob = null
                                        if (isActive) {
                                            voicePhase = TurnVoicePhase.Idle
                                        }
                                    }
                                }
                        }
                    }
                    TurnVoicePhase.Transcribing -> Unit
                }
            },
            onCancelVoiceRecording = {
                scope.launch {
                    if (voicePhase != TurnVoicePhase.Recording) return@launch
                    withContext(Dispatchers.IO) { voiceRecorder.cancel() }
                    voicePhase = TurnVoicePhase.Idle
                    resetVoiceMeteringState()
                }
            },
            composerEnvironment = {
                if (pendingSteers.isEmpty() && (!isImeVisible || isBranchPickerOpen)) {
                    TurnComposerSecondaryBar(
                        threadId = threadId,
                        repository = repository,
                        isWorktreeProject = activeThread?.isManagedWorktreeProject == true,
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
                        selectedAccessMode = selectedAccessMode,
                        accessPickerEnabled =
                            ready &&
                                !composerLocks.runtimeControlsLocked &&
                                runtimeControls.accessMode.enabled,
                        onSelectAccessMode = { mode ->
                            scope.launch { runCatching { repository.setSelectedAccessMode(mode) } }
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
                        onBranchPickerOpenChange = { isOpen ->
                            isBranchPickerOpen = isOpen
                        },
                    )
                }
            },
            onSend = {
                transcribeJob?.cancel()
                transcribeJob = null
                voiceRecorder.cancel()
                voicePhase = TurnVoicePhase.Idle
                resetVoiceMeteringState()
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
                if (isThreadRunning) {
                    stageCurrentDraftForSteering()
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
                    fromQueue = false,
                )
            },
                )
            }
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
                markdown = message.text.trim(),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                keyPrefix = "full-${message.id}",
            )
        }
    }
}

@Composable
private fun PendingSteerDraftQueue(
    pendingDrafts: List<PendingSteerDraft>,
    onSteer: (PendingSteerDraft) -> Unit,
    onEdit: (PendingSteerDraft) -> Unit,
    onDelete: (PendingSteerDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val lightChrome = isAgentLightChrome()
    val shape = RoundedCornerShape(28.dp)
    val surfaceColor = RemodexPopupChrome.surfaceColor()
    val iconTint =
        if (lightChrome) {
            AgentLightColors.IconMuted
        } else {
            colors.onSurfaceVariant
        }
    val textTint =
        if (lightChrome) {
            AgentLightColors.TextSecondary
        } else {
            colors.onSurfaceVariant
        }
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(
                    min = if (pendingDrafts.size <= 1) 44.dp else 46.dp,
                    max = if (pendingDrafts.size <= 1) 52.dp else 92.dp,
                )
                .shadow(
                    elevation = if (lightChrome) 8.dp else 2.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = if (lightChrome) 0.05f else 0.10f),
                    spotColor = Color.Black.copy(alpha = if (lightChrome) 0.05f else 0.10f),
                ),
        shape = shape,
        color = surfaceColor,
        border = RemodexPopupChrome.borderStroke(),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 20.dp,
                        end = 14.dp,
                        top = if (pendingDrafts.size <= 1) 6.dp else 8.dp,
                        bottom = if (pendingDrafts.size <= 1) 6.dp else 8.dp,
                    ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            pendingDrafts.forEach { pending ->
                PendingSteerDraftQueueRow(
                    pending = pending,
                    textTint = textTint,
                    iconTint = iconTint,
                    onSteer = { onSteer(pending) },
                    onEdit = { onEdit(pending) },
                    onDelete = { onDelete(pending) },
                )
            }
        }
    }
}

@Composable
private fun PendingSteerDraftQueueRow(
    pending: PendingSteerDraft,
    textTint: Color,
    iconTint: Color,
    onSteer: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = pending.draftText.ifBlank { stringResource(R.string.turn_queue_item_empty) },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = textTint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onEdit,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier =
                Modifier
                    .heightIn(min = 26.dp)
                    .clip(RoundedCornerShape(8.dp)),
        ) {
            Text(
                text = "...",
                color = colors.onSurface,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        IconButton(
            onClick = onSteer,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_send_horizontal),
                contentDescription = stringResource(R.string.turn_steer_cd),
                modifier = Modifier.size(17.dp),
                tint = iconTint,
            )
        }
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_trash_2),
                contentDescription = stringResource(R.string.turn_steer_delete_cd),
                modifier = Modifier.size(17.dp),
                tint = iconTint,
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
