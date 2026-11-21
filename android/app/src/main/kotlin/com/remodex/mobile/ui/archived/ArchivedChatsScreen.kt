package com.remodex.mobile.ui.archived

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.ui.theme.conversationColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexThreadSyncState
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.ui.sidebar.SidebarSearchField
import com.remodex.mobile.ui.sidebar.SidebarThreadRow
import com.remodex.mobile.ui.theme.RemodexPageHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedChatsScreen(
    repository: CodexRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onNavigateBack)
    val threads by repository.threads.collectAsStateWithLifecycle()
    val activeId by repository.activeThreadId.collectAsStateWithLifecycle()
    val runningTurnByThread by repository.runningTurnIdByThread.collectAsStateWithLifecycle()
    val protectedRunningFallback by repository.protectedRunningFallbackThreadIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val archived =
        remember(threads) {
            threads.filter { it.syncState == CodexThreadSyncState.archivedLocal }
        }
    val filtered =
        remember(archived, query) {
            val t = query.trim().lowercase()
            if (t.isEmpty()) {
                archived
            } else {
                archived.filter { thread ->
                    thread.displayTitle.lowercase().contains(t) ||
                        thread.id.lowercase().contains(t) ||
                        (thread.preview?.lowercase()?.contains(t) == true)
                }
            }
        }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.conversationColors().reading,
        topBar = {
            RemodexPageHeader(stringResource(R.string.archived_chats_title), onNavigateBack)
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SidebarSearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(R.string.archived_chats_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(
                        items = filtered,
                        key = { it.id },
                    ) { thread ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SidebarThreadRow(
                                thread = thread,
                                modifier = Modifier.weight(1f),
                                selected = thread.id == activeId,
                                isRunning =
                                    runningTurnByThread.containsKey(thread.id) ||
                                        protectedRunningFallback.contains(thread.id),
                                onSelect = {
                                    scope.launch { repository.setActiveThreadId(thread.id) }
                                },
                            )
                            IconButton(enabled = busy == null, onClick = {
                                if (busy != null) return@IconButton
                                busy = thread.id
                                scope.launch {
                                    try {
                                        error = null
                                        repository.unarchiveThread(thread.id)
                                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                                        throw cancel
                                    } catch (failure: Exception) {
                                        android.util.Log.e("RemodexArchive", "thread/unarchive", failure)
                                        error = failure.localizedMessage ?: failure.javaClass.simpleName
                                    } finally { busy = null }
                                }
                            }) { Icon(painterResource(LucideR.drawable.lucide_ic_archive_restore), stringResource(R.string.thread_unarchive)) }
                        }
                    }
                }
            }
        }
    }
}
