package com.remodex.mobile.ui.shell

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import com.remodex.mobile.services.loadGitToolbarStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import com.remodex.mobile.ui.theme.conversationColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.remodex.mobile.AppContainer
import com.remodex.mobile.R
import com.remodex.mobile.core.config.FeatureFlags
import com.remodex.mobile.core.model.AIUnifiedPatchParser
import com.remodex.mobile.core.model.GitBranchesWithStatusResult
import com.remodex.mobile.core.model.GitDiffTotals
import com.remodex.mobile.core.model.GitRepoSyncResult
import com.remodex.mobile.core.model.GitRunStackedActionResult
import com.remodex.mobile.core.model.GitStackedActionProgressEvent
import com.remodex.mobile.core.model.GitWorktreeChangeTransferMode
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.remodex.mobile.core.model.PendingApprovalDecision
import com.remodex.mobile.core.model.TurnGitActionKind
import com.remodex.mobile.core.model.TurnGitPreflightOperation
import com.remodex.mobile.core.model.TurnGitPreflightPolicy
import com.remodex.mobile.core.model.TurnGitSyncAlert
import com.remodex.mobile.core.model.TurnGitSyncAlertAction
import com.remodex.mobile.core.model.TurnGitSyncAlertButtonRole
import com.remodex.mobile.data.gitWorkingDirectoryForGitActions
import com.remodex.mobile.data.RepoDiffLastTurnAggregator
import com.remodex.mobile.data.RepoDiffLastTurnFileRow
import com.remodex.mobile.data.WorktreeFlowCoordinator
import com.remodex.mobile.data.WorktreeFlowHandoffOutcome
import com.remodex.mobile.data.remodexBuildPullRequestUrl
import com.remodex.mobile.data.remodexResolveCommitMessage
import com.remodex.mobile.services.DesktopHandoffService
import com.remodex.mobile.services.GitActionsError
import com.remodex.mobile.services.GitActionsService
import com.remodex.mobile.services.WorkspaceTextFileService
import com.remodex.mobile.ui.LocalCodexRepository
import com.remodex.mobile.ui.agent.ConversationHeader
import com.remodex.mobile.ui.agent.SidebarDrawerContent
import com.remodex.mobile.ui.agent.truncatePathMiddle
import com.remodex.mobile.ui.home.BridgeUpdateSheet
import com.remodex.mobile.ui.home.GitActionProgressBannerState
import com.remodex.mobile.ui.home.GitActionProgressPhase
import com.remodex.mobile.ui.home.RootViewModel
import com.remodex.mobile.ui.home.ThreadCompletionBanner
import com.remodex.mobile.ui.navigation.AppNavHost
import com.remodex.mobile.ui.navigation.AppRoutes
import com.remodex.mobile.ui.pet.PetCompanionOverlay
import com.remodex.mobile.ui.sidebar.SidebarActiveChatMetadata
import com.remodex.mobile.ui.sidebar.rememberSidebarColorPalette
import com.remodex.mobile.ui.turn.LocalOpenRepoDiffForMarkdownLink
import com.remodex.mobile.ui.turn.RepoMarkdownFileLink
import com.remodex.mobile.ui.turn.WorkspaceTextFilePreviewDialog
import com.remodex.mobile.ui.turn.WorkspaceTextFilePreviewRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.withTimeout
import java.util.UUID

private const val GIT_OPERATION_TIMEOUT_MS = 150_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    viewModel: RootViewModel,
    onOpenPairingScanner: () -> Unit,
    onOpenPairingCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = LocalCodexRepository.current
    val workspaceTextFileService = remember(repository) { WorkspaceTextFileService(repository) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val timelineRenderingEnabled by remember(drawerState) {
        derivedStateOf {
            // Pause background timeline work only at the settled open anchor.
            // Resume on the first close/drag frame, not at the end of the drawer animation.
            !drawerState.isOpen || drawerState.isAnimationRunning || drawerState.currentOffset != 0f
        }
    }
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val showShellHeader = currentRoute == AppRoutes.Home
    val lifecycleOwner = LocalLifecycleOwner.current
    val sidebarColors = rememberSidebarColorPalette()

    val ready by repository.isSessionReady.collectAsStateWithLifecycle()
    LaunchedEffect(repository, lifecycleOwner, ready) {
        if (!ready) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                repository.refreshUsageStatus(null)
                kotlinx.coroutines.delay(60_000L)
            }
        }
    }
    val currentReady by rememberUpdatedState(ready)
    var backgroundedWhileReady by remember { mutableStateOf(false) }
    val activeThreadId by repository.activeThreadId.collectAsStateWithLifecycle()
    val environment = remember(activeThreadId) { com.remodex.mobile.ui.turn.SessionEnvironmentHost(activeThreadId) }
    val shareInbox = com.remodex.mobile.LocalShareInbox.current
    val pendingShare = shareInbox?.first
    LaunchedEffect(pendingShare?.id, currentRoute != null) {
        if (pendingShare == null || currentRoute == null) return@LaunchedEffect
        if (currentRoute != AppRoutes.Home) navController.popBackStack(AppRoutes.Home, inclusive = false)
        if (activeThreadId == null || !ready) drawerState.moveToSessionDrawer(DrawerValue.Open)
    }
    var refreshingConversation by remember(activeThreadId) { mutableStateOf(false) }
    var conversationRefreshError by remember(activeThreadId) { mutableStateOf<String?>(null) }
    var conversationRefreshJob by remember(activeThreadId) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var historyPresentationReceipt by remember(activeThreadId) { mutableStateOf<com.remodex.mobile.ui.turn.HistoryPresentationReceipt?>(null) }
    DisposableEffect(activeThreadId) {
        onDispose { conversationRefreshJob?.cancel() }
    }
    val threads by repository.threads.collectAsStateWithLifecycle()
    val runningTurnByThread by repository.runningTurnIdByThread.collectAsStateWithLifecycle()
    val protectedRunningFallback by repository.protectedRunningFallbackThreadIds.collectAsStateWithLifecycle()
    val pendingApprovalRequest by repository.pendingApprovalRequest.collectAsStateWithLifecycle()
    val bridgeUpdatePrompt by repository.bridgeUpdatePrompt.collectAsStateWithLifecycle()
    val connectionState by repository.connectionState.collectAsStateWithLifecycle()
    val reconnectUiState by viewModel.reconnectUiState.collectAsStateWithLifecycle()
    val handoffFallbackMessage = stringResource(R.string.turn_open_desktop_failed_fallback)
    val appName = stringResource(R.string.app_name)
    val gitStatusFailureMessage = stringResource(R.string.git_status_unavailable)
    val context = LocalContext.current
    var desktopHandoffError by remember { mutableStateOf<String?>(null) }
    var handingOffToDesktop by remember { mutableStateOf(false) }
    var handingOffWorktree by remember { mutableStateOf(false) }
    var worktreeHandoffError by remember { mutableStateOf<String?>(null) }
    var repoStatusSnapshot by remember { mutableStateOf<GitRepoSyncResult?>(null) }
    var branchesWithStatusSnapshot by remember { mutableStateOf<GitBranchesWithStatusResult?>(null) }
    var repoDiffTotals by remember { mutableStateOf<GitDiffTotals?>(null) }
    var defaultGitBaseBranch by remember { mutableStateOf<String?>(null) }
    var isLoadingRepoDiff by remember { mutableStateOf(false) }
    var gitStatusFailed by remember { mutableStateOf(false) }
    var gitToolbarRefreshNonce by remember { mutableStateOf(0) }
    var showRepoDiffSheet by remember { mutableStateOf(false) }
    var repoDiffSheetScope by remember { mutableStateOf(GitRepoDiffScope.LastTurn) }
    /** threadId → full working-tree patch from bridge (invalidated via [gitToolbarRefreshNonce]). */
    var cachedFullWorkingTreeDiff by remember { mutableStateOf<Pair<String, String>?>(null) }
    var repoDiffSheetLastTurnRows by remember { mutableStateOf<List<RepoDiffLastTurnFileRow>>(emptyList()) }
    var repoDiffSheetFullPatch by remember { mutableStateOf("") }
    var repoDiffSheetFullLoading by remember { mutableStateOf(false) }
    var repoDiffSheetFullError by remember { mutableStateOf<String?>(null) }
    var repoDiffPrefetchRequestId by remember { mutableStateOf(0) }
    var repoDiffMarkdownFocusQuery by remember { mutableStateOf<String?>(null) }
    var workspaceTextFilePreviewRequest by remember { mutableStateOf<WorkspaceTextFilePreviewRequest?>(null) }
    var gitActionBusy by remember { mutableStateOf(false) }
    var gitActionError by remember { mutableStateOf<String?>(null) }
    var gitActionProgressMessage by remember { mutableStateOf<String?>(null) }
    var gitActionProgressPhase by remember { mutableStateOf<GitActionProgressPhase?>(null) }
    var gitActionProgressIncludesPush by remember { mutableStateOf(true) }
    var gitActionProgressIncludesPullRequest by remember { mutableStateOf(false) }
    var gitActionSheetMode by remember { mutableStateOf<GitActionSheetMode?>(null) }
    var gitActionSheetInitialNextStep by remember { mutableStateOf<GitActionNextStep?>(null) }
    var gitSyncAlert by remember { mutableStateOf<TurnGitSyncAlert?>(null) }
    var pendingGitOperation by remember { mutableStateOf<PendingGitOperation?>(null) }
    var showNothingToCommit by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val isActiveTurnRunning =
        ready &&
            activeThreadId?.let { tid ->
                runningTurnByThread.containsKey(tid) || protectedRunningFallback.contains(tid)
            } == true
    val showDesktopHandoff = ready && !activeThreadId.isNullOrBlank()
    val activeThread =
        remember(activeThreadId, threads) {
            activeThreadId?.let { id -> threads.firstOrNull { it.id == id } }
        }
    val activeThreadTitle =
        remember(activeThreadId, threads, appName) {
            activeThreadId
                ?.let { id -> threads.firstOrNull { it.id == id }?.displayTitle }
                ?.takeIf { it.isNotBlank() }
                ?: appName
        }
    val threadPathFull =
        remember(activeThread) {
            activeThread?.cwd?.trim()?.takeIf { it.isNotEmpty() }
        }
    val pathSubtitle =
        remember(threadPathFull) {
            threadPathFull?.let { truncatePathMiddle(it) }
        }
    // The shell only needs chat bodies for Git summaries, never for navigation or connection state.
    val threadMessages = if (FeatureFlags.gitIntegrationEnabled) {
        val messagesByThread by repository.messagesByThread.collectAsStateWithLifecycle()
        activeThreadId?.let { messagesByThread[it] }.orEmpty()
    } else emptyList()
    val lastTurnDiffTotals =
        remember(threadMessages) {
            val rows = RepoDiffLastTurnAggregator.fileRowsFromLastTurn(threadMessages)
            if (rows.isEmpty()) {
                null
            } else {
                val additions = rows.sumOf { AIUnifiedPatchParser.additionsDeletionsForDisplay(it.chunk).first }
                val deletions = rows.sumOf { AIUnifiedPatchParser.additionsDeletionsForDisplay(it.chunk).second }
                GitDiffTotals(additions = additions, deletions = deletions).takeIf { it.hasChanges }
            }
        }
    val sidebarActiveChatMetadata =
        remember(activeThreadId, activeThread, repoStatusSnapshot, lastTurnDiffTotals) {
            if (activeThreadId == null) {
                null
            } else {
                SidebarActiveChatMetadata(
                    branch = repoStatusSnapshot?.currentBranch,
                    additions = lastTurnDiffTotals?.additions,
                    deletions = lastTurnDiffTotals?.deletions,
                    model = activeThread?.model,
                )
            }
        }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        if (currentReady) {
                            backgroundedWhileReady = true
                        }
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        if (backgroundedWhileReady && currentReady) viewModel.catchUpAfterBackground()
                        else viewModel.onAppForegrounded()
                        if (backgroundedWhileReady) {
                            backgroundedWhileReady = false
                            scope.launch {
                                AppContainer.betaEngagementRepository.recordMissionEvent(
                                    eventType = "session_recovered_from_background",
                                    screen = "conversation",
                                )
                            }
                        }
                    }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.onAppLaunched()
        if (FeatureFlags.betaEngagementEnabled) {
            AppContainer.betaEngagementRepository.recoverBetaIdentityIfNeeded()
        }
    }

    LaunchedEffect(ready) {
        if (ready) {
            viewModel.restoreActiveThreadIfNeeded()
        }
    }

    LaunchedEffect(activeThreadId) {
        val id = activeThreadId
        if (!id.isNullOrBlank()) {
            viewModel.persistActiveThreadId(id)
        }
    }

    val gitCwd = remember(activeThread) { activeThread?.gitWorkingDirectoryForGitActions() }
    val showGitControls = FeatureFlags.gitIntegrationEnabled && ready && !gitCwd.isNullOrBlank()
    val isWorktreeProject = activeThread?.isManagedWorktreeProject == true
    val associatedWorktreePath =
        remember(activeThreadId, threads) {
            activeThreadId?.let { repository.associatedManagedWorktreePathFor(it) }
        }
    val localWorktreeHandoffTargetPath =
        remember(branchesWithStatusSnapshot) {
            val branches = branchesWithStatusSnapshot ?: return@remember null
            val preferredBranch = branches.defaultBranch ?: branches.currentBranch
            preferredBranch
                ?.let { branches.worktreePathByBranch[it]?.trim()?.takeIf { path -> path.isNotEmpty() } }
                ?: branches.worktreePathByBranch.values.firstOrNull { it.isNotBlank() }?.trim()
        }
    val showWorktreeHandoff =
        showGitControls &&
            ready &&
            activeThreadId != null &&
            !isActiveTurnRunning &&
            !handingOffWorktree &&
            (
                (isWorktreeProject && localWorktreeHandoffTargetPath != null) ||
                    (!isWorktreeProject && (associatedWorktreePath != null || defaultGitBaseBranch != null))
            )
    val gitToastMessage =
        if (gitStatusFailed && showGitControls && gitActionProgressPhase == null) {
                gitStatusFailureMessage
            } else {
                null
            }
    val gitProgressToast =
        gitActionProgressPhase?.let {
            GitActionProgressBannerState(
                phase = it,
                includesPush = gitActionProgressIncludesPush,
                includesPullRequest = gitActionProgressIncludesPullRequest,
            )
        }

    fun enqueueRepoDiffFullTreePrefetch() {
        val tid = activeThreadId
        val cwd = gitCwd
        repoDiffPrefetchRequestId += 1
        val requestId = repoDiffPrefetchRequestId
        if (tid != null && !cwd.isNullOrBlank()) {
            val cached = cachedFullWorkingTreeDiff?.takeIf { it.first == tid }
            repoDiffSheetFullPatch = cached?.second.orEmpty()
            repoDiffSheetFullLoading = cached == null || cached.second.isBlank()
            scope.launch {
                val result = runCatching { GitActionsService(repository, cwd).diff() }
                if (repoDiffPrefetchRequestId != requestId || activeThreadId != tid || gitCwd != cwd) {
                    return@launch
                }
                result
                    .onSuccess {
                        cachedFullWorkingTreeDiff = tid to it.patch
                        repoDiffSheetFullPatch = it.patch
                    }
                    .onFailure {
                        val rawMessage = it.message.orEmpty()
                        val userVisibleMessage = rawMessage.withoutGitLineEndingWarnings().ifBlank { null }
                        repoDiffSheetFullError =
                            if (rawMessage.isNotBlank() && userVisibleMessage == null) {
                                null
                            } else {
                                userVisibleMessage ?: context.getString(R.string.git_repo_diff_load_error)
                            }
                    }
                repoDiffSheetFullLoading = false
            }
        } else {
            repoDiffSheetFullPatch = ""
            repoDiffSheetFullLoading = false
        }
    }

    fun openRepoDiffSheetFromHeader() {
        repoDiffMarkdownFocusQuery = null
        showRepoDiffSheet = true
        repoDiffSheetScope = GitRepoDiffScope.LastTurn
        repoDiffSheetFullError = null
        repoDiffSheetLastTurnRows =
            RepoDiffLastTurnAggregator.fileRowsFromLastTurn(threadMessages)
        enqueueRepoDiffFullTreePrefetch()
        scope.launch {
            AppContainer.betaEngagementRepository.recordMissionEvent(
                eventType = "repo_diff_reviewed",
                screen = "git",
                refreshAfter = false,
            )
        }
    }

    fun openRepoDiffSheetFromMarkdown(
        link: String,
        threadProjectRoot: String?,
    ) {
        val previewPath = RepoMarkdownFileLink.previewPath(link) ?: return
        val cwdFromAbsoluteLink = RepoMarkdownFileLink.previewWorkspaceCwd(previewPath)
        workspaceTextFilePreviewRequest =
            WorkspaceTextFilePreviewRequest(
                path = previewPath,
                cwd =
                    cwdFromAbsoluteLink
                        ?: threadProjectRoot?.trim()?.takeIf { it.isNotEmpty() }
                        ?: activeThread?.cwd?.trim()?.takeIf { it.isNotEmpty() }
                        ?: gitCwd,
            )
    }

    LaunchedEffect(activeThreadId, gitToolbarRefreshNonce) {
        cachedFullWorkingTreeDiff = null
    }

    fun handleGitActionFailure(
        e: Throwable,
        onNothingToCommit: () -> Unit,
    ) {
        gitActionProgressMessage = null
        gitActionProgressPhase = null
        gitActionProgressIncludesPush = true
        gitActionProgressIncludesPullRequest = false
        when {
            e is GitActionsError.BridgeFailure && e.errorCode == "nothing_to_commit" -> onNothingToCommit()
            e is GitActionsError.BridgeFailure &&
                (e.errorCode == "branch_is_main" || e.errorCode == "protected_branch") -> {
                gitSyncAlert =
                    TurnGitSyncAlert.withDefaultButtons(
                        title = "Protected branch",
                        message = e.message?.ifBlank { null } ?: "This branch is protected.",
                        action = TurnGitSyncAlertAction.dismissOnly,
                    )
                pendingGitOperation = null
            }
            e is TimeoutCancellationException -> {
                gitActionError = "Git operation timed out. Check the desktop bridge and try again."
            }
            else -> {
                gitActionError = e.message?.ifBlank { null } ?: e.toString()
            }
        }
    }

    suspend fun resolveCommitMessage(
        git: GitActionsService,
        rawMessage: String,
    ): String? {
        return remodexResolveCommitMessage(rawMessage) { git.generateCommitMessage().fullMessage }
    }

    suspend fun openPullRequestUrl(
        git: GitActionsService,
        submission: GitActionSheetSubmission,
    ) {
        val st = git.status()
        val bw = git.branchesWithStatus()
        val branch = st.currentBranch?.trim().orEmpty()
        val base =
            submission.baseBranch.trim()
                .ifEmpty { bw.defaultBranch?.trim().orEmpty() }
        if (branch.isEmpty() || base.isEmpty()) {
            throw IllegalStateException("Could not determine branch or default for PR.")
        }
        val draft =
            if (submission.pullRequestTitle.isBlank() || submission.pullRequestBody.isBlank()) {
                runCatching { git.generatePullRequestDraft(baseBranch = base) }.getOrNull()
            } else {
                null
            }
        val title =
            submission.pullRequestTitle.trim()
                .ifEmpty { draft?.title?.trim().orEmpty() }
        val body =
            submission.pullRequestBody.trim()
                .ifEmpty { draft?.body?.trim().orEmpty() }
        val remote = git.remoteUrl()
        val ownerRepo =
            remote.ownerRepo?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Could not read Git remote (GitHub PR link needs origin).")
        val url = remodexBuildPullRequestUrl(ownerRepo, branch, base, title, body)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun operationForSubmission(submission: GitActionSheetSubmission): TurnGitPreflightOperation =
        when (submission.nextStep) {
            GitActionNextStep.commit -> TurnGitPreflightOperation.commit
            GitActionNextStep.commitAndPush,
            GitActionNextStep.push,
            -> TurnGitPreflightOperation.push
            GitActionNextStep.commitPushAndPullRequest,
            GitActionNextStep.pushAndPullRequest,
            GitActionNextStep.createPullRequest,
            -> TurnGitPreflightOperation.createPullRequest
        }

    fun enqueueGitPreflightIfNeeded(
        cwd: String,
        operation: TurnGitPreflightOperation,
        submission: GitActionSheetSubmission? = null,
        status: GitRepoSyncResult? = repoStatusSnapshot,
        branches: GitBranchesWithStatusResult? = branchesWithStatusSnapshot,
    ): Boolean {
        val alert = TurnGitPreflightPolicy.alertFor(status, branches, operation) ?: return false
        pendingGitOperation =
            PendingGitOperation(
                operation = operation,
                cwd = cwd,
                submission = submission,
            )
        gitSyncAlert = alert
        return true
    }

    suspend fun executeGitActionSheetNow(
        submission: GitActionSheetSubmission,
        cwd: String,
    ) {
        gitActionBusy = true
        gitActionError = null
        val progressId = UUID.randomUUID().toString()
        val wireAction = submission.nextStep.toStackedWireAction()
        configureGitStackedProgressUi(
            wireAction = wireAction,
            step = submission.nextStep,
            onConfigure = { message, includesPush, includesPr, phase ->
                gitActionProgressMessage = message
                gitActionProgressIncludesPush = includesPush
                gitActionProgressIncludesPullRequest = includesPr
                gitActionProgressPhase = phase
            },
        )
        val progressJob =
            scope.launch {
                repository.gitStackedActionProgress.collect { event ->
                    if (event.progressId == progressId) {
                        applyGitStackedProgressEvent(
                            event = event,
                            onPhase = { gitActionProgressPhase = it },
                            currentPhase = gitActionProgressPhase,
                        )
                    }
                }
            }
        try {
            withTimeout(GIT_OPERATION_TIMEOUT_MS) {
                val git = GitActionsService(repository, cwd)
                val commitMessage =
                    if (submission.nextStep.involvesCommit()) {
                        gitActionProgressPhase = GitActionProgressPhase.resolvingCommitMessage
                        resolveCommitMessage(git, submission.commitMessage)
                    } else {
                        null
                    }
                val result =
                    git.runStackedAction(
                        action = wireAction,
                        commitMessage = commitMessage,
                        baseBranch =
                            submission.baseBranch
                                .trim()
                                .takeIf { submission.nextStep.involvesNativePullRequest() },
                        progressId = progressId,
                    )
                result.status?.let { snapshot ->
                    repoStatusSnapshot = snapshot
                    repoDiffTotals = snapshot.workingTreeDiffTotals
                }
                if (submission.nextStep.involvesNativePullRequest() && result.pullRequest != null) {
                    AppContainer.betaEngagementRepository.recordMissionEvent(
                        eventType = "mobile_pr_draft_opened",
                        screen = "git",
                        refreshAfter = false,
                    )
                }
                gitActionProgressMessage =
                    formatGitStackedActionSuccessMessage(
                        context = context,
                        step = submission.nextStep,
                        result = result,
                    )
                gitActionProgressPhase = GitActionProgressPhase.done
            }
            gitActionError = null
            gitActionSheetMode = null
            gitActionSheetInitialNextStep = null
        } catch (e: Throwable) {
            if (shouldFallbackPullRequestToBrowser(e, submission.nextStep)) {
                try {
                    val git = GitActionsService(repository, cwd)
                    openPullRequestUrl(git, submission)
                    gitActionProgressMessage =
                        context.getString(R.string.git_pr_opened_browser_fallback)
                    gitActionProgressPhase = GitActionProgressPhase.done
                    AppContainer.betaEngagementRepository.recordMissionEvent(
                        eventType = "mobile_pr_draft_opened",
                        screen = "git",
                        refreshAfter = false,
                    )
                    gitActionError = null
                    gitActionSheetMode = null
                    gitActionSheetInitialNextStep = null
                } catch (fallbackError: Throwable) {
                    handleGitActionFailure(fallbackError) { showNothingToCommit = true }
                }
            } else {
                handleGitActionFailure(e) { showNothingToCommit = true }
            }
        } finally {
            progressJob.cancel()
            gitActionBusy = false
            gitToolbarRefreshNonce++
        }
    }

    LaunchedEffect(gitActionProgressMessage, gitActionProgressPhase, gitActionBusy) {
        val message = gitActionProgressMessage
        val phase = gitActionProgressPhase
        if (!gitActionBusy && (!message.isNullOrBlank() || phase == GitActionProgressPhase.done)) {
            delay(3_500)
            if (gitActionProgressMessage == message && gitActionProgressPhase == phase) {
                gitActionProgressMessage = null
                gitActionProgressPhase = null
            }
        }
    }

    fun continuePendingGitOperation(commitFirst: Boolean = false) {
        val pending = pendingGitOperation ?: return
        gitSyncAlert = null
        pendingGitOperation = null
        scope.launch {
            gitActionBusy = true
            gitActionError = null
            try {
                withTimeout(GIT_OPERATION_TIMEOUT_MS) {
                    val git = GitActionsService(repository, pending.cwd)
                    if (commitFirst) {
                        git.commit("WIP before continuing")
                    }
                    val submission = pending.submission
                    if (submission != null) {
                        gitActionBusy = false
                        executeGitActionSheetNow(submission, pending.cwd)
                    } else if (pending.operation is TurnGitPreflightOperation.CreateManagedWorktree) {
                        gitActionBusy = false
                        val tid = activeThreadId ?: return@withTimeout
                        val baseBranch = pending.operation.baseBranch
                        handingOffWorktree = true
                        worktreeHandoffError = null
                        try {
                            val outcome =
                                WorktreeFlowCoordinator(repository).handoffThreadToWorktree(
                                    threadId = tid,
                                    sourceProjectPath = pending.cwd,
                                    associatedWorktreePath = associatedWorktreePath,
                                    baseBranchForNewWorktree = baseBranch,
                                )
                            when (outcome) {
                                is WorktreeFlowHandoffOutcome.Moved -> repository.setActiveThreadId(outcome.move.thread.id)
                                WorktreeFlowHandoffOutcome.MissingAssociatedWorktree ->
                                    worktreeHandoffError =
                                        "The associated worktree is no longer available. Open the thread to create a new managed worktree."
                            }
                        } catch (e: Throwable) {
                            worktreeHandoffError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                        } finally {
                            handingOffWorktree = false
                        }
                    }
                }
            } catch (e: Throwable) {
                handleGitActionFailure(e) { showNothingToCommit = true }
            } finally {
                if (gitActionBusy) {
                    gitActionBusy = false
                    gitToolbarRefreshNonce++
                }
            }
        }
    }

    fun pullRebaseForPendingGitOperation() {
        val cwd = pendingGitOperation?.cwd ?: gitCwd ?: return
        gitSyncAlert = null
        pendingGitOperation = null
        scope.launch {
            gitActionBusy = true
            gitActionError = null
            runCatching {
                withTimeout(GIT_OPERATION_TIMEOUT_MS) {
                    val result = GitActionsService(repository, cwd).pull()
                    result.status?.let {
                        repoStatusSnapshot = it
                        repoDiffTotals = it.workingTreeDiffTotals
                    }
                }
            }.onFailure { e ->
                handleGitActionFailure(e) { showNothingToCommit = true }
            }
            gitActionBusy = false
            gitToolbarRefreshNonce++
        }
    }

    fun discardRuntimeChangesForPendingGitOperation() {
        val cwd = pendingGitOperation?.cwd ?: gitCwd ?: return
        gitSyncAlert = null
        pendingGitOperation = null
        scope.launch {
            gitActionBusy = true
            gitActionError = null
            runCatching {
                withTimeout(GIT_OPERATION_TIMEOUT_MS) {
                    val result = GitActionsService(repository, cwd).resetToRemoteDiscardRuntime()
                    result.status?.let {
                        repoStatusSnapshot = it
                        repoDiffTotals = it.workingTreeDiffTotals
                    }
                }
            }.onFailure { e ->
                handleGitActionFailure(e) { showNothingToCommit = true }
            }
            gitActionBusy = false
            gitToolbarRefreshNonce++
        }
    }

    fun handoffCurrentThreadWorktree(skipPreflight: Boolean = false) {
        val tid = activeThreadId ?: return
        val cwd = gitCwd ?: return
        if (!showGitControls || isActiveTurnRunning || handingOffWorktree) return
        val baseBranch =
            defaultGitBaseBranch?.trim()?.takeIf { it.isNotEmpty() }
                ?: branchesWithStatusSnapshot?.currentBranch?.trim()?.takeIf { it.isNotEmpty() }
        val localTarget = localWorktreeHandoffTargetPath
        if (isWorktreeProject && localTarget == null) {
            gitSyncAlert =
                TurnGitSyncAlert.withDefaultButtons(
                    title = "Worktree handoff unavailable",
                    message = "Could not resolve the paired Local checkout for this worktree.",
                    action = TurnGitSyncAlertAction.dismissOnly,
                )
            return
        }
        if (!isWorktreeProject && associatedWorktreePath == null && baseBranch == null) {
            gitSyncAlert =
                TurnGitSyncAlert.withDefaultButtons(
                    title = "Worktree handoff unavailable",
                    message = "Could not determine a base branch for the managed worktree.",
                    action = TurnGitSyncAlertAction.dismissOnly,
                )
            return
        }

        val preflightBranches = branchesWithStatusSnapshot
        if (
            !skipPreflight &&
            !isWorktreeProject &&
            associatedWorktreePath == null &&
            preflightBranches != null &&
            baseBranch != null
        ) {
            val alert =
                TurnGitPreflightPolicy.alertFor(
                    status = repoStatusSnapshot ?: preflightBranches.status,
                    branches = preflightBranches,
                    operation =
                        TurnGitPreflightOperation.createManagedWorktree(
                            baseBranch = baseBranch,
                            changeTransfer = GitWorktreeChangeTransferMode.move,
                        ),
                )
            if (alert != null) {
                pendingGitOperation =
                    PendingGitOperation(
                        operation =
                            TurnGitPreflightOperation.createManagedWorktree(
                                baseBranch = baseBranch,
                                changeTransfer = GitWorktreeChangeTransferMode.move,
                            ),
                        cwd = cwd,
                    )
                gitSyncAlert = alert
                return
            }
        }

        scope.launch {
            handingOffWorktree = true
            worktreeHandoffError = null
            try {
                val coordinator = WorktreeFlowCoordinator(repository)
                val outcome =
                    if (isWorktreeProject) {
                        coordinator.handoffThreadToProjectPath(
                            threadId = tid,
                            sourceProjectPath = cwd,
                            targetProjectPath = localTarget ?: error("Missing local checkout path."),
                        )
                    } else {
                        coordinator.handoffThreadToWorktree(
                            threadId = tid,
                            sourceProjectPath = cwd,
                            associatedWorktreePath = associatedWorktreePath,
                            baseBranchForNewWorktree = baseBranch,
                        )
                    }
                when (outcome) {
                    is WorktreeFlowHandoffOutcome.Moved -> {
                        repository.setActiveThreadId(outcome.move.thread.id)
                        gitToolbarRefreshNonce++
                    }
                    WorktreeFlowHandoffOutcome.MissingAssociatedWorktree ->
                        worktreeHandoffError =
                            "The associated worktree is no longer available. Open the thread to create a new managed worktree."
                }
            } catch (e: Throwable) {
                worktreeHandoffError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            } finally {
                handingOffWorktree = false
            }
        }
    }

    fun handleGitAction(action: TurnGitActionKind) {
        if (action == TurnGitActionKind.viewRepositoryDiff) {
            if (repoDiffTotals?.hasChanges == true && showGitControls) {
                openRepoDiffSheetFromHeader()
            }
            return
        }
        val cwd = gitCwd ?: return
        if (action == TurnGitActionKind.discardRuntimeChangesAndSync) {
            enqueueGitPreflightIfNeeded(cwd, TurnGitPreflightOperation.discardRuntimeChanges)
            return
        }
        if (!showGitControls || isActiveTurnRunning) return
        when (action) {
            TurnGitActionKind.commit -> {
                gitActionSheetMode = GitActionSheetMode.commit
                gitActionSheetInitialNextStep = GitActionNextStep.commit
                return
            }
            TurnGitActionKind.push -> {
                gitActionSheetMode = GitActionSheetMode.push
                gitActionSheetInitialNextStep = GitActionNextStep.push
                return
            }
            TurnGitActionKind.commitAndPush -> {
                gitActionSheetMode = GitActionSheetMode.commit
                gitActionSheetInitialNextStep = GitActionNextStep.commitAndPush
                return
            }
            TurnGitActionKind.createPR -> {
                gitActionSheetMode = GitActionSheetMode.createPullRequest
                gitActionSheetInitialNextStep = GitActionNextStep.createPullRequest
                return
            }
            TurnGitActionKind.commitPushAndPullRequest -> {
                gitActionSheetMode = GitActionSheetMode.commit
                gitActionSheetInitialNextStep = GitActionNextStep.commitPushAndPullRequest
                return
            }
            else -> Unit
        }
        scope.launch {
            gitActionBusy = true
            gitActionError = null
            try {
                withTimeout(GIT_OPERATION_TIMEOUT_MS) {
                    val git = GitActionsService(repository, cwd)
                    when (action) {
                        TurnGitActionKind.syncNow -> {
                            val s = git.status()
                            repoStatusSnapshot = s
                            repoDiffTotals = s.workingTreeDiffTotals
                            if (
                                enqueueGitPreflightIfNeeded(
                                    cwd = cwd,
                                    operation = TurnGitPreflightOperation.syncUpdate,
                                    status = s,
                                )
                            ) {
                                return@withTimeout
                            }
                            when (s.state) {
                                "behind_only" -> git.pull()
                                else -> Unit
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (e: Throwable) {
                handleGitActionFailure(e) { showNothingToCommit = true }
            } finally {
                gitActionBusy = false
                gitToolbarRefreshNonce++
            }
        }
    }

    fun executeGitActionSheet(submission: GitActionSheetSubmission) {
        val cwd = gitCwd ?: return
        if (!showGitControls || isActiveTurnRunning || gitActionBusy) return
        if (enqueueGitPreflightIfNeeded(cwd, operationForSubmission(submission), submission)) return
        scope.launch {
            executeGitActionSheetNow(submission, cwd)
        }
    }

    LaunchedEffect(activeThreadId, ready, gitCwd, isActiveTurnRunning, gitToolbarRefreshNonce) {
        if (!FeatureFlags.gitIntegrationEnabled) return@LaunchedEffect
        gitStatusFailed = false
        if (!ready) {
            repoStatusSnapshot = null
            branchesWithStatusSnapshot = null
            repoDiffTotals = null
            defaultGitBaseBranch = null
            isLoadingRepoDiff = false
            return@LaunchedEffect
        }
        val tid = activeThreadId
        if (tid.isNullOrBlank()) {
            repoStatusSnapshot = null
            branchesWithStatusSnapshot = null
            repoDiffTotals = null
            defaultGitBaseBranch = null
            isLoadingRepoDiff = false
            return@LaunchedEffect
        }
        val cwd = gitCwd
        if (cwd == null) {
            repoStatusSnapshot = null
            branchesWithStatusSnapshot = null
            repoDiffTotals = null
            defaultGitBaseBranch = null
            isLoadingRepoDiff = false
            return@LaunchedEffect
        }
        repoStatusSnapshot = null
        branchesWithStatusSnapshot = null
        repoDiffTotals = null
        defaultGitBaseBranch = null
        isLoadingRepoDiff = true
        try {
            val branches = loadGitToolbarStatus(cwd) {
                GitActionsService(repository, cwd).branchesWithStatus()
            }
            currentCoroutineContext().ensureActive()
            repoStatusSnapshot = branches.status
            branchesWithStatusSnapshot = branches
            repoDiffTotals = branches.status?.workingTreeDiffTotals
            defaultGitBaseBranch = branches.defaultBranch
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            gitStatusFailed = true
            Log.w("RemodexGit", "operation=git/branchesWithStatus stage=toolbar attempt=1 thread=$tid cwd=$cwd", error)
        } finally {
            if (currentCoroutineContext().isActive) isLoadingRepoDiff = false
        }
    }

    LaunchedEffect(drawerState, ready, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { drawerState.isOpen }.collectLatest { open ->
                if (open && ready) {
                    while (currentCoroutineContext().isActive) {
                        try {
                            repository.refreshThreads()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The service records the original failure. Keep the cached list;
                            // a new visibility/connection cycle or manual refresh can try again.
                            return@collectLatest
                        }
                        // Reconcile missed notifications without reading conversation history.
                        delay(10_000L)
                    }
                }
            }
        }
    }

    DisposableEffect(navController, drawerState, scope) {
        val listener =
            androidx.navigation.NavController.OnDestinationChangedListener { _, _, _ ->
                if (drawerState.isOpen) {
                    scope.launch { drawerState.moveToSessionDrawer(DrawerValue.Closed) }
                }
            }
        navController.addOnDestinationChangedListener(listener)
        onDispose {
            navController.removeOnDestinationChangedListener(listener)
        }
    }

    // The conversation is the root route; Back should reveal the chat list.
    // Register before child content so dialogs and nested navigation take priority.
    BackHandler(enabled = showShellHeader && activeThreadId != null && drawerState.isClosed) {
        scope.launch { drawerState.moveToSessionDrawer(DrawerValue.Open) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            val drawerWidth = (LocalConfiguration.current.screenWidthDp.dp * 0.90f).coerceAtMost(360.dp)
            ModalDrawerSheet(
                modifier =
                    Modifier
                        .width(drawerWidth)
                        .fillMaxHeight(),
                drawerContainerColor = sidebarColors.background,
                drawerContentColor = sidebarColors.primaryText,
            ) {
                if (pendingShare != null) {
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.share_choose_thread), Modifier.weight(1f))
                        TextButton(onClick = { shareInbox?.take(pendingShare.id) }) { Text(stringResource(R.string.auth_profiles_cancel)) }
                    }
                }
                SidebarDrawerContent(
                    repository = repository,
                    navController = navController,
                    drawerScope = scope,
                    drawerState = drawerState,
                    onOpenPairingScanner = onOpenPairingScanner,
                    onReconnectSavedPairing = viewModel::reconnectSavedPairingManually,
                    onWakeSavedComputer = viewModel::wakeSavedComputerDisplay,
                    closeDrawer = { drawerState.moveToSessionDrawer(DrawerValue.Closed) },
                    sessionReady = ready,
                    connectionState = connectionState,
                    reconnectUiState = reconnectUiState,
                    activeChatMetadata = sidebarActiveChatMetadata,
                )
            }
        },
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = if (showShellHeader) MaterialTheme.colorScheme.conversationColors().reading else MaterialTheme.colorScheme.background,
            topBar = {
                if (showShellHeader) {
                    ConversationHeader(
                        onOpenSettings = { navController.navigate(AppRoutes.Settings) { launchSingleTop = true } },
                        title = activeThreadTitle,
                        threadId = activeThreadId,
                        environment = environment.content,
                        refreshingConversation = refreshingConversation,
                        onRefreshConversation = activeThreadId?.let { tid ->
                            {
                                if (!refreshingConversation) {
                                    refreshingConversation = true
                                    conversationRefreshError = null
                                    conversationRefreshJob = scope.launch {
                                        try {
                                            repository.refreshConversation(tid)
                                            val revision = repository.threadHistoryPaginationByThread.value[tid]?.initialLoadRevision ?: 0L
                                            try {
                                                com.remodex.mobile.ui.turn.awaitHistoryPresentation(
                                                    androidx.compose.runtime.snapshotFlow { historyPresentationReceipt }, tid, revision,
                                                    renderingEnabled = kotlinx.coroutines.flow.combine(
                                                        androidx.compose.runtime.snapshotFlow { timelineRenderingEnabled },
                                                        lifecycleOwner.lifecycle.currentStateFlow,
                                                    ) { enabled, lifecycle -> enabled && lifecycle.isAtLeast(Lifecycle.State.STARTED) },
                                                )
                                            } catch (failure: java.util.concurrent.TimeoutException) {
                                                android.util.Log.e("RemodexHistoryUI", "operation=manual-refresh thread=$tid stage=history-presentation attempt=1", failure)
                                                throw failure
                                            }
                                            android.widget.Toast.makeText(
                                                context, R.string.conversation_refreshed, android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            conversationRefreshError = failure.message ?: failure.javaClass.name
                                        } finally {
                                            refreshingConversation = false
                                        }
                                    }
                                }
                            }
                        },
                        pathSubtitle = pathSubtitle,
                        onPathClick =
                            threadPathFull?.let {
                                { showPathDialog = true }
                            },
                        running = isActiveTurnRunning,
                        repoDiffTotals = repoDiffTotals,
                        isLoadingRepoDiff = isLoadingRepoDiff,
                        canViewRepoDiff =
                            repoDiffTotals?.hasChanges == true &&
                                showGitControls &&
                                !isLoadingRepoDiff,
                        showGitActions = showGitControls,
                        onGitAction = { handleGitAction(it) },
                        gitActionsBusy = gitActionBusy,
                        showsDiscardRuntimeRecovery = repoStatusSnapshot?.isDirty == true,
                        isGitActionEnabled = showGitControls && !isActiveTurnRunning,
                        showDesktopHandoff = showDesktopHandoff,
                        handingOffToDesktop = handingOffToDesktop,
                        showWorktreeHandoff = showWorktreeHandoff,
                        handingOffWorktree = handingOffWorktree,
                        isWorktreeProject = isWorktreeProject,
                        onOpenDrawer = {
                            scope.launch { drawerState.moveToSessionDrawer(DrawerValue.Open) }
                        },
                        onContinueDesktop = {
                            val tid = activeThreadId
                            if (tid != null) {
                                handingOffToDesktop = true
                                desktopHandoffError = null
                                scope.launch {
                                    runCatching {
                                        DesktopHandoffService(repository).continueOnDesktop(tid)
                                    }.onFailure { error ->
                                        desktopHandoffError =
                                            error.message ?: handoffFallbackMessage
                                    }
                                    handingOffToDesktop = false
                                }
                            }
                        },
                        onWorktreeHandoff = { handoffCurrentThreadWorktree() },
                    )
                }
            },
            contentWindowInsets =
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
        ) { innerPadding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding).consumeWindowInsets(innerPadding),
            ) {
                CompositionLocalProvider(
                    com.remodex.mobile.LocalChooseShareThread provides { scope.launch { drawerState.moveToSessionDrawer(DrawerValue.Open) }; Unit },
                    com.remodex.mobile.ui.turn.LocalSessionEnvironment provides environment,
                    LocalOpenRepoDiffForMarkdownLink provides ::openRepoDiffSheetFromMarkdown,
                    com.remodex.mobile.ui.turn.LocalConversationVisible provides (drawerState.isClosed && !drawerState.isAnimationRunning),
                    com.remodex.mobile.ui.turn.LocalTimelineRenderingEnabled provides timelineRenderingEnabled,
                    com.remodex.mobile.ui.turn.LocalHistoryPresentationReceipt provides { receipt ->
                        if (receipt.threadId == activeThreadId && receipt.revision >= (historyPresentationReceipt?.revision ?: 0L)) {
                            historyPresentationReceipt = receipt
                        }
                    },
                ) {
                    AppNavHost(
                        navController = navController,
                        repository = repository,
                        reconnectUiState = reconnectUiState,
                        onReconnectSavedPairing = viewModel::reconnectSavedPairingManually,
                        onWakeSavedComputer = viewModel::wakeSavedComputerDisplay,
                        onOpenPairingScanner = onOpenPairingScanner,
                        onOpenPairingCode = onOpenPairingCode,
                        onGitContextChanged = { gitToolbarRefreshNonce++ },
                        modifier =
                            Modifier
                                .fillMaxSize(),
                    )
                }
                if (showShellHeader) {
                    ThreadCompletionBanner(
                        bannerMessage = gitToastMessage,
                        gitProgress = gitProgressToast,
                        onTap = { if (gitStatusFailed) gitToolbarRefreshNonce++ },
                        onDismiss = {
                            gitStatusFailed = false
                            if (gitActionProgressMessage != null) {
                                gitActionProgressMessage = null
                            }
                            if (gitActionProgressPhase != null) {
                                gitActionProgressPhase = null
                            }
                        },
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp),
                    )
                }
                PetCompanionOverlay(
                    repository = repository,
                    store = AppContainer.petCompanionStore,
                    isInteractionEnabled = currentRoute == AppRoutes.Home,
                    bottomExclusionHeight = if (showShellHeader) 120.dp else 16.dp,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showPathDialog) {
        val fullPath = threadPathFull
        if (fullPath != null) {
            RemodexAlertDialog(
                onDismissRequest = { showPathDialog = false },
                title = { Text(stringResource(R.string.turn_thread_path_dialog_title)) },
                text = {
                    SelectionContainer {
                        Text(
                            text = fullPath,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipData.newPlainText("thread-path", fullPath).toClipEntry(),
                                )
                                showPathDialog = false
                            }
                        },
                    ) {
                        Text(stringResource(R.string.turn_thread_path_copy))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPathDialog = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }

    GitRepoDiffBottomSheet(
        visible = showRepoDiffSheet,
        scope = repoDiffSheetScope,
        onScopeChange = { repoDiffSheetScope = it },
        lastTurnRows = repoDiffSheetLastTurnRows,
        fullTreePatch = repoDiffSheetFullPatch,
        isFullTreeLoading = repoDiffSheetFullLoading,
        fullTreeError = repoDiffSheetFullError,
        gitStatus = repoStatusSnapshot,
        focusPathQuery = repoDiffMarkdownFocusQuery,
        onFocusPathQueryConsumed = { repoDiffMarkdownFocusQuery = null },
        onDismiss = {
            showRepoDiffSheet = false
            repoDiffMarkdownFocusQuery = null
        },
    )

    workspaceTextFilePreviewRequest?.let { request ->
        val liveCwd = activeThread?.cwd?.trim()?.takeIf { it.isNotEmpty() } ?: gitCwd
        val effectiveRequest =
            if (request.cwd.isNullOrBlank() && !liveCwd.isNullOrBlank()) {
                request.copy(cwd = liveCwd)
            } else {
                request
            }
        WorkspaceTextFilePreviewDialog(
            request = effectiveRequest,
            service = workspaceTextFileService,
            onDismiss = { workspaceTextFilePreviewRequest = null },
        )
    }

    GitActionBottomSheet(
        visible = gitActionSheetMode != null,
        mode = gitActionSheetMode,
        initialNextStep = gitActionSheetInitialNextStep,
        status = repoStatusSnapshot,
        defaultBaseBranch = defaultGitBaseBranch,
        isBusy = gitActionBusy,
        onDismiss = {
            gitActionSheetMode = null
            gitActionSheetInitialNextStep = null
        },
        onSubmit = { executeGitActionSheet(it) },
    )

    if (showNothingToCommit) {
        RemodexAlertDialog(
            onDismissRequest = { showNothingToCommit = false },
            title = { Text(stringResource(R.string.git_action_section_write)) },
            text = { Text(stringResource(R.string.git_nothing_to_commit)) },
            confirmButton = {
                TextButton(onClick = { showNothingToCommit = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    gitSyncAlert?.let { alert ->
        fun dismissGitAlert() {
            gitSyncAlert = null
            pendingGitOperation = null
        }

        RemodexAlertDialog(
            onDismissRequest = { dismissGitAlert() },
            title = { Text(alert.title) },
            text = { Text(alert.message) },
            confirmButton = {
                Column {
                    alert.buttons
                        .filter { it.role != TurnGitSyncAlertButtonRole.cancel }
                        .forEach { button ->
                            TextButton(
                                onClick = {
                                    when (button.action) {
                                        TurnGitSyncAlertAction.dismissOnly -> dismissGitAlert()
                                        TurnGitSyncAlertAction.pullRebase -> pullRebaseForPendingGitOperation()
                                        TurnGitSyncAlertAction.continuePendingGitOperation,
                                        TurnGitSyncAlertAction.continueGitBranchOperation,
                                        -> continuePendingGitOperation()
                                        TurnGitSyncAlertAction.commitAndContinuePendingGitOperation,
                                        TurnGitSyncAlertAction.commitAndContinueGitBranchOperation,
                                        -> continuePendingGitOperation(commitFirst = true)
                                        TurnGitSyncAlertAction.discardRuntimeChanges ->
                                            discardRuntimeChangesForPendingGitOperation()
                                    }
                                },
                            ) {
                                Text(
                                    text = button.title,
                                    color =
                                        if (button.role == TurnGitSyncAlertButtonRole.destructive) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.primary
                                        },
                                )
                            }
                        }
                    if (alert.buttons.none { it.role != TurnGitSyncAlertButtonRole.cancel }) {
                        TextButton(onClick = { dismissGitAlert() }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    }
                }
            },
            dismissButton = {
                val cancel = alert.buttons.firstOrNull { it.role == TurnGitSyncAlertButtonRole.cancel }
                if (cancel != null && alert.buttons.size > 1) {
                    TextButton(onClick = { dismissGitAlert() }) {
                        Text(cancel.title)
                    }
                }
            },
        )
    }

    gitActionError?.let { err ->
        RemodexAlertDialog(
            onDismissRequest = { gitActionError = null },
            title = { Text(stringResource(R.string.git_error_title)) },
            text = { Text(err) },
            confirmButton = {
                TextButton(onClick = { gitActionError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    conversationRefreshError?.let { error ->
        RemodexAlertDialog(
            onDismissRequest = { conversationRefreshError = null },
            title = { Text(stringResource(R.string.conversation_refresh_failed)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { conversationRefreshError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (desktopHandoffError != null) {
        RemodexAlertDialog(
            onDismissRequest = { desktopHandoffError = null },
            title = { Text(stringResource(R.string.turn_open_desktop_error_title)) },
            text = { Text(desktopHandoffError ?: "") },
            confirmButton = {
                TextButton(onClick = { desktopHandoffError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (worktreeHandoffError != null) {
        RemodexAlertDialog(
            onDismissRequest = { worktreeHandoffError = null },
            title = { Text(stringResource(R.string.worktree_handoff_failed)) },
            text = { Text(worktreeHandoffError ?: "") },
            confirmButton = {
                TextButton(onClick = { worktreeHandoffError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    pendingApprovalRequest?.let { request ->
        val supportsSession = PendingRequestPresentation.supportsAcceptForSession(request.method)
        var replyError by remember(request.id) { mutableStateOf<String?>(null) }
        fun resolve(decision: PendingApprovalDecision) {
            scope.launch {
                try { repository.resolvePendingApproval(request.id, decision) }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (failure: Exception) {
                    android.util.Log.e("RemodexApproval", "reply id=${request.id}", failure)
                    replyError = failure.message ?: failure.javaClass.name
                }
            }
        }
        RemodexAlertDialog(
            onDismissRequest = { resolve(PendingApprovalDecision.Decline) },
            title = { Text(stringResource(R.string.approval_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    replyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    request.permissions?.let { permissions ->
                        Text(com.remodex.mobile.core.model.JSONValue.toJsonElement(permissions).toString(),
                            modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
                    }
                    val cmdLine =
                        request.command
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { c -> stringResource(R.string.approval_command_line, c) }
                    val message =
                        PendingRequestPresentation.approvalMessageOrNull(
                            request.reason,
                            cmdLine,
                        )
                    Text(
                        text = message ?: stringResource(R.string.approval_default_body),
                    )
                    Text(
                        text = stringResource(PendingRequestPresentation.approvalKindTitleRes(request.method)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                if (supportsSession) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = { resolve(PendingApprovalDecision.Accept) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.approval_approve))
                        }
                        TextButton(
                            onClick = { resolve(PendingApprovalDecision.AcceptForSession) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.approval_approve_for_session))
                        }
                        TextButton(
                            onClick = { resolve(PendingApprovalDecision.Decline) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.approval_decline))
                        }
                    }
                } else {
                    TextButton(onClick = { resolve(PendingApprovalDecision.Accept) }) {
                        Text(stringResource(R.string.approval_approve))
                    }
                }
            },
            dismissButton = {
                if (!supportsSession) {
                    TextButton(onClick = { resolve(PendingApprovalDecision.Decline) }) {
                        Text(stringResource(R.string.approval_decline))
                    }
                }
            },
        )
    }


    BridgeUpdateSheet(
        visible = bridgeUpdatePrompt != null,
        title = bridgeUpdatePrompt?.title.orEmpty(),
        message = bridgeUpdatePrompt?.message.orEmpty(),
        installCommand = bridgeUpdatePrompt?.command,
        onDismiss = { repository.dismissBridgeUpdatePrompt() },
        onRetry = { viewModel.retryBridgeConnectionAfterUpdate() },
        onScanNewQr = {
            repository.dismissBridgeUpdatePrompt()
            onOpenPairingScanner()
        },
    )
}

private data class PendingGitOperation(
    val operation: TurnGitPreflightOperation,
    val cwd: String,
    val submission: GitActionSheetSubmission? = null,
)

private fun String.withoutGitLineEndingWarnings(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .filterNot { it.isGitLineEndingWarning() }
        .joinToString("\n")

private fun String.isGitLineEndingWarning(): Boolean =
    startsWith("warning: in the working copy of ") &&
        contains("LF will be replaced by CRLF the next time Git touches it")

private fun configureGitStackedProgressUi(
    wireAction: String,
    step: GitActionNextStep,
    onConfigure: (
        message: String?,
        includesPush: Boolean,
        includesPullRequest: Boolean,
        phase: GitActionProgressPhase?,
    ) -> Unit,
) {
    onConfigure(
        null,
        wireAction != "commit",
        step.involvesNativePullRequest(),
        when {
            step.involvesCommit() -> null
            else -> GitActionProgressPhase.pushing
        },
    )
}

private fun applyGitStackedProgressEvent(
    event: GitStackedActionProgressEvent,
    onPhase: (GitActionProgressPhase?) -> Unit,
    currentPhase: GitActionProgressPhase?,
) {
    if (event.status != "started") return
    onPhase(
        when (event.phase) {
            "commit" -> GitActionProgressPhase.committing
            "push" -> GitActionProgressPhase.pushing
            "createPR" -> GitActionProgressPhase.preparingPullRequest
            else -> currentPhase
        },
    )
}

private fun shouldFallbackPullRequestToBrowser(
    error: Throwable,
    step: GitActionNextStep,
): Boolean {
    if (!step.involvesNativePullRequest()) return false
    val code = (error as? GitActionsError.BridgeFailure)?.errorCode ?: return false
    return code == "github_cli_unavailable" || code == "github_cli_unauthenticated"
}

private fun formatGitStackedActionSuccessMessage(
    context: android.content.Context,
    step: GitActionNextStep,
    result: GitRunStackedActionResult,
): String {
    result.pullRequest?.let { pr ->
        val number = pr.number
        return when (pr.status) {
            "created" ->
                if (number != null) {
                    context.getString(R.string.git_pr_created, number)
                } else {
                    pr.title.ifBlank { "Pull request created." }
                }
            "opened_existing" ->
                if (number != null) {
                    context.getString(R.string.git_pr_opened_existing, number)
                } else {
                    pr.title.ifBlank { "Opened existing pull request." }
                }
            else -> pr.title
        }
    }
    return when (step) {
        GitActionNextStep.commit -> context.getString(R.string.git_stacked_commit_done)
        GitActionNextStep.push,
        GitActionNextStep.commitAndPush,
        GitActionNextStep.commitPushAndPullRequest,
        GitActionNextStep.pushAndPullRequest,
        GitActionNextStep.createPullRequest,
        -> context.getString(R.string.git_stacked_push_done)
    }
}
