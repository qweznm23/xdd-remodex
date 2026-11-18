package com.remodex.mobile.ui.sidebar

import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexThreadSyncState
import java.time.Instant
import kotlin.test.*

class SidebarRecentGroupingTest {
    private val rows = listOf(
        CodexThread("a", cwd = "/projects/alpha"),
        CodexThread("b", cwd = "/projects/beta"),
        CodexThread("chat"),
    )
    @Test fun shortcutHeadersAreAlwaysVisibleAndInFixedOrder() {
        val groups = SidebarThreadGrouping.makeGroups(rows, includeEmptyShortcuts = true)
        assertEquals(listOf(SidebarThreadGroupKind.Pinned, SidebarThreadGroupKind.Recent), groups.take(2).map { it.kind })
        assertTrue(groups.take(2).all { it.threads.isEmpty() })
    }
    @Test fun pinnedWinsOverRecentAndRecentFollowsVisitOrderNotRemoteActivity() {
        val groups = SidebarThreadGrouping.makeGroups(rows, listOf("chat"), listOf("b", "chat", "a", "b"))
        assertEquals(listOf("chat"), groups[0].threads.map { it.id })
        assertEquals(listOf("b", "a"), groups[1].threads.map { it.id })
        val changed = SidebarThreadGrouping.makeGroups(rows.map { it.copy(updatedAt = Instant.now()) }, listOf("chat"), listOf("b", "a"))
        assertEquals(groups.map { it.id }, changed.map { it.id })
        assertEquals(listOf("b", "a"), changed[1].threads.map { it.id })
    }
    @Test fun recentVisitsKeepProjectGroupsAndTheirStableOrder() {
        val groups = SidebarThreadGrouping.makeGroups(rows, recentThreadIds = listOf("b", "a"))
        val projects = groups.filter { it.kind == SidebarThreadGroupKind.Project }
        assertEquals(listOf("alpha", "beta"), projects.map { it.label })
        assertEquals(listOf("a", "b"), projects.flatMap { it.threads }.map { it.id })
        val keys = groups.flatMap { group -> group.threads.map { "${group.id}/thread/${it.id}" } }
        assertEquals(keys.size, keys.distinct().size)
    }
    @Test fun archivedMissingAndSearchExcludedRowsDoNotAppearInRecent() {
        val groups = SidebarThreadGrouping.makeGroups(listOf(rows[0], rows[1].copy(syncState = CodexThreadSyncState.archivedLocal)),
            recentThreadIds = listOf("b", "missing", "chat", "a"))
        assertEquals(listOf("a"), groups.first { it.kind == SidebarThreadGroupKind.Recent }.threads.map { it.id })
    }
    @Test fun eachSectionHasIndependentFiveRowPreviewAndManualExpansion() {
        val threads = (1..20).map { CodexThread("$it", cwd = "/projects/alpha") }
        val groups = SidebarThreadGrouping.makeGroups(threads,
            pinnedThreadIds = (1..8).map { "$it" }, recentThreadIds = (9..16).map { "$it" })
        val compact = SidebarThreadGrouping.applyGroupLimit(groups, 5)
        assertTrue(compact.all { it.visibleThreads.size <= 5 })
        val expanded = SidebarThreadGrouping.applyGroupLimit(groups, 5, expandedGroupIds = setOf("__recent__"))
        assertEquals(8, expanded.first { it.kind == SidebarThreadGroupKind.Recent }.visibleThreads.size)
        assertEquals(5, expanded.first { it.kind == SidebarThreadGroupKind.Pinned }.visibleThreads.size)
        assertEquals(5, expanded.first { it.kind == SidebarThreadGroupKind.Project }.visibleThreads.size)
        assertEquals(compact, SidebarThreadGrouping.applyGroupLimit(expanded, 5))
    }

}
