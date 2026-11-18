package com.remodex.mobile.ui.sidebar

import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexThreadSyncState
import java.time.Instant
import kotlin.test.*

class SidebarPinnedGroupingTest {
    private val a = CodexThread("a", cwd = "/projects/alpha", updatedAt = Instant.ofEpochSecond(20))
    private val b = CodexThread("b", cwd = "/projects/beta", updatedAt = Instant.ofEpochSecond(30))
    private val chat = CodexThread("chat", updatedAt = Instant.ofEpochSecond(40))

    @Test fun pinnedSectionIsFirstAndRowsAreNotDuplicatedInProjects() {
        val groups = SidebarThreadGrouping.makeGroups(listOf(a, b, chat), listOf("b", "chat"))
        assertEquals(SidebarThreadGroupKind.Pinned, groups.first().kind)
        assertEquals(listOf("b", "chat"), groups.first().threads.map { it.id })
        assertEquals(listOf("b", "chat", "a"), groups.flatMap { it.threads }.map { it.id })
    }

    @Test fun conversationUpdatesDoNotMovePinnedRows() {
        val groups = SidebarThreadGrouping.makeGroups(listOf(a.copy(updatedAt = Instant.now()), b), listOf("b", "a"))
        assertEquals(listOf("b", "a"), groups.first().threads.map { it.id })
    }

    @Test fun missingArchivedAndSearchExcludedPinsDoNotCreatePhantomRows() {
        val groups = SidebarThreadGrouping.makeGroups(listOf(a, b.copy(syncState = CodexThreadSyncState.archivedLocal)), listOf("missing", "b", "chat"))
        assertFalse(groups.any { it.kind == SidebarThreadGroupKind.Pinned })
        assertEquals(listOf("a", "b"), groups.flatMap { it.threads }.map { it.id })
    }

    @Test fun unpinRestoresNormalProjectOrdering() {
        val groups = SidebarThreadGrouping.makeGroups(listOf(b, a), emptyList())
        assertEquals(listOf("alpha", "beta"), groups.map { it.label })
    }

    @Test fun pinsAreLimitedUntilExplicitExpansionAndDuplicateIdsAreRemoved() {
        val threads = (1..8).map { CodexThread("$it", cwd = "/project") }
        val ids = threads.map { it.id }
        val groups = SidebarThreadGrouping.applyGroupLimit(
            SidebarThreadGrouping.makeGroups(threads, ids + "1"), limit = 5,
        )
        assertEquals(ids.take(5), groups.single().visibleThreads.map { it.id })
        assertEquals(3, groups.single().hiddenCount)
        val expanded = SidebarThreadGrouping.applyGroupLimit(groups, limit = 5, expandedGroupIds = setOf("__pinned__"))
        assertEquals(ids, expanded.single().visibleThreads.map { it.id })
        assertEquals(0, expanded.single().hiddenCount)
        assertEquals(5, SidebarThreadGrouping.applyGroupLimit(expanded, limit = 5).single().visibleThreads.size)
    }
}
