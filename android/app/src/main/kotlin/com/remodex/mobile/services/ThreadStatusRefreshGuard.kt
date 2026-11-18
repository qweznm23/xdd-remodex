package com.remodex.mobile.services

/** Access under runningTurnStateLock. Tracks only requests currently in flight. */
internal class ThreadStatusRefreshGuard {
    class Snapshot internal constructor() {
        internal val changedThreads = mutableSetOf<String>()
    }
    private val snapshots = mutableSetOf<Snapshot>()

    fun begin(): Snapshot = Snapshot().also { snapshots += it }
    fun changed(threadId: String) { snapshots.forEach { it.changedThreads += threadId } }
    fun accepts(snapshot: Snapshot, threadId: String): Boolean =
        snapshot in snapshots && threadId !in snapshot.changedThreads
    fun finish(snapshot: Snapshot) { snapshots -= snapshot }
}
