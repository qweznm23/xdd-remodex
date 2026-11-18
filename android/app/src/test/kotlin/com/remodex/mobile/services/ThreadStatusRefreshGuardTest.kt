package com.remodex.mobile.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadStatusRefreshGuardTest {
    @Test fun freshSnapshotCanUpdateUnopenedThreads() {
        val guard = ThreadStatusRefreshGuard()
        val snapshot = guard.begin()
        assertTrue(guard.accepts(snapshot, "unopened"))
    }

    @Test fun liveStartOrFinishWinsOverOlderListResponseWithoutBlockingOtherThreads() {
        val guard = ThreadStatusRefreshGuard()
        val snapshot = guard.begin()
        guard.changed("started")
        guard.changed("finished")
        assertFalse(guard.accepts(snapshot, "started"))
        assertFalse(guard.accepts(snapshot, "finished"))
        assertTrue(guard.accepts(snapshot, "unchanged"))
    }

    @Test fun newRefreshCanReconcilePreviouslyChangedThread() {
        val guard = ThreadStatusRefreshGuard()
        val old = guard.begin()
        guard.changed("a")
        val fresh = guard.begin()
        assertFalse(guard.accepts(old, "a"))
        assertTrue(guard.accepts(fresh, "a"))
        guard.changed("a")
        assertFalse(guard.accepts(fresh, "a"))
    }

    @Test fun completedOrCancelledRefreshCannotApplyLateState() {
        val guard = ThreadStatusRefreshGuard()
        val snapshot = guard.begin()
        guard.finish(snapshot)
        assertFalse(guard.accepts(snapshot, "a"))
        assertTrue(guard.accepts(guard.begin(), "a"))
    }
}
