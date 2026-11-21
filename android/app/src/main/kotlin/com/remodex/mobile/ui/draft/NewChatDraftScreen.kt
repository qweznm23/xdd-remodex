package com.remodex.mobile.ui.draft

import com.remodex.mobile.ui.theme.RemodexDropdownMenu
import com.remodex.mobile.ui.theme.RemodexPageHeader
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.remodex.mobile.core.config.FeatureFlags
import com.remodex.mobile.ui.theme.conversationColors
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexAccessMode
import com.remodex.mobile.core.model.CodexCollaborationModeKind
import com.remodex.mobile.core.model.CodexServiceTier
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.data.WorktreeNewChatDefaults
import com.remodex.mobile.services.GitActionsService
import com.remodex.mobile.services.ProjectFolderService
import com.remodex.mobile.services.SubscriptionService
import com.remodex.mobile.ui.agent.truncatePathMiddle
import com.remodex.mobile.ui.sidebar.SidebarProjectPickerSheet
import com.remodex.mobile.ui.turn.TURN_COMPOSER_RUNTIME_AUTO_ID
import com.remodex.mobile.ui.turn.TurnComposerAttachment
import com.remodex.mobile.ui.turn.TurnComposerAttachmentState
import com.remodex.mobile.ui.turn.appendFileAttachmentsToDraft
import com.remodex.mobile.ui.turn.rememberComposerAttachmentActions
import com.remodex.mobile.ui.turn.TurnComposerBar
import com.remodex.mobile.ui.turn.TurnComposerEvent
import com.remodex.mobile.ui.turn.TurnComposerModel
import com.remodex.mobile.ui.turn.TurnComposerReducer
import com.remodex.mobile.ui.turn.ReasoningEffortTitleStrings
import com.remodex.mobile.ui.turn.TurnVoicePhase
import com.remodex.mobile.ui.turn.buildRuntimeControlsState
import kotlinx.coroutines.launch

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
        NewChatDraftContent(route, repository, subscriptionService, onNavigateBack, onThreadStarted, onOpenTerminal, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatDraftContent(
    route: NewChatDraftRoute,
    repository: CodexRepository,
    subscriptionService: SubscriptionService,
    onNavigateBack: () -> Unit,
    onThreadStarted: (String, Boolean) -> Unit,
    onOpenTerminal: (String?) -> Unit,
    modifier: Modifier,
) {
    BackHandler(onBack = onNavigateBack)
    val scope = rememberCoroutineScope()
    val ready by repository.isSessionReady.collectAsStateWithLifecycle()
    val conn by repository.connectionState.collectAsStateWithLifecycle()
    val threads by repository.threads.collectAsStateWithLifecycle()
    val activeThreadId by repository.activeThreadId.collectAsStateWithLifecycle()
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
    var sendError by remember { mutableStateOf<String?>(null) }
    val attachmentActions = rememberComposerAttachmentActions(repository, attachmentState, onError = { sendError = it })
    var gitStatusMessage by remember { mutableStateOf<String?>(null) }
    var isLoadingGitStatus by remember { mutableStateOf(false) }
    var selectedProjectPath by remember(route.id) {
        mutableStateOf(
            when {
                route.source.isFromGeneralChat && route.preferredProjectPath == null -> null
                else -> route.preferredProjectPath ?: WorktreeNewChatDefaults.baseProjectPath(activeThreadId, threads)
            },
        )
    }
    var showProjectPicker by remember { mutableStateOf(false) }
    var showThreadMenu by remember { mutableStateOf(false) }

    val hasSelectedProject = !selectedProjectPath.isNullOrBlank()
    val isConnected = conn is ConnectionState.Connected
    val canUseGitActions =
        NewChatDraftLogic.gitActionsEnabled(
            projectPath = selectedProjectPath,
            isConnected = isConnected,
        )
    val pathSubtitle =
        remember(selectedProjectPath) {
            selectedProjectPath?.let { truncatePathMiddle(it) }
        }
    val runtimeLoadingLabel = stringResource(R.string.turn_runtime_loading)

    LaunchedEffect(ready, conn) {
        if (ready && conn is ConnectionState.Connected) {
            runCatching { repository.refreshModels() }
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
        remember(ready, sending, draft, attachments) {
            listOf(
                TurnComposerEvent.SetEnabled(ready),
                TurnComposerEvent.SetSending(sending),
                TurnComposerEvent.SetDraftText(draft),
                TurnComposerEvent.SetReadyAttachmentCount(images.size + files.size),
                TurnComposerEvent.SetHasBlockingAttachments(images.size + files.size != attachments.size),
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
        val wireText = appendFileAttachmentsToDraft(text, files, binarySummary)
        if (wireText.isEmpty() && images.isEmpty()) return
        sending = true
        sendError = null
        scope.launch {
            runCatching {
                val threadId = createdThreadId ?: run {
                    val requestedCwd = selectedProjectPath ?: if (route.source.isFromGeneralChat) {
                        projectFolderService.createRootlessChatRoot(text)
                    } else null
                    repository.startThread(
                        model = runtimeControls.model.selected.id.takeUnless { it == TURN_COMPOSER_RUNTIME_AUTO_ID },
                        cwd = requestedCwd,
                        serviceTier = selectedServiceTier?.name ?: "default".takeIf { choseServiceTier },
                    ).id.also { createdThreadId = it }
                }
                repository.setActiveThreadId(threadId)
                repository.setSelectedReasoningEffort(selectedReasoningEffort)
                repository.startTurn(
                    threadId = threadId,
                    text = wireText,
                    attachments = images,
                    collaborationMode =
                        if (isPlanModeEnabled) {
                            CodexCollaborationModeKind.plan
                        } else {
                            null
                        },
                )
                if (subscriptionService.isConfigured) {
                    subscriptionService.recordFreeSendAttempt()
                }
                onThreadStarted(threadId, keyboardVisible)
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                sendError = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                sending = false
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
                onBack = onNavigateBack,
                subtitle = pathSubtitle,
                actions = {
                    Box {
                        IconButton(onClick = { showThreadMenu = true }) {
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
                    .padding(innerPadding),
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
                    if (route.source.isFromGeneralChat && !hasSelectedProject) {
                        Text(
                            text = stringResource(R.string.new_chat_draft_quick_chat_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Start,
                        )
                    }
                    if (!route.source.isFromGeneralChat && hasSelectedProject && createdThreadId == null && !sending) {
                        TextButton(onClick = { showProjectPicker = true }) {
                            Text(stringResource(R.string.new_chat_draft_change_project))
                        }
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
            TurnComposerBar(
                draft = draft,
                attachments = attachments,
                model = composerModel,
                isPlanModeEnabled = isPlanModeEnabled,
                runtimeControls = runtimeControls,
                onDraftChange = { draft = it },
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
                composerEnvironment = {
                    if (hasSelectedProject) {
                        Text(
                            text = pathSubtitle.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }

    SidebarProjectPickerSheet(
        repository = repository,
        visible = showProjectPicker,
        initialPath = selectedProjectPath ?: WorktreeNewChatDefaults.baseProjectPath(activeThreadId, threads),
        onDismiss = { showProjectPicker = false },
        onStartBusyChange = {},
        onStartThread = { cwd ->
            selectedProjectPath = cwd?.trim()?.takeIf { it.isNotEmpty() }
            showProjectPicker = false
        },
        initialFoldersCollapsed = route.source == NewChatDraftSource.folderChat,
        threads = threads,
        activeThreadId = activeThreadId,
        activeChatMetadata = null,
        onThreadStarted = { showProjectPicker = false },
    )
}
