package com.remodex.mobile.ui.draft

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.AppContainer
import com.remodex.mobile.R
import com.remodex.mobile.core.config.FeatureFlags
import com.remodex.mobile.core.model.CodexAccessMode
import com.remodex.mobile.core.model.CodexCollaborationModeKind
import com.remodex.mobile.core.model.CodexReviewTarget
import com.remodex.mobile.core.model.CodexServiceTier
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.services.GitActionsService
import com.remodex.mobile.services.ProjectFolderService
import com.remodex.mobile.services.SubscriptionService
import com.remodex.mobile.ui.sidebar.SidebarProjectPickerSheet
import com.remodex.mobile.ui.theme.RemodexDropdownMenu
import com.remodex.mobile.ui.theme.RemodexPageHeader
import com.remodex.mobile.ui.theme.conversationColors
import com.remodex.mobile.ui.turn.ComposerMentionChipPayload
import com.remodex.mobile.ui.turn.IncomingDraftShare
import com.remodex.mobile.ui.turn.MissingThreadDraft
import com.remodex.mobile.ui.turn.MissingThreadDraftStore
import com.remodex.mobile.ui.turn.ReasoningEffortTitleStrings
import com.remodex.mobile.ui.turn.TURN_COMPOSER_RUNTIME_AUTO_ID
import com.remodex.mobile.ui.turn.TurnComposerAttachment
import com.remodex.mobile.ui.turn.TurnComposerAttachmentState
import com.remodex.mobile.ui.turn.TurnComposerBar
import com.remodex.mobile.ui.turn.TurnComposerEvent
import com.remodex.mobile.ui.turn.TurnComposerModel
import com.remodex.mobile.ui.turn.TurnComposerReducer
import com.remodex.mobile.ui.turn.TurnVoicePhase
import com.remodex.mobile.ui.turn.appendFileAttachmentsToDraft
import com.remodex.mobile.ui.turn.buildRuntimeControlsState
import com.remodex.mobile.ui.turn.formatTurnSendError
import com.remodex.mobile.ui.turn.isMissingSendTarget
import com.remodex.mobile.ui.turn.mentionChipsToFileMentions
import com.remodex.mobile.ui.turn.mentionChipsToSkillMentions
import com.remodex.mobile.ui.turn.mergeMentionChipsIntoDraft
import com.remodex.mobile.ui.turn.rememberComposerAttachmentActions
import com.remodex.mobile.ui.turn.restoreMentionChips
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatDraftScreen(
    route: NewChatDraftRoute,
    repository: CodexRepository,
    subscriptionService: SubscriptionService,
    onNavigateBack: () -> Unit,
    onThreadStarted: (String, Boolean) -> Unit,
    onOpenTerminal: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val device by repository.currentTrustedMacDeviceId.collectAsStateWithLifecycle()
    androidx.compose.runtime.key(route.id, device) {
        NewChatDraftContent(route, device, repository, subscriptionService, onNavigateBack, onThreadStarted, onOpenTerminal, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatDraftContent(
    route: NewChatDraftRoute,
    deviceId: String?,
    repository: CodexRepository,
    subscriptionService: SubscriptionService,
    onNavigateBack: () -> Unit,
    onThreadStarted: (String, Boolean) -> Unit,
    onOpenTerminal: (String?) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val ready by repository.isSessionReady.collectAsStateWithLifecycle()
    val conn by repository.connectionState.collectAsStateWithLifecycle()
    val threads by repository.threads.collectAsStateWithLifecycle()
    val availableModels by repository.availableModels.collectAsStateWithLifecycle()
    val isLoadingModels by repository.isLoadingModels.collectAsStateWithLifecycle()
    var selectedModelId by remember(route.id) { mutableStateOf(repository.selectedModelId.value) }
    var selectedReasoningEffort by remember(route.id) { mutableStateOf(repository.selectedReasoningEffort.value) }
    val selectedAccessMode by repository.selectedAccessMode.collectAsStateWithLifecycle()
    var selectedServiceTier by remember { mutableStateOf(repository.selectedServiceTier.value) }
    var choseServiceTier by remember { mutableStateOf(false) }
    val projectFolderService = remember(repository) { ProjectFolderService(repository) }

    var draft by remember(route.id) { mutableStateOf("") }
    val attachmentState = remember { mutableStateOf<List<TurnComposerAttachment>>(emptyList()) }
    val attachments by attachmentState
    val images = remember(attachments) { attachments.mapNotNull { (it.state as? TurnComposerAttachmentState.ReadyImage)?.attachment } }
    val files = remember(attachments) { attachments.mapNotNull { (it.state as? TurnComposerAttachmentState.ReadyFile)?.attachment } }
    val binarySummary = stringResource(R.string.turn_attachment_file_binary_summary)
    var createdThreadId by remember { mutableStateOf<String?>(null) }
    var isPlanModeEnabled by remember(route.id) { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sendAttempts by remember { mutableIntStateOf(0) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val attachmentActions = rememberComposerAttachmentActions(repository, attachmentState, onError = { sendError = it })
    var gitStatusMessage by remember { mutableStateOf<String?>(null) }
    var isLoadingGitStatus by remember { mutableStateOf(false) }
    var selectedProjectPath by remember { mutableStateOf(route.preferredProjectPath) }
    var rootlessPath by remember { mutableStateOf<String?>(null) }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showThreadMenu by remember { mutableStateOf(false) }

    val recovering = route.source == NewChatDraftSource.recoveredChat
    val recoveryThreadId = route.recoveryThreadId
    val context = LocalContext.current
    val recoveryStore = remember(context) { MissingThreadDraftStore(context) }
    var recovered by remember { mutableStateOf<MissingThreadDraft?>(null) }
    var loadingRecovery by remember { mutableStateOf(recovering) }
    var reviewTarget by remember { mutableStateOf<CodexReviewTarget?>(null) }
    var mentionChips by remember { mutableStateOf(emptyList<ComposerMentionChipPayload>()) }
    val recoveryUnavailable = stringResource(R.string.new_chat_recovery_unavailable)
    val recoveryDeviceChanged = stringResource(R.string.new_chat_recovery_device_changed)
    val recoveryLoading = stringResource(R.string.new_chat_recovery_loading)
    val originalWorkspaceError = stringResource(R.string.new_chat_recovery_directory_error)
    val reviewNeedsWorkspace = stringResource(R.string.new_chat_review_needs_workspace)
    var recoveryError by remember { mutableStateOf<String?>(null) }
    val recoveryReady = !recovering || recovered != null

    if (ready && recoveryReady) {
        IncomingDraftShare(repository, deviceId, route.id, stringResource(R.string.new_chat_draft_title),
            attachmentActions, enabled = !sending && reviewTarget == null,
            appendText = { draft = if (draft.isEmpty()) it else draft + "\n" + it })
    }

    val hasSelectedProject = !selectedProjectPath.isNullOrBlank()
    val isConnected = conn is ConnectionState.Connected
    val canUseGitActions = NewChatDraftLogic.gitActionsEnabled(selectedProjectPath, isConnected)
    val canChangeWorkspace = recoveryReady && !sending && createdThreadId == null
    BackHandler { if (!sending) onNavigateBack() }

    LaunchedEffect(recoveryThreadId, deviceId) {
        if (!recovering) return@LaunchedEffect
        try {
            if (deviceId == null || deviceId != route.recoveryDeviceId) {
                recoveryError = recoveryDeviceChanged
                return@LaunchedEffect
            }
            val saved = recoveryThreadId?.let { id ->
                withContext(Dispatchers.IO) { recoveryStore.read(deviceId, id) }
            }
            if (saved == null) {
                recoveryError = recoveryUnavailable
                return@LaunchedEffect
            }
            draft = saved.editableText
            attachmentState.value = saved.composerAttachments()
            mentionChips = restoreMentionChips(saved.skills, saved.mentions)
            selectedProjectPath = saved.cwd
            saved.model?.let { selectedModelId = it }
            isPlanModeEnabled = saved.plan
            reviewTarget = saved.review
            recovered = saved
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.e("RemodexSend", "Could not load retained draft stage=read-draft thread=$recoveryThreadId", error)
            recoveryError = recoveryUnavailable
        } finally {
            loadingRecovery = false
        }
    }

    val runtimeLoadingLabel = stringResource(R.string.turn_runtime_loading)

    LaunchedEffect(ready, conn) {
        if (ready && conn is ConnectionState.Connected) {
            try {
                repository.refreshModels()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w("RemodexSend", "Could not load models stage=refresh-models route=${route.id}", error)
            }
        }
    }

    LaunchedEffect(selectedProjectPath) {
        gitStatusMessage = null
    }

    val runtimeLabels = listOf(
        stringResource(R.string.turn_runtime_model_fallback),
        stringResource(R.string.turn_runtime_no_models),
        stringResource(R.string.turn_runtime_auto),
        stringResource(R.string.turn_runtime_normal),
        stringResource(R.string.turn_runtime_reasoning_title_low),
        stringResource(R.string.turn_runtime_reasoning_title_medium),
        stringResource(R.string.turn_runtime_reasoning_title_high),
        stringResource(R.string.turn_runtime_reasoning_title_xhigh),
    )
    val runtimeControls =
        remember(
            runtimeLabels,
            runtimeLoadingLabel,
            availableModels,
            isLoadingModels,
            selectedModelId,
            selectedReasoningEffort,
            selectedAccessMode,
            selectedServiceTier,
        ) {
            buildRuntimeControlsState(
                models = availableModels,
                isLoadingModels = isLoadingModels,
                selectedModelId = selectedModelId,
                selectedReasoningEffort = selectedReasoningEffort,
                selectedAccessMode = selectedAccessMode,
                selectedServiceTier = selectedServiceTier,
                loadingLabel = runtimeLoadingLabel,
                modelFallbackLabel = runtimeLabels[0],
                noModelsLabel = runtimeLabels[1],
                autoLabel = runtimeLabels[2],
                normalTierLabel = runtimeLabels[3],
                reasoningEffortTitles =
                    ReasoningEffortTitleStrings(
                        low = runtimeLabels[4],
                        medium = runtimeLabels[5],
                        high = runtimeLabels[6],
                        xhigh = runtimeLabels[7],
                    ),
            )
        }

    val composerModel =
        remember(ready, recoveryReady, sending, draft, attachments, mentionChips, reviewTarget) {
            listOf(
                TurnComposerEvent.SetEnabled(ready && recoveryReady),
                TurnComposerEvent.SetSending(sending),
                TurnComposerEvent.SetDraftText(
                    reviewTarget?.name ?: mergeMentionChipsIntoDraft(draft, mentionChips),
                ),
                TurnComposerEvent.SetReadyAttachmentCount(images.size + files.size),
                TurnComposerEvent.SetHasBlockingAttachments(
                    images.size + files.size != attachments.size || (reviewTarget != null && attachments.isNotEmpty()),
                ),
                TurnComposerEvent.SetVoicePhase(TurnVoicePhase.Idle),
                TurnComposerEvent.SetThreadRunning(false),
                TurnComposerEvent.SetTranscribing(false),
            ).fold(TurnComposerModel()) { state, event ->
                TurnComposerReducer.reduce(state, event)
            }
        }

    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun sendDraft() {
        if (!composerModel.derive().canSend || sending) return
        if (subscriptionService.isConfigured && !subscriptionService.hasFreeSendAccess) {
            sendError = "Subscription required to send more messages."
            return
        }
        val text = draft.trim()
        val review = reviewTarget
        if (review != null && !hasSelectedProject) {
            sendError = reviewNeedsWorkspace
            return
        }
        val saved = recovered
        val originalChips = saved?.let { restoreMentionChips(it.skills, it.mentions) }
        val wireText = if (saved != null && draft == saved.editableText && mentionChips == originalChips && files == saved.files) {
            saved.text
        } else {
            appendFileAttachmentsToDraft(mergeMentionChipsIntoDraft(text, mentionChips), files, binarySummary)
        }
        if (review == null && wireText.isEmpty() && images.isEmpty()) return
        val skills = if (saved != null && mentionChips == originalChips) saved.skills else mentionChipsToSkillMentions(mentionChips)
        val mentions = if (saved != null && mentionChips == originalChips) saved.mentions else mentionChipsToFileMentions(mentionChips)
        val collaborationMode = CodexCollaborationModeKind.plan.takeIf { isPlanModeEnabled }
        val workspace = selectedProjectPath
        val model = runtimeControls.model.selected.id.takeUnless { it == TURN_COMPOSER_RUNTIME_AUTO_ID }
        val serviceTier = selectedServiceTier?.name ?: "default".takeIf { choseServiceTier }
        val reasoningEffort = selectedReasoningEffort
        val attempt = ++sendAttempts
        sending = true
        sendError = null
        scope.launch {
            var stage = "check-host"
            val threadId = try {
                check(repository.currentTrustedMacDeviceId.value == deviceId) { recoveryDeviceChanged }
                val target = createdThreadId ?: run {
                    val cwd = when {
                        workspace != null && workspace == saved?.cwd -> {
                            stage = "check-original-workspace"
                            projectFolderService.resolveExistingWorkspace(workspace) { failure ->
                                Log.i("RemodexWorkspace", "Existing workspace is outside the directory browser", failure)
                            }
                        }
                        workspace != null -> workspace
                        else -> {
                            stage = "create-rootless-workspace"
                            rootlessPath ?: projectFolderService.createRootlessChatRoot(text).also { rootlessPath = it }
                        }
                    }
                    check(repository.currentTrustedMacDeviceId.value == deviceId) { recoveryDeviceChanged }
                    stage = "create-thread"
                    repository.createThreadForFirstTurn(model, cwd, serviceTier).id.also { createdThreadId = it }
                }
                stage = "activate-thread"
                check(repository.currentTrustedMacDeviceId.value == deviceId) { recoveryDeviceChanged }
                repository.setActiveThreadId(target)
                stage = "set-reasoning"
                repository.setSelectedReasoningEffort(reasoningEffort)
                stage = "first-turn"
                check(repository.currentTrustedMacDeviceId.value == deviceId) { recoveryDeviceChanged }
                if (review == null) {
                    repository.startTurn(target, wireText, images, skills, mentions, collaborationMode)
                } else {
                    repository.startReview(target, review, saved?.branch)
                }
                target
            } catch (cancel: CancellationException) {
                sending = false
                throw cancel
            } catch (error: Exception) {
                Log.e("RemodexSend", "New chat failed stage=$stage route=${route.id} thread=$createdThreadId attempt=$attempt", error)
                if (stage == "first-turn" && error.isMissingSendTarget()) createdThreadId = null
                val detail = formatTurnSendError(error)
                sendError = if (stage == "check-original-workspace") "$originalWorkspaceError\n$detail" else detail
                sending = false
                return@launch
            }

            if (saved != null && deviceId != null && recoveryThreadId != null) {
                try {
                    withContext(Dispatchers.IO) { recoveryStore.clearIfUnchanged(deviceId, recoveryThreadId, saved) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.e("RemodexSend", "Could not remove retained draft stage=clear-draft thread=$recoveryThreadId newThread=$threadId attempt=$attempt", error)
                }
            }
            if (subscriptionService.isConfigured) subscriptionService.recordFreeSendAttempt()
            if (repository.currentTrustedMacDeviceId.value == deviceId) {
                onThreadStarted(threadId, keyboardVisible)
            }
            try {
                AppContainer.betaEngagementRepository.recordMissionEvent(
                    eventType = "project_thread_started",
                    screen = "sidebar",
                    refreshAfter = false,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w("RemodexSend", "Could not record send stage=record-send thread=$threadId attempt=$attempt", error)
            }
        }
    }

    val reading = MaterialTheme.colorScheme.conversationColors().reading
    Scaffold(
        modifier = modifier,
        containerColor = reading,
        topBar = {
            RemodexPageHeader(
                title = stringResource(R.string.new_chat_draft_title),
                onBack = { if (!sending) onNavigateBack() },
                actions = {
                    Box {
                        IconButton(onClick = { showThreadMenu = true }, enabled = !sending && recoveryReady) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.sidebar_more_menu_cd))
                        }
                        RemodexDropdownMenu(
                            expanded = showThreadMenu,
                            onDismissRequest = { showThreadMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.new_chat_draft_open_terminal)) },
                                onClick = {
                                    showThreadMenu = false
                                    onOpenTerminal(selectedProjectPath)
                                },
                                enabled = hasSelectedProject,
                            )
                            if (FeatureFlags.gitIntegrationEnabled) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.new_chat_draft_git_status)) },
                                    onClick = {
                                        showThreadMenu = false
                                        if (!canUseGitActions || isLoadingGitStatus) return@DropdownMenuItem
                                        val projectPath = selectedProjectPath
                                        isLoadingGitStatus = true
                                        gitStatusMessage = null
                                        scope.launch {
                                            runCatching {
                                                GitActionsService(repository, projectPath).status()
                                            }.onSuccess { status ->
                                                gitStatusMessage = NewChatDraftLogic.gitStatusSummary(status)
                                            }.onFailure { error ->
                                                gitStatusMessage =
                                                    error.message?.takeIf { it.isNotBlank() }
                                                        ?: error.javaClass.simpleName
                                            }
                                            isLoadingGitStatus = false
                                        }
                                    },
                                    enabled = canUseGitActions && !isLoadingGitStatus,
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.new_chat_draft_prompt),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Start,
                    )
                    if (loadingRecovery) Text(recoveryLoading, style = MaterialTheme.typography.bodySmall)
                    recoveryError?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    sendError?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    gitStatusMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            reviewTarget?.let { target ->
                val title = when {
                    target.name != "baseBranch" -> stringResource(R.string.turn_review_current_title)
                    recovered?.branch != null -> stringResource(R.string.turn_review_base_title, recovered?.branch.orEmpty())
                    else -> stringResource(R.string.turn_review_base_title_unset)
                }
                ListItem(
                    headlineContent = { Text(title) },
                    trailingContent = {
                        TextButton(enabled = !sending, onClick = {
                            reviewTarget = null
                            if (draft == recovered?.editableText) draft = ""
                        }) { Text(stringResource(R.string.turn_review_clear)) }
                    },
                )
            }
            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(enabled = canChangeWorkspace, role = Role.Button, onClick = { showProjectPicker = true }),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.new_chat_workspace)) },
                supportingContent = {
                    Text(
                        text = selectedProjectPath ?: stringResource(R.string.new_chat_no_project),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = if (canChangeWorkspace) {
                    { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) }
                } else null,
            )
            TurnComposerBar(
                draft = draft,
                attachments = attachments,
                model = composerModel,
                isPlanModeEnabled = isPlanModeEnabled,
                runtimeControls = runtimeControls,
                mentionChips = mentionChips,
                onRemoveMentionChip = { chip -> mentionChips = mentionChips - chip },
                onDraftChange = {
                    draft = it
                    reviewTarget = null
                },
                onPickImages = attachmentActions.pickImages,
                onPickFiles = attachmentActions.pickFiles,
                onTakePhoto = attachmentActions.takePhoto,
                onSetPlanModeEnabled = { isPlanModeEnabled = it },
                onOpenModelPicker = {
                    if (ready && availableModels.isEmpty() && !isLoadingModels) scope.launch {
                        try { repository.refreshModels() }
                        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (failure: Exception) { sendError = failure.localizedMessage }
                    }
                },
                onSelectModel = { option ->
                    selectedModelId = option.id
                    selectedReasoningEffort = null
                },
                onSelectReasoningEffort = { option -> selectedReasoningEffort = option.id.takeUnless { it == TURN_COMPOSER_RUNTIME_AUTO_ID } },
                onSelectAccessMode = { option ->
                    scope.launch {
                        repository.setSelectedAccessMode(
                            CodexAccessMode.entries.firstOrNull { it.name == option.id }
                                ?: CodexAccessMode.onRequest,
                        )
                    }
                },
                onSelectServiceTier = { option ->
                    selectedServiceTier = CodexServiceTier.entries.firstOrNull { it.name == option.id }
                    choseServiceTier = true
                },
                onRemoveAttachment = attachmentActions.remove,
                onRetryAttachment = attachmentActions.retry,
                onSend = ::sendDraft,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }

    SidebarProjectPickerSheet(
        repository = repository,
        visible = showProjectPicker,
        initialPath = selectedProjectPath,
        onDismiss = { showProjectPicker = false },
        onWorkspaceSelected = { path ->
            if (selectedProjectPath != path) {
                selectedProjectPath = path
                rootlessPath = null
                sendError = null
            }
            showProjectPicker = false
        },
        initialFoldersCollapsed = route.source == NewChatDraftSource.folderChat,
        threads = threads,
    )
}
