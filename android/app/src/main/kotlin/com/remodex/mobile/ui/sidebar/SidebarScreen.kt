package com.remodex.mobile.ui.sidebar
import com.remodex.mobile.ui.theme.singleOwnerPageEntry

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import android.util.Log
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Surface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PushPin
import com.remodex.mobile.ui.shared.DialogConfirmButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.R
import com.remodex.mobile.core.config.FeatureFlags
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexThreadSyncState
import com.remodex.mobile.core.model.GitWorktreeChangeTransferMode
import com.remodex.mobile.core.model.TurnGitPreflightOperation
import com.remodex.mobile.core.model.TurnGitPreflightPolicy
import com.remodex.mobile.core.model.TurnGitSyncAlert
import com.remodex.mobile.core.model.TurnGitSyncAlertAction
import com.remodex.mobile.core.model.TurnGitSyncAlertButtonRole
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.data.GitBranchDisplayMapper
import com.remodex.mobile.data.WorktreeFlowCoordinator
import com.remodex.mobile.data.WorktreeNewChatDefaults
import com.remodex.mobile.data.loadGitBranchesWithStatus
import com.remodex.mobile.ui.draft.NewChatDraftSource
import com.remodex.mobile.ui.shared.ThreadRenameDialog
import com.remodex.mobile.ui.theme.RemodexDropdownMenu
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val SIDEBAR_THREADS_PER_GROUP = 5
private val SidebarFloatingBarBottomPadding = 12.dp

@Composable
fun SidebarScreen(
    repository: CodexRepository,
    activeChatMetadata: SidebarActiveChatMetadata? = null,
    onOpenArchivedChats: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenPairingScanner: () -> Unit = {},
    onOpenMyDevices: () -> Unit = {},
    onOpenNewChatDraft: (NewChatDraftSource, String?) -> Unit = { _, _ -> },
    onThreadSelected: suspend () -> Unit = {},
    modifier: Modifier = Modifier,
    dotAnimationsEnabled: Boolean = true,
    drawerOpen: Boolean = true,
    onCloseDrawer: (() -> Unit)? = null,
) {
    val threads by repository.threads.collectAsStateWithLifecycle()
    val pendingQuestions by repository.pendingStructuredInputRequests.collectAsStateWithLifecycle()
    val questionThreads by repository.questionThreadIds.collectAsStateWithLifecycle()
    val activeId by repository.activeThreadId.collectAsStateWithLifecycle()
    val conn by repository.connectionState.collectAsStateWithLifecycle()
    val ready by repository.isSessionReady.collectAsStateWithLifecycle()
    val runningTurnByThread by repository.runningTurnIdByThread.collectAsStateWithLifecycle()
    val protectedRunningFallback by repository.protectedRunningFallbackThreadIds.collectAsStateWithLifecycle()
    val pinDeviceId by repository.currentTrustedMacDeviceId.collectAsStateWithLifecycle()
    val activityState by repository.sidebarActivity.collectAsStateWithLifecycle()
    val activity = activityState.takeIf { it.deviceId == pinDeviceId }
    val recentIds = activity?.recentThreadIds.orEmpty()
    var listTab by rememberSaveable(pinDeviceId) { mutableStateOf(SidebarListTab.Home) }
    var projectId by rememberSaveable(pinDeviceId) { mutableStateOf<String?>(null) }
    val homeList = rememberLazyListState()
    val projectList = rememberLazyListState()
    val detailList = androidx.compose.runtime.key(projectId) { rememberLazyListState() }
    var searching by rememberSaveable(pinDeviceId) { mutableStateOf(false) }
    var query by rememberSaveable(pinDeviceId) { mutableStateOf("") }
    fun closeSearch() { query = ""; searching = false }
    val inProject = listTab == SidebarListTab.Projects && projectId != null
    // Register on opening so the overlay handles Back before the page underneath.
    if (drawerOpen) {
        BackHandler(enabled = searching || inProject || onCloseDrawer != null) {
            when {
                searching -> closeSearch()
                inProject -> projectId = null
                else -> onCloseDrawer?.invoke()
            }
        }
    }
    val searchResults = rememberLazyListState()
    val listState = when {
        searching -> searchResults
        inProject -> detailList
        listTab == SidebarListTab.Projects -> projectList
        else -> homeList
    }
    val animateRunningRows by remember(listState, dotAnimationsEnabled, runningTurnByThread, protectedRunningFallback) {
        derivedStateOf {
            dotAnimationsEnabled && !listState.isScrollInProgress &&
                (runningTurnByThread.isNotEmpty() || protectedRunningFallback.isNotEmpty()) &&
                listState.layoutInfo.visibleItemsInfo.any { item ->
                    val threadId = (item.key as? String)?.substringAfter("/thread/", "")
                    threadId in runningTurnByThread || threadId in protectedRunningFallback
                }
        }
    }
    val scope = rememberCoroutineScope()
    var questionDialogThreadId by remember(pinDeviceId) { mutableStateOf<String?>(null) }
    com.remodex.mobile.ui.turn.ConversationQuestionDialog(questionDialogThreadId.orEmpty(), questionDialogThreadId != null,
        onDismiss = { questionDialogThreadId = null })
    val context = LocalContext.current
    val sidebarColors = rememberSidebarColorPalette()
    val pinnedIds = activity?.pinnedThreadIds.orEmpty()
    val pinsLoaded = ready && activity?.initialized == true && activity.pinnedSectionId != null
    var pinBusy by remember(pinDeviceId) { mutableStateOf(false) }
    var pinError by remember(pinDeviceId) { mutableStateOf<String?>(null) }
    var readError by remember(pinDeviceId) { mutableStateOf<String?>(null) }
    fun togglePin(thread: CodexThread) {
        val device = pinDeviceId ?: return
        if (pinBusy || !pinsLoaded) return
        val pinned = thread.id !in pinnedIds
        pinBusy = true
        pinError = null
        scope.launch {
            try { repository.setThreadPinned(thread.id, device, pinned) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.e("RemodexSidebarPins", "operation=sidebar-pins stage=server-write thread=${thread.id} pinned=$pinned", failure)
                pinError = failure.message ?: failure.javaClass.name
            } finally { pinBusy = false }
        }
    }


    var showWorktreeSheet by remember { mutableStateOf(false) }
    var worktreeSheetBasePath by remember { mutableStateOf<String?>(null) }
    var worktreeChatBusy by remember { mutableStateOf(false) }
    var worktreeChatError by remember { mutableStateOf<String?>(null) }
    var worktreeGitSyncAlert by remember { mutableStateOf<TurnGitSyncAlert?>(null) }
    var pendingWorktreeChat by remember { mutableStateOf<PendingSidebarWorktreeChat?>(null) }
    var renameTarget by remember { mutableStateOf<CodexThread?>(null) }
    var renameBusy by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var deleteLocalTarget by remember { mutableStateOf<CodexThread?>(null) }
    var deleteLocalBusy by remember { mutableStateOf(false) }
    var deleteLocalError by remember { mutableStateOf<String?>(null) }
    var archiveGroupTarget by remember { mutableStateOf<SidebarThreadGroup?>(null) }
    var archiveGroupBusy by remember { mutableStateOf(false) }
    var archiveGroupError by remember { mutableStateOf<String?>(null) }
    var deleteLocalGroupTarget by remember { mutableStateOf<SidebarThreadGroup?>(null) }
    var deleteLocalGroupBusy by remember { mutableStateOf(false) }
    var deleteLocalGroupError by remember { mutableStateOf<String?>(null) }
    var collapsedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }


    var projectlessRoots by remember(repository, pinDeviceId) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(repository, pinDeviceId, ready) {
        if (ready) try {
            val result = repository.sendRequest("project/projectlessRoots", com.remodex.mobile.core.model.JSONValue.Obj(emptyMap()))
            projectlessRoots = requireNotNull(result.result?.objectValue?.get("roots")?.arrayValue) {
                "project/projectlessRoots missing roots"
            }.map { requireNotNull(it.stringValue) { "Invalid projectless root" } }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { Log.w("RemodexSidebar", "project/projectlessRoots", error) }
    }
    val workingIds = remember(runningTurnByThread, protectedRunningFallback) {
        runningTurnByThread.keys + protectedRunningFallback
    }
    val live = remember(threads) { threads.filter { it.syncState == CodexThreadSyncState.live } }
    val questionIds = remember(pendingQuestions) { pendingQuestions.values.mapTo(hashSetOf()) { it.threadId } }
    val projects = if (listTab == SidebarListTab.Projects) remember(live, projectlessRoots) {
        SidebarThreadGrouping.makeGroups(live, projectlessRoots = projectlessRoots)
    } else emptyList()
    val selectedProject = projects.firstOrNull { it.id == projectId }
    LaunchedEffect(projectId, selectedProject, ready, projects) {
        if (inProject && ready && projects.isNotEmpty() && selectedProject == null) projectId = null
    }
    val directory = !searching && listTab == SidebarListTab.Projects && !inProject
    val visibleGroups = when {
        searching -> remember(live, query) {
            val matches = SidebarThreadGrouping.sortThreadsByRecentActivity(filterThreadsForSidebar(live, query))
            listOf(SidebarThreadGroup("__search__", "", SidebarThreadGroupKind.Chats, java.time.Instant.EPOCH, null, matches))
        }
        inProject -> listOfNotNull(selectedProject)
        directory -> projects
        else -> {
            val groups = remember(live, pinnedIds, recentIds, workingIds) {
                sidebarHomeGroups(live, pinnedIds, recentIds, workingIds)
            }
            remember(groups, expandedGroupIds) {
                SidebarThreadGrouping.applyGroupLimit(groups, SIDEBAR_THREADS_PER_GROUP, expandedGroupIds)
            }
        }
    }
    val home = !searching && listTab == SidebarListTab.Home
    val collapsibleGroupIds = remember(home, visibleGroups) {
        if (home) visibleGroups.mapTo(mutableSetOf()) { it.id } else emptySet()
    }

    fun openNewChatDraft(
        source: NewChatDraftSource,
        preferredProjectPath: String? = null,
    ) {
        if (worktreeChatBusy) return
        onOpenNewChatDraft(source, preferredProjectPath)
    }

    fun startManagedWorktreeChat(
        baseProjectPath: String? = null,
        selectedBaseBranch: String? = null,
        changeTransfer: GitWorktreeChangeTransferMode = GitWorktreeChangeTransferMode.none,
        preselected: PendingSidebarWorktreeChat? = null,
        skipPreflight: Boolean = false,
    ) {
        worktreeChatError = null
        worktreeChatBusy = true
        scope.launch {
            try {
                val base =
                    preselected?.baseProjectPath
                        ?: baseProjectPath?.trim()?.takeIf { it.isNotEmpty() }
                        ?: WorktreeNewChatDefaults.baseProjectPath(activeId, threads)
                        ?: run {
                            worktreeChatError =
                                context.getString(R.string.sidebar_worktree_chat_no_local_project)
                            return@launch
                        }
                val branch =
                    preselected?.baseBranch
                        ?: selectedBaseBranch?.trim()?.takeIf { it.isNotEmpty() }
                        ?: run {
                            val loaded = loadGitBranchesWithStatus(repository, base)
                            val gitResult =
                                loaded.getOrNull() ?: run {
                                    worktreeChatError =
                                        loaded.exceptionOrNull()?.let { GitBranchDisplayMapper.userVisibleMessage(it) }
                                            ?: context.getString(R.string.sidebar_worktree_chat_no_base_branch)
                                    return@launch
                                }
                            WorktreeNewChatDefaults.baseBranch(GitBranchDisplayMapper.summaryFrom(gitResult))
                                ?: run {
                                    worktreeChatError =
                                        context.getString(R.string.sidebar_worktree_chat_no_base_branch)
                                    return@launch
                                }
                        }
                if (!skipPreflight) {
                    val loaded = loadGitBranchesWithStatus(repository, base)
                    val gitResult =
                        loaded.getOrNull() ?: run {
                            worktreeChatError =
                                loaded.exceptionOrNull()?.let { GitBranchDisplayMapper.userVisibleMessage(it) }
                                    ?: context.getString(R.string.sidebar_worktree_chat_no_base_branch)
                            return@launch
                        }
                    val alert =
                        TurnGitPreflightPolicy.alertFor(
                            status = gitResult.status,
                            branches = gitResult,
                            operation =
                                TurnGitPreflightOperation.createManagedWorktree(
                                    baseBranch = branch,
                                    changeTransfer = changeTransfer,
                                ),
                        )
                    if (alert != null) {
                        pendingWorktreeChat = PendingSidebarWorktreeChat(base, branch, changeTransfer)
                        worktreeGitSyncAlert = alert
                        return@launch
                    }
                }
                WorktreeFlowCoordinator(repository).startNewManagedWorktreeChat(base, branch, changeTransfer)
                showWorktreeSheet = false
                worktreeSheetBasePath = null
                onThreadSelected()
            } catch (e: Exception) {
                worktreeChatError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            } finally {
                worktreeChatBusy = false
            }
        }
    }

    val indexEntries = remember(directory, projects) {
        if (directory) projectIndexEntries(projects) else emptyList()
    }
    val showIndex by remember(listState, indexEntries) {
        derivedStateOf { indexEntries.size > 1 && (listState.canScrollBackward || listState.canScrollForward) }
    }
    var projectIndexOpen by rememberSaveable(pinDeviceId) { mutableStateOf(false) }
    val allCollapsed = collapsibleGroupIds.isNotEmpty() && collapsedGroupIds.containsAll(collapsibleGroupIds)
    fun toggleGroups() {
        collapsedGroupIds = if (allCollapsed) collapsedGroupIds - collapsibleGroupIds else collapsedGroupIds + collapsibleGroupIds
        expandedGroupIds = expandedGroupIds - collapsibleGroupIds
    }
    var refreshing by remember(repository) { mutableStateOf(false) }
    var refreshFailure by remember(repository) { mutableStateOf<String?>(null) }
    fun refreshList() {
        if (refreshing) return
        refreshing = true
        refreshFailure = null
        scope.launch {
            try { repository.refreshThreads() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Log.e("RemodexSidebar", "operation=refreshThreads stage=refresh_list", e)
                refreshFailure = e.localizedMessage ?: e.javaClass.simpleName
            } finally { refreshing = false }
        }
    }
    val bridgeConnected = conn is ConnectionState.Connected

    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .background(sidebarColors.background)
                .padding(horizontal = 12.dp),
    ) {
        if (searching) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SidebarSearchField(query, { query = it; searchResults.requestScrollToItem(0) }, modifier = Modifier.weight(1f), focusOnOpen = true)
                IconButton(onClick = ::closeSearch) {
                    Icon(Icons.Filled.Close, stringResource(android.R.string.cancel))
                }
            }
        } else key(drawerOpen) {
            // Dispose the anchored popup as soon as its drawer leaves the screen.
            SidebarBrandHeader(
                colors = sidebarColors,
                navigation = {
                    if (inProject) Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { projectId = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.drawer_back_projects))
                        }
                        Text(selectedProject?.let { if (it.kind == SidebarThreadGroupKind.Chats) stringResource(R.string.sidebar_tab_chats) else it.label }.orEmpty(),
                            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } else SidebarListTabRow(listTab, onSelected = { listTab = it })
                },
                onMoreMenu =
                    SidebarMoreMenuCallbacks(
                        onOpenArchivedChats = onOpenArchivedChats,
                        onRefreshThreads = ::refreshList,
                        onOpenConnections = onOpenMyDevices,
                        toggleGroups = if (collapsibleGroupIds.isEmpty()) null else ::toggleGroups,
                        allCollapsed = allCollapsed,
                        openIndex = if (indexEntries.size > 1) { { projectIndexOpen = true } } else null,
                    ),
                onOpenSettings = onOpenSettings,
                onSearch = { searchResults.requestScrollToItem(0); searching = true },
            )
        }
        if (searching) {
            val count = visibleGroups.single().totalCount
            val summary = when {
                query.isBlank() -> stringResource(R.string.drawer_all_count, count)
                count > 0 -> stringResource(R.string.drawer_matches, count)
                else -> stringResource(R.string.drawer_no_matches)
            }
            Text(summary, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = sidebarColors.secondaryText)
        } else if (ready && visibleGroups.isEmpty()) {
            Text(stringResource(R.string.drawer_empty), Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium, color = sidebarColors.secondaryText)
        }
        if (refreshing) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        refreshFailure?.let { error ->
            Text(stringResource(R.string.drawer_refresh_failed, error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = ::refreshList) { Text(stringResource(R.string.workspace_text_preview_retry)) }
        }
        readError?.let { error ->
            Text(stringResource(R.string.sidebar_mark_read_failed, error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        pinError?.let { error ->
            Text(stringResource(R.string.sidebar_pin_failed, error), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { scope.launch { try { repository.refreshThreads(); pinError = null } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { pinError = failure.message; Log.e("RemodexSidebarPins", "operation=pin-refresh", failure) } } }, enabled = !pinBusy) { Text(stringResource(R.string.workspace_text_preview_retry)) }
        }
        worktreeChatError?.let { err ->
            Text(
                text = err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(max = 40.dp),
            )
        }
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        ) {
            SidebarRunAnimation(enabled = animateRunningRows) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = if (showIndex) 44.dp else 0.dp)
                    .singleOwnerPageEntry(if (inProject) 2 else listTab.ordinal) { from, to -> to < from },
                flingBehavior = rememberSessionFling(),
                contentPadding = PaddingValues(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                visibleGroups.forEach { group ->
                if (home || directory) item(key = "hdr-${group.id}", contentType = "section-header") {
                    val isCollapsed = group.id in collapsedGroupIds
                        RepoHeader(
                            group = group,
                            modifier = Modifier.padding(horizontal = 4.dp),
                            worktreeChatBusy = worktreeChatBusy,
                            colors = sidebarColors,
                            directory = directory,
                            collapsed = isCollapsed,
                            onToggleCollapse = {
                            if (directory) projectId = group.id else {
                            // Reopening a section starts with its compact preview again.
                            expandedGroupIds = expandedGroupIds - group.id
                            collapsedGroupIds =
                                if (isCollapsed) {
                                    collapsedGroupIds - group.id
                                } else {
                                    collapsedGroupIds + group.id
                                }
                            }
                        },
                        onNewChatInProject =
                            if (group.kind == SidebarThreadGroupKind.Project && group.projectPath != null) {
                                {
                                    openNewChatDraft(
                                        NewChatDraftSource.folderChat,
                                        preferredProjectPath = group.projectPath,
                                    )
                                }
                            } else {
                                null
                            },
                            onNewWorktreeInProject =
                                if (
                                    FeatureFlags.gitIntegrationEnabled &&
                                    group.kind == SidebarThreadGroupKind.Project &&
                                    group.projectPath != null &&
                                    ready &&
                                    bridgeConnected
                                ) {
                                    {
                                        worktreeChatError = null
                                        worktreeSheetBasePath = group.projectPath
                                        showWorktreeSheet = true
                                    }
                                } else {
                                    null
                                },
                            onArchiveProjectGroup =
                                if (group.kind == SidebarThreadGroupKind.Project) {
                                    {
                                    archiveGroupTarget = group
                                    archiveGroupError = null
                                }
                            } else {
                                null
                            },
                        onDeleteLocalGroup =
                            if (group.kind == SidebarThreadGroupKind.Project) {
                                {
                                    deleteLocalGroupTarget = group
                                    deleteLocalGroupError = null
                                }
                            } else {
                                null
                            },
                        onOpenArchivedChats = null,
                    )
                }
                if (directory || (home && group.id in collapsedGroupIds)) return@forEach
                items(
                    items = group.visibleThreads,
                    key = { "${group.id}/thread/${it.id}" },
                    contentType = { "thread" },
                ) { thread ->
                    val isActive = thread.id == activeId
                    val isRunning =
                        runningTurnByThread.containsKey(thread.id) ||
                            protectedRunningFallback.contains(thread.id)
                    val onSelectThread = {
                        // The clicked row is an explicit read action; use its server revision,
                        // not a later revision that may arrive while opening the conversation.
                        val receipt = activity?.threads?.get(thread.id)
                        val device = pinDeviceId
                        readError = null
                        if (ready && device != null && receipt?.unread == true) scope.launch {
                            try { repository.markThreadRead(thread.id, device, receipt.revision) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                Log.e("RemodexSidebarActivity", "operation=mark-read stage=row-selection thread=${thread.id} revision=${receipt.revision}", failure)
                                readError = failure.message ?: failure.javaClass.name
                            }
                        }
                        scope.launch {
                            repository.setActiveThreadId(thread.id)
                            onThreadSelected()
                            if (searching) closeSearch()
                        }
                        Unit
                    }
                    val onRenameThread = {
                        renameTarget = thread
                        renameError = null
                    }
                    val onDeleteThread = {
                        deleteLocalTarget = thread
                        deleteLocalError = null
                    }
                    Box {
                        val shortcut = searching || home
                        // Keep the popup out of untouched rows. After opening it owns its exit animation.
                        var menuExpanded by remember(pinDeviceId, thread.id) { mutableStateOf<Boolean?>(null) }
                        SidebarThreadRow(
                            thread = thread,
                            selected = isActive,
                            projectLabel = if (shortcut && !SidebarThreadGrouping.isProjectless(thread, projectlessRoots)) thread.projectDisplayName else null,
                            isRunning = isRunning,
                            isUnread = activity?.threads?.get(thread.id)?.unread == true,
                            needsAnswer = thread.id in questionIds,
                            hasQuestions = thread.id in questionThreads,
                            onAnswerRequest = { questionDialogThreadId = thread.id },
                            activeMetadata = activeChatMetadata,
                            colors = sidebarColors,
                            onSelect = onSelectThread,
                            onActionsRequest = { menuExpanded = true },
                        )
                        if (menuExpanded != null) RemodexDropdownMenu(
                            expanded = menuExpanded == true,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(text = { Text(stringResource(if (thread.id in pinnedIds) R.string.sidebar_unpin_thread else R.string.sidebar_pin_thread)) },
                                enabled = pinsLoaded && !pinBusy, onClick = { menuExpanded = false; togglePin(thread) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.sidebar_thread_rename)) },
                                onClick = { menuExpanded = false; onRenameThread() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.sidebar_thread_delete_local), color = MaterialTheme.colorScheme.error) },
                                onClick = { menuExpanded = false; onDeleteThread() })
                        }
                    }
                }
                if (home &&
                    (
                        group.hiddenCount > 0 ||
                            (group.id in expandedGroupIds && group.totalCount > SIDEBAR_THREADS_PER_GROUP)
                    )
                ) {
                    item(key = "more-${group.id}", contentType = "show-more") {
                        ShowAllRow(
                            expanded = group.id in expandedGroupIds,
                            hiddenCount = group.hiddenCount,
                            onClick = {
                                expandedGroupIds =
                                    if (group.id in expandedGroupIds) {
                                        expandedGroupIds - group.id
                                    } else {
                                        expandedGroupIds + group.id
                                    }
                            },
                        )
                    }
                }
            }
            }
            }
            if (showIndex) {
                ProjectIndex(indexEntries, listState, sidebarColors, Modifier.align(Alignment.CenterEnd),
                    openPicker = { projectIndexOpen = true })
            }
            if (projectIndexOpen) ProjectIndexPicker(indexEntries, onDismiss = { projectIndexOpen = false }) { entry ->
                projectId = entry.id
                projectIndexOpen = false
            }
        }
        if (!searching) SidebarFloatingActionBar(
                terminalEnabled = true,
                chatEnabled = ready && !worktreeChatBusy,
                onTerminal = onOpenTerminal,
                onChat = {
                    val target = sidebarFloatingChatDraftTarget(selectedProject?.projectPath.takeIf { inProject })
                    openNewChatDraft(
                        target.source,
                        preferredProjectPath = target.preferredProjectPath,
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = SidebarFloatingBarBottomPadding),
            )
        ThreadRenameDialog(
            visible = renameTarget != null,
            initialName = renameTarget?.displayTitle.orEmpty(),
            busy = renameBusy,
            error = renameError,
            onDismiss = {
                if (!renameBusy) {
                    renameTarget = null
                    renameError = null
                }
            },
            onConfirm = { newName ->
                val target = renameTarget ?: return@ThreadRenameDialog
                if (newName.isBlank()) {
                    renameError = context.getString(R.string.thread_rename_dialog_empty)
                    return@ThreadRenameDialog
                }
                renameBusy = true
                renameError = null
                scope.launch {
                    runCatching {
                        repository.renameThread(target.id, newName)
                    }.onSuccess {
                        renameTarget = null
                    }.onFailure { e ->
                        renameError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    }
                    renameBusy = false
                }
            },
        )
        deleteLocalTarget?.let { target ->
            RemodexAlertDialog(
                onDismissRequest = {
                    if (!deleteLocalBusy) {
                        deleteLocalTarget = null
                        deleteLocalError = null
                    }
                },
                title = { Text(stringResource(R.string.sidebar_thread_delete_local_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.sidebar_thread_delete_local_message, target.displayTitle))
                        deleteLocalError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    DialogConfirmButton(
                        destructive = true,
                        enabled = !deleteLocalBusy,
                        onClick = {
                            deleteLocalBusy = true
                            deleteLocalError = null
                            scope.launch {
                                runCatching {
                                    repository.deleteThreadLocally(target.id)
                                }.onSuccess {
                                    deleteLocalTarget = null
                                }.onFailure { e ->
                                    deleteLocalError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                                }
                                deleteLocalBusy = false
                            }
                        },
                    ) {
                        Text(
                            text =
                                if (deleteLocalBusy) {
                                    stringResource(R.string.sidebar_thread_delete_local_deleting)
                                } else {
                                    stringResource(R.string.sidebar_thread_delete_local_confirm)
                                },
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !deleteLocalBusy,
                        onClick = {
                            deleteLocalTarget = null
                            deleteLocalError = null
                        },
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        archiveGroupTarget?.let { group ->
            RemodexAlertDialog(
                onDismissRequest = {
                    if (!archiveGroupBusy) {
                        archiveGroupTarget = null
                        archiveGroupError = null
                    }
                },
                title = { Text(stringResource(R.string.sidebar_project_archive_title, group.label)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.sidebar_project_archive_message))
                        archiveGroupError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    DialogConfirmButton(
                        enabled = !archiveGroupBusy,
                        onClick = {
                            archiveGroupBusy = true
                            archiveGroupError = null
                            scope.launch {
                                runCatching {
                                    val ids = SidebarThreadGrouping.liveThreadIdsForGroup(group, threads)
                                    repository.archiveThreadGroup(ids)
                                }.onSuccess {
                                    archiveGroupTarget = null
                                }.onFailure { e ->
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    android.util.Log.e("RemodexArchive", "thread/archive", e)
                                    archiveGroupError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                                }
                                archiveGroupBusy = false
                            }
                        },
                    ) {
                        Text(
                            text =
                                if (archiveGroupBusy) {
                                    stringResource(R.string.sidebar_project_archive_busy)
                                } else {
                                    stringResource(R.string.sidebar_project_archive_confirm)
                                },
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !archiveGroupBusy,
                        onClick = {
                            archiveGroupTarget = null
                            archiveGroupError = null
                        },
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        deleteLocalGroupTarget?.let { group ->
            RemodexAlertDialog(
                onDismissRequest = {
                    if (!deleteLocalGroupBusy) {
                        deleteLocalGroupTarget = null
                        deleteLocalGroupError = null
                    }
                },
                title = { Text(stringResource(R.string.sidebar_project_delete_local_title, group.label)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.sidebar_project_delete_local_message))
                        deleteLocalGroupError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    DialogConfirmButton(
                        destructive = true,
                        enabled = !deleteLocalGroupBusy,
                        onClick = {
                            deleteLocalGroupBusy = true
                            deleteLocalGroupError = null
                            scope.launch {
                                runCatching {
                                    val ids = SidebarThreadGrouping.liveThreadIdsForGroup(group, threads)
                                    repository.deleteLocalThreadGroup(ids)
                                }.onSuccess {
                                    deleteLocalGroupTarget = null
                                }.onFailure { e ->
                                    deleteLocalGroupError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                                }
                                deleteLocalGroupBusy = false
                            }
                        },
                    ) {
                        Text(
                            text =
                                if (deleteLocalGroupBusy) {
                                    stringResource(R.string.sidebar_project_delete_local_busy)
                                } else {
                                    stringResource(R.string.sidebar_project_delete_local_confirm)
                                },
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !deleteLocalGroupBusy,
                        onClick = {
                            deleteLocalGroupTarget = null
                            deleteLocalGroupError = null
                        },
                    ) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        SidebarNewWorktreeSheet(
            repository = repository,
            visible = showWorktreeSheet,
            baseProjectPath = worktreeSheetBasePath,
            busy = worktreeChatBusy,
            onDismiss = {
                showWorktreeSheet = false
                worktreeSheetBasePath = null
                worktreeChatError = null
            },
            onCreate = { basePath, baseBranch, transfer ->
                startManagedWorktreeChat(
                    baseProjectPath = basePath,
                    selectedBaseBranch = baseBranch,
                    changeTransfer = transfer,
                )
            },
        )
        worktreeGitSyncAlert?.let { alert ->
            fun dismissWorktreeAlert() {
                worktreeGitSyncAlert = null
                pendingWorktreeChat = null
            }

            RemodexAlertDialog(
                onDismissRequest = { dismissWorktreeAlert() },
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
                                            TurnGitSyncAlertAction.continuePendingGitOperation,
                                            TurnGitSyncAlertAction.continueGitBranchOperation,
                                            -> {
                                                val pending = pendingWorktreeChat
                                                dismissWorktreeAlert()
                                                if (pending != null) {
                                                    startManagedWorktreeChat(
                                                        preselected = pending,
                                                        changeTransfer = pending.changeTransfer,
                                                        skipPreflight = true,
                                                    )
                                                }
                                            }
                                            TurnGitSyncAlertAction.pullRebase -> {
                                                val pending = pendingWorktreeChat
                                                dismissWorktreeAlert()
                                                if (pending != null) {
                                                    worktreeChatBusy = true
                                                    scope.launch {
                                                        runCatching {
                                                            com.remodex.mobile.services.GitActionsService(
                                                                repository,
                                                                pending.baseProjectPath,
                                                            ).pull()
                                                        }.onFailure { e ->
                                                            worktreeChatError =
                                                                GitBranchDisplayMapper.userVisibleMessage(e)
                                                        }
                                                        worktreeChatBusy = false
                                                    }
                                                }
                                            }
                                            else -> dismissWorktreeAlert()
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
                            TextButton(onClick = { dismissWorktreeAlert() }) {
                                Text(stringResource(android.R.string.ok))
                            }
                        }
                    }
                },
                dismissButton = {
                    val cancel = alert.buttons.firstOrNull { it.role == TurnGitSyncAlertButtonRole.cancel }
                    if (cancel != null && alert.buttons.size > 1) {
                        TextButton(onClick = { dismissWorktreeAlert() }) {
                            Text(cancel.title)
                        }
                    }
                },
            )
        }
    }
}

private data class PendingSidebarWorktreeChat(
    val baseProjectPath: String,
    val baseBranch: String,
    val changeTransfer: GitWorktreeChangeTransferMode,
)

internal data class SidebarFloatingChatDraftTarget(
    val source: NewChatDraftSource,
    val preferredProjectPath: String?,
)

internal fun sidebarFloatingChatDraftTarget(selectedProjectPath: String?): SidebarFloatingChatDraftTarget =
    SidebarFloatingChatDraftTarget(
        source = if (selectedProjectPath == null) NewChatDraftSource.generalChat else NewChatDraftSource.folderChat,
        preferredProjectPath = selectedProjectPath,
    )

@Composable
private fun RepoHeader(
    group: SidebarThreadGroup,
    modifier: Modifier,
    worktreeChatBusy: Boolean,
    colors: SidebarColorPalette,
    onNewChatInProject: (() -> Unit)?,
    onNewWorktreeInProject: (() -> Unit)? = null,
    onArchiveProjectGroup: (() -> Unit)? = null,
    onDeleteLocalGroup: (() -> Unit)? = null,
    onOpenArchivedChats: (() -> Unit)? = null,
    directory: Boolean,
    collapsed: Boolean = false,
    onToggleCollapse: (() -> Unit)? = null,
) {
    var showProjectMenu by remember(group.id) { mutableStateOf<Boolean?>(null) }
    val hasMenuActions =
        onNewChatInProject != null ||
            onNewWorktreeInProject != null ||
            onArchiveProjectGroup != null ||
            onDeleteLocalGroup != null
    val canCollapse = !directory
    val disclosure = stringResource(if (collapsed) R.string.sidebar_state_collapsed else R.string.sidebar_state_expanded)

    Box(modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = if (directory) 60.dp else 48.dp)
                        .then(if (canCollapse) Modifier.semantics { stateDescription = disclosure } else Modifier)
                        .combinedClickable(
                            onClick = { if (onOpenArchivedChats != null) onOpenArchivedChats() else onToggleCollapse?.invoke() },
                            onLongClickLabel = stringResource(R.string.sidebar_project_actions_cd),
                            onLongClick = if (hasMenuActions && !worktreeChatBusy) ({ showProjectMenu = true }) else null,
                        ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (directory) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Icon(group.leadingIcon(), null, Modifier.size(18.dp)) }
                }
                Column(Modifier.weight(1f)) {
                    val label = when {
                        directory && group.kind == SidebarThreadGroupKind.Chats -> stringResource(R.string.sidebar_tab_chats)
                        directory -> group.label
                        group.kind == SidebarThreadGroupKind.Pinned -> stringResource(R.string.session_pinned)
                        group.kind == SidebarThreadGroupKind.Working -> stringResource(R.string.drawer_working)
                        group.kind == SidebarThreadGroupKind.Updated -> stringResource(R.string.drawer_updated)
                        else -> stringResource(R.string.session_recent)
                    }
                    Text(label, style = if (directory) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                        color = if (directory) colors.primaryText else colors.secondaryText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (directory && group.projectPath != null) {
                        Text(group.projectPath, style = MaterialTheme.typography.labelSmall, color = colors.mutedText,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(group.totalCount.toString(), style = MaterialTheme.typography.labelMedium, color = colors.mutedText)
                if (directory) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = colors.mutedText)
                if (canCollapse) {
                    val angle by androidx.compose.animation.core.animateFloatAsState(
                        if (collapsed) -90f else 0f, androidx.compose.animation.core.tween(250,
                            easing = androidx.compose.animation.core.CubicBezierEasing(.215f, .61f, .355f, 1f)), label = "project disclosure")
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.mutedText,
                        modifier = Modifier.size(18.dp).rotate(angle),
                    )
                }
            }
            if (worktreeChatBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        if (hasMenuActions && showProjectMenu != null) {
            RemodexDropdownMenu(
                expanded = showProjectMenu == true,
                onDismissRequest = { showProjectMenu = false },
            ) {
                onNewChatInProject?.let { action ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sidebar_new_chat_in_project)) },
                        onClick = {
                            showProjectMenu = false
                            action()
                        },
                    )
                }
                onNewWorktreeInProject?.let { action ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sidebar_new_managed_worktree_chat)) },
                        onClick = {
                            showProjectMenu = false
                            action()
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(LucideR.drawable.lucide_ic_git_branch),
                                contentDescription = null,
                            )
                        },
                    )
                }
                onArchiveProjectGroup?.let { action ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sidebar_project_archive_menu)) },
                        onClick = {
                            showProjectMenu = false
                            action()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Archive,
                                contentDescription = null,
                            )
                        },
                    )
                }
                onDeleteLocalGroup?.let { action ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.sidebar_thread_delete_local),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showProjectMenu = false
                            action()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowAllRow(
    expanded: Boolean,
    hiddenCount: Int,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(start = 18.dp)) {
        TextButton(onClick = onClick) {
            Text(if (expanded) stringResource(R.string.sidebar_group_show_less)
                else stringResource(R.string.sidebar_group_show_more, hiddenCount))
            if (!expanded) Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
    }
}

private fun SidebarThreadGroup.leadingIcon(): ImageVector =
    when (kind) {
        SidebarThreadGroupKind.Pinned -> Icons.Outlined.PushPin
        SidebarThreadGroupKind.Recent, SidebarThreadGroupKind.Updated, SidebarThreadGroupKind.Working -> Icons.Outlined.History
        SidebarThreadGroupKind.Archived -> Icons.Outlined.Archive
        SidebarThreadGroupKind.Chats -> Icons.Outlined.ChatBubbleOutline
        SidebarThreadGroupKind.Project -> {
            val t = threads.firstOrNull()
            when {
                t == null -> Icons.Outlined.Folder
                t.normalizedProjectPath == null -> Icons.Outlined.ChatBubbleOutline
                t.isManagedWorktreeProject -> Icons.Outlined.AccountTree
                else -> Icons.Outlined.Folder
            }
        }
    }

private fun filterThreadsForSidebar(
    threads: List<CodexThread>,
    query: String,
): List<CodexThread> {
    val t = query.trim().lowercase()
    if (t.isEmpty()) return threads
    return threads.filter { thread ->
        thread.displayTitle.lowercase().contains(t) ||
            thread.id.lowercase().contains(t) ||
            (thread.preview?.lowercase()?.contains(t) == true) ||
            (thread.cwd?.lowercase()?.contains(t) == true)
    }
}
