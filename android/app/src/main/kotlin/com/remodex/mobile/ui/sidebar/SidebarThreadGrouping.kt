package com.remodex.mobile.ui.sidebar

import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexThreadSyncState
import java.time.Instant

/**
 * Parity with [SidebarThreadGrouping.swift](../../../../../../../../CodexMobile/CodexMobile/Views/Sidebar/SidebarThreadGrouping.swift).
 */
enum class SidebarThreadGroupKind {
    Pinned,
    Recent,
    Project,
    Chats,
    Archived;

    val canCollapse: Boolean get() = this == Pinned || this == Recent || this == Project
}

data class SidebarThreadGroup(
    val id: String,
    val label: String,
    val kind: SidebarThreadGroupKind,
    val sortDate: Instant,
    val projectPath: String?,
    val threads: List<CodexThread>,
    val visibleThreads: List<CodexThread> = threads,
    val hiddenCount: Int = 0,
    val totalCount: Int = threads.size,
)

object SidebarThreadGrouping {
    private const val CHATS_GROUP_ID = "__chats__"

    fun makeGroups(
        threads: List<CodexThread>,
        pinnedThreadIds: List<String> = emptyList(),
        recentThreadIds: List<String> = emptyList(),
        includeEmptyShortcuts: Boolean = false,
    ): List<SidebarThreadGroup> {
        val byId = threads.filter { it.syncState == CodexThreadSyncState.live }.associateBy { it.id }
        val pinned = pinnedThreadIds.distinct().mapNotNull { byId[it] }
        val pinnedIds = pinned.mapTo(hashSetOf()) { it.id }
        val remaining = threads.filterNot { it.id in pinnedIds }
        val pinnedGroup = pinned.takeIf { it.isNotEmpty() || includeEmptyShortcuts }?.let {
            SidebarThreadGroup(id = "__pinned__", label = "Pinned", kind = SidebarThreadGroupKind.Pinned,
                sortDate = Instant.EPOCH, projectPath = null, threads = it)
        }
        // Recent is a shortcut list: visiting a chat must not remove or reorder its project section.
        val recent = recentThreadIds.distinct().filterNot { it in pinnedIds }.mapNotNull { byId[it] }.take(30)
        val recentGroup = recent.takeIf { it.isNotEmpty() || includeEmptyShortcuts }?.let {
            SidebarThreadGroup(id = "__recent__", label = "Recently used", kind = SidebarThreadGroupKind.Recent,
                sortDate = Instant.EPOCH, projectPath = null, threads = it)
        }
        val archivedThreads = threads.filter { it.syncState == CodexThreadSyncState.archivedLocal }
        val projectGroups = makeProjectGroups(threads = remaining)
        val chatsGroup = makeChatsGroup(threads = remaining)
        val sortedArchived = sortThreadsByRecentActivity(archivedThreads)
        val archivedGroup =
            sortedArchived.firstOrNull()?.let { first ->
                SidebarThreadGroup(
                    id = "archived",
                    label = "Archived (${sortedArchived.size})",
                    kind = SidebarThreadGroupKind.Archived,
                    sortDate = first.updatedAt ?: first.createdAt ?: Instant.EPOCH,
                    projectPath = null,
                    threads = sortedArchived,
                )
            }
        return listOfNotNull(pinnedGroup, recentGroup) + projectGroups + listOfNotNull(chatsGroup, archivedGroup)
    }

    fun applyGroupLimit(
        groups: List<SidebarThreadGroup>,
        limit: Int,
        expandedGroupIds: Set<String> = emptySet(),
        pinnedThreadIds: Set<String> = emptySet(),
    ): List<SidebarThreadGroup> {
        val safeLimit = limit.coerceAtLeast(0)
        return groups.map { group ->
            val expanded = group.id in expandedGroupIds
            if (expanded || group.threads.size <= safeLimit) {
                group.copy(
                    visibleThreads = group.threads,
                    hiddenCount = 0,
                    totalCount = group.threads.size,
                )
            } else {
                val head = group.threads.take(safeLimit)
                val pinnedFromTail =
                    if (pinnedThreadIds.isEmpty()) {
                        emptyList()
                    } else {
                        group.threads.drop(safeLimit).filter { it.id in pinnedThreadIds }
                    }
                val visible = head + pinnedFromTail
                group.copy(
                    visibleThreads = visible,
                    hiddenCount = group.threads.size - visible.size,
                    totalCount = group.threads.size,
                )
            }
        }
    }

    /**
     * Resolves all live thread IDs for a project group, including threads not in the
     * currently-visible filtered list. Parity: iOS [liveThreadIDsForProjectGroup].
     */
    fun liveThreadIdsForGroup(
        group: SidebarThreadGroup,
        allThreads: List<CodexThread>,
    ): List<String> {
        if (group.kind != SidebarThreadGroupKind.Project) return emptyList()
        return sortThreadsByRecentActivity(
            allThreads.filter { thread ->
                thread.syncState != CodexThreadSyncState.archivedLocal &&
                    projectGroupId(thread) == group.id
            },
        ).map { it.id }
    }

    private fun makeProjectGroup(
        projectKey: String,
        threads: List<CodexThread>,
    ): SidebarThreadGroup {
        val sortedThreads = sortThreadsByRecentActivity(threads)
        val representative = sortedThreads.first()
        val sortDate = representative.updatedAt ?: representative.createdAt ?: Instant.EPOCH
        return SidebarThreadGroup(
            id = "project:$projectKey",
            label = representative.projectDisplayName,
            kind = SidebarThreadGroupKind.Project,
            sortDate = sortDate,
            projectPath = representative.normalizedProjectPath,
            threads = sortedThreads,
        )
    }

    private fun makeProjectGroups(threads: List<CodexThread>): List<SidebarThreadGroup> {
        val liveByProject = LinkedHashMap<String, MutableList<CodexThread>>()
        for (thread in threads) {
            if (thread.syncState == CodexThreadSyncState.archivedLocal) continue
            if (thread.normalizedProjectPath == null) continue
            liveByProject.getOrPut(thread.projectKey) { mutableListOf() }.add(thread)
        }
        return liveByProject
            .map { (key, list) -> makeProjectGroup(key, list) }
            .sortedWith { lhs, rhs ->
                // Conversation activity may reorder children, never their project sections.
                val byName = lhs.label.compareTo(rhs.label, ignoreCase = true)
                if (byName != 0) byName else lhs.id.compareTo(rhs.id)
            }
    }

    private fun makeChatsGroup(threads: List<CodexThread>): SidebarThreadGroup? {
        val chatThreads =
            sortThreadsByRecentActivity(
                threads.filter { thread ->
                    thread.syncState != CodexThreadSyncState.archivedLocal &&
                        thread.normalizedProjectPath == null
                },
            )
        val first = chatThreads.firstOrNull() ?: return null
        return SidebarThreadGroup(
            id = CHATS_GROUP_ID,
            label = "Chats",
            kind = SidebarThreadGroupKind.Chats,
            sortDate = first.updatedAt ?: first.createdAt ?: Instant.EPOCH,
            projectPath = null,
            threads = chatThreads,
        )
    }

    private fun sortThreadsByRecentActivity(threads: List<CodexThread>): List<CodexThread> =
        threads.sortedWith { lhs, rhs ->
            val l = lhs.updatedAt ?: lhs.createdAt ?: Instant.EPOCH
            val r = rhs.updatedAt ?: rhs.createdAt ?: Instant.EPOCH
            when {
                l != r -> r.compareTo(l)
                else -> lhs.id.compareTo(rhs.id)
            }
        }

    private fun projectGroupId(thread: CodexThread): String = "project:${thread.projectKey}"
}
