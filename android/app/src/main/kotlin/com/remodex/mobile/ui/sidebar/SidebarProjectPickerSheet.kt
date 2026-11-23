package com.remodex.mobile.ui.sidebar

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexProjectDirectoryEntry
import com.remodex.mobile.core.model.CodexProjectDirectoryListing
import com.remodex.mobile.core.model.CodexProjectLocation
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.projectDisplayLabelFor
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.services.ProjectFolderService
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class NewThreadWorkspaceSummary(
    val name: String,
    val path: String,
    val relativeTime: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarProjectPickerSheet(
    repository: CodexRepository,
    visible: Boolean,
    initialPath: String?,
    onDismiss: () -> Unit,
    onWorkspaceSelected: (String?) -> Unit,
    initialFoldersCollapsed: Boolean = false,
    threads: List<CodexThread> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val service = remember(repository) { ProjectFolderService(repository) }
    val colors = rememberSidebarColorPalette()

    var quickLocations by remember { mutableStateOf<List<CodexProjectLocation>>(emptyList()) }
    var currentPath by remember(visible, initialPath) { mutableStateOf(initialPath?.trim().orEmpty()) }
    var listing by remember { mutableStateOf<CodexProjectDirectoryListing?>(null) }
    var searchQuery by remember(visible) { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CodexProjectDirectoryEntry>>(emptyList()) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var createName by remember { mutableStateOf("") }
    var createBusy by remember { mutableStateOf(false) }
    var foldersCollapsed by remember { mutableStateOf(initialFoldersCollapsed) }
    var showAllRecentWorkspaces by remember { mutableStateOf(false) }

    fun selectFolder(path: String) {
        val cwd = path.trim()
        if (cwd.isBlank()) return
        if (cwd != currentPath) listing = null
        currentPath = cwd
        foldersCollapsed = false
        searchQuery = ""
        createName = ""
        loadingError = null
    }

    fun createFolder() {
        val parent = listing?.path ?: return
        val name = createName.trim()
        if (parent.isBlank() || name.isBlank() || createBusy) return
        createBusy = true
        loadingError = null
        scope.launch {
            try {
                val createdPath = service.createDirectory(parent, name)
                createName = ""
                searchQuery = ""
                selectFolder(createdPath)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                reportFolderPickerError("create-directory", error) { loadingError = it }
            } finally {
                createBusy = false
            }
        }
    }

    LaunchedEffect(repository) {
        try {
            quickLocations = service.quickLocations()
            if (currentPath.isBlank()) currentPath = quickLocations.firstOrNull()?.path.orEmpty()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            reportFolderPickerError("quick-locations", error) {
                if (currentPath.isBlank()) loadingError = it
            }
        }
    }

    LaunchedEffect(currentPath) {
        val path = currentPath
        if (path.isBlank()) return@LaunchedEffect
        listing = null
        loadingError = null
        try {
            listing = service.listDirectory(path)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            reportFolderPickerError("list-directory", error) { loadingError = it }
        }
    }

    var searchError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentPath, searchQuery) {
        searchResults = emptyList()
        searchError = null
        if (currentPath.isBlank() || searchQuery.isBlank()) return@LaunchedEffect
        try {
            searchResults = service.searchDirectories(currentPath, searchQuery).sortedForWorkspacePicker()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            reportFolderPickerError("search-directories", error) { searchError = it }
        }
    }

    RemodexModalBottomSheet(
        modifier = modifier,
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        val visibleEntries =
            if (searchQuery.trim().isNotEmpty()) {
                searchResults
            } else {
                listing?.entries.orEmpty().sortedForWorkspacePicker()
            }
        val searchingFolders = searchQuery.trim().isNotEmpty()
        val folderListCollapsed = foldersCollapsed && !searchingFolders
        val recentWorkspaces =
            remember(threads, currentPath) {
                recentWorkspaceSummaries(threads, currentPath)
            }
        val visibleRecentWorkspaces =
            if (showAllRecentWorkspaces) {
                recentWorkspaces
            } else {
                recentWorkspaces.take(3)
            }
        val quickLocationChips = quickLocations.preferredQuickLocations()

        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding =
                    PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        bottom = 12.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.new_chat_choose_workspace),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }

                if (visibleRecentWorkspaces.isNotEmpty()) {
                    item {
                        SectionLabel(
                            text = stringResource(R.string.sidebar_project_picker_recent_workspaces),
                            colors = colors,
                            actionText = stringResource(R.string.sidebar_project_picker_view_all).takeIf {
                                recentWorkspaces.size > 3 && !showAllRecentWorkspaces
                            },
                            onAction = { showAllRecentWorkspaces = true },
                        )
                    }
                    items(visibleRecentWorkspaces, key = { "recent-${it.path}" }) { workspace ->
                        WorkspaceRow(
                            workspace = workspace,
                            enabled = !createBusy,
                            onClick = { onWorkspaceSelected(workspace.path) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                item {
                    BrowseWorkspaceSection(
                        currentPath = listing?.path ?: currentPath,
                        parentPath = listing?.parentPath,
                        quickLocations = quickLocationChips,
                        query = searchQuery,
                        colors = colors,
                        enabled = !createBusy,
                        onQueryChange = { searchQuery = it },
                        onQuickLocation = { selectFolder(it.path) },
                        onParent = { parent -> selectFolder(parent) },
                    )
                }

                (searchError ?: loadingError)?.let { error ->
                    item {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                item {
                    CreateFolderField(
                        value = createName,
                        colors = colors,
                        enabled = !createBusy && listing != null,
                        busy = createBusy,
                        onValueChange = { createName = it },
                        onCreate = { createFolder() },
                    )
                }

                item {
                    SectionLabel(
                        text =
                            if (searchingFolders) {
                                stringResource(R.string.sidebar_project_picker_search_results)
                            } else {
                                stringResource(R.string.sidebar_project_picker_subfolders)
                            },
                        colors = colors,
                        actionText =
                            if (!searchingFolders) {
                                if (folderListCollapsed) {
                                    stringResource(R.string.sidebar_project_picker_show)
                                } else {
                                    stringResource(R.string.sidebar_project_picker_hide)
                                }
                            } else {
                                null
                            },
                        onAction = { foldersCollapsed = !foldersCollapsed },
                    )
                }

                if (!folderListCollapsed) {
                    if (visibleEntries.isEmpty()) {
                        item {
                            Text(
                                text =
                                    if (searchingFolders) {
                                        stringResource(R.string.sidebar_project_picker_empty_search)
                                    } else {
                                        stringResource(R.string.sidebar_project_picker_empty_folder)
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.secondaryText,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    } else {
                        items(visibleEntries, key = { "folder-${it.id}" }) { entry ->
                            FolderRow(
                                entry = entry,
                                enabled = !createBusy,
                                onClick = { selectFolder(entry.path) },
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { onWorkspaceSelected(null) }, enabled = !createBusy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.new_chat_no_project))
                }
                Button(
                    onClick = { listing?.path?.let(onWorkspaceSelected) },
                    enabled = listing != null && !createBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.new_chat_use_directory))
                }
            }
        }
    }
}

private inline fun reportFolderPickerError(stage: String, error: Exception, display: (String) -> Unit) {
    Log.e("RemodexWorkspace", "Workspace operation failed stage=$stage", error)
    display(error.localizedMessage?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
}

@Composable
private fun BrowseWorkspaceSection(
    currentPath: String,
    parentPath: String?,
    quickLocations: List<CodexProjectLocation>,
    query: String,
    colors: SidebarColorPalette,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onQuickLocation: (CodexProjectLocation) -> Unit,
    onParent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(
                text = stringResource(R.string.sidebar_project_picker_browse_title),
                colors = colors,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    parentPath?.let(onParent)
                },
                enabled = enabled && parentPath != null,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ArrowUpward,
                    contentDescription = stringResource(R.string.sidebar_project_picker_parent_cd),
                    tint = colors.secondaryText,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            text = currentPath.ifBlank { stringResource(R.string.sidebar_project_picker_browse_empty) },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SidebarSearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholderText = stringResource(R.string.sidebar_project_picker_search_hint),
        )
        if (quickLocations.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quickLocations.forEach { location ->
                    QuickLocationChip(
                        text = location.label,
                        enabled = enabled,
                        onClick = { onQuickLocation(location) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickLocationChip(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AssistChip(onClick = onClick, enabled = enabled, modifier = modifier, label = { Text(text) })
}

@Composable
private fun WorkspaceRow(
    workspace: NewThreadWorkspaceSummary,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        headlineContent = { Text(workspace.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(workspace.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Outlined.Folder, null) },
        trailingContent = { workspace.relativeTime?.let { Text(it) } },
    )
}

@Composable
private fun FolderRow(
    entry: CodexProjectDirectoryEntry,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Outlined.Folder, null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
    )
}

@Composable
private fun CreateFolderField(
    value: String,
    colors: SidebarColorPalette,
    enabled: Boolean,
    busy: Boolean,
    onValueChange: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.primaryText),
        shape = RoundedCornerShape(12.dp),
        label = {
            Text(
                text = stringResource(R.string.sidebar_project_picker_create_label),
                color = colors.secondaryText,
            )
        },
        placeholder = {
            Text(
                text = stringResource(R.string.sidebar_project_picker_create_placeholder),
                color = colors.mutedText,
            )
        },
        trailingIcon = {
            IconButton(
                onClick = onCreate,
                enabled = enabled && value.trim().isNotEmpty(),
            ) {
                if (busy) {
                    BusyIndicator(colors = colors)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.CreateNewFolder,
                        contentDescription = stringResource(R.string.sidebar_project_picker_create_cd),
                        tint = colors.secondaryText,
                    )
                }
            }
        },
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.border,
                unfocusedBorderColor = colors.border,
                disabledBorderColor = colors.border,
                cursorColor = colors.primaryText,
                focusedTextColor = colors.primaryText,
                unfocusedTextColor = colors.primaryText,
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                disabledContainerColor = colors.surface,
            ),
    )
}

@Composable
private fun SectionLabel(
    text: String,
    colors: SidebarColorPalette,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                ),
            color = colors.mutedText,
            maxLines = 1,
        )
        if (actionText != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text(
                    text = actionText,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color = colors.secondaryText,
                )
            }
        }
    }
}

@Composable
private fun BusyIndicator(
    colors: SidebarColorPalette,
    modifier: Modifier = Modifier,
) {
    CircularProgressIndicator(
        modifier = modifier.size(18.dp),
        strokeWidth = 2.dp,
        color = colors.primaryText,
        trackColor = colors.border,
    )
}

private fun recentWorkspaceSummaries(
    threads: List<CodexThread>,
    currentPath: String,
): List<NewThreadWorkspaceSummary> {
    val normalizedCurrent = CodexThread.normalizeProjectPath(currentPath)
    return threads
        .filter { it.normalizedProjectPath != null && it.normalizedProjectPath != normalizedCurrent }
        .groupBy { it.normalizedProjectPath.orEmpty() }
        .mapNotNull { (path, workspaceThreads) ->
            val representative =
                workspaceThreads.maxWithOrNull(
                    compareBy<CodexThread> { it.updatedAt ?: it.createdAt ?: Instant.EPOCH }
                        .thenBy { it.id },
                ) ?: return@mapNotNull null
            NewThreadWorkspaceSummary(
                name = projectDisplayLabelFor(path),
                path = path,
                relativeTime = SidebarRelativeTimeFormatter.compactLabel(representative),
            )
        }
        .sortedWith(
            compareByDescending<NewThreadWorkspaceSummary> { summary ->
                threads
                    .filter { it.normalizedProjectPath == summary.path }
                    .maxOfOrNull { it.updatedAt ?: it.createdAt ?: Instant.EPOCH }
                    ?: Instant.EPOCH
            }.thenBy { it.name.lowercase() },
        )
}

private fun List<CodexProjectDirectoryEntry>.sortedForWorkspacePicker(): List<CodexProjectDirectoryEntry> =
    sortedWith(
        compareBy<CodexProjectDirectoryEntry> { entry ->
            when (entry.name.lowercase()) {
                "android" -> 0
                "android-relay" -> 1
                else -> 2
            }
        }.thenBy { it.name.lowercase() }.thenBy { it.path },
    )

private fun List<CodexProjectLocation>.preferredQuickLocations(): List<CodexProjectLocation> {
    val wanted = listOf("home", "desktop", "documents")
    return mapNotNull { location ->
        val key = location.id.lowercase().takeIf { it in wanted }
            ?: location.label.lowercase().takeIf { it in wanted }
        key?.let { wanted.indexOf(it) to location }
    }
        .sortedBy { it.first }
        .map { it.second }
}
