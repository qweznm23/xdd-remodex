package com.remodex.mobile.ui.sidebar

import com.remodex.mobile.core.model.CodexThread
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionProjectIndexTest {
    @Test fun projectDirectoryAnchorsDoNotDependOnConversationCounts() {
        val threads = (0..7).map { CodexThread(id = "thread-$it", cwd = if (it < 6) "/a/demo" else "/b/demo") }
        val entries = projectIndexEntries(SidebarThreadGrouping.makeGroups(threads))
        assertEquals(listOf(0, 1), entries.map { it.row })
        assertEquals(setOf("/a/demo", "/b/demo"), entries.map { it.path }.toSet())
    }

    @Test fun homeUsesServerOrderAndLabelsFallbackSeparately() {
        val rows = (1..5).map { CodexThread(id = it.toString(), updatedAt = Instant.ofEpochSecond(it.toLong())) }
        val groups = sidebarHomeGroups(rows, listOf("2"), listOf("1", "4", "2", "3"), setOf("3"))
        assertEquals(listOf(listOf("3"), listOf("2"), listOf("1", "4")), groups.map { it.threads.map(CodexThread::id) })
        val fallback = sidebarHomeGroups(rows, emptyList(), listOf("missing"), emptySet()).single()
        assertEquals(SidebarThreadGroupKind.Updated, fallback.kind)
        assertEquals(listOf("5", "4", "3", "2", "1"), fallback.threads.map(CodexThread::id))
    }

    @Test fun similarlyNamedProjectsHaveDistinctShortLabels() {
        val anchors = listOf("Project-01","Project-05","Project-08","Project-12").mapIndexed { row,name ->
            ProjectAnchor(name,name,"/qa/$name",row)
        }
        assertEquals(listOf("01","05","08","12"), projectIndexLabels(anchors))
    }

    @Test fun flutterFlingArrivesAtItsDeclaredDistanceWithoutChangingDirection() {
        val forward = PrismClampingMotion(1600.0)
        val reverse = PrismClampingMotion(-1600.0)
        assertEquals(0.0, forward.position(0.0), 0.00001)
        assertEquals(forward.distance, forward.position(forward.duration), 0.00001)
        assertEquals(-forward.distance, reverse.distance, 0.00001)
        assertEquals(0.0, reverse.velocity(reverse.duration), 0.00001)
    }
}
