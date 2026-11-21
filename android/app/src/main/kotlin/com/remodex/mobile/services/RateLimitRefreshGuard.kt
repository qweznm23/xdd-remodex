package com.remodex.mobile.services

import com.remodex.mobile.core.model.CodexRateLimitBucket
import com.remodex.mobile.core.model.accountWeeklyQuota

/** Caller synchronizes on this guard while publishing state, including connection resets and pushes. */
internal class RateLimitRefreshGuard(private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private var lastActivityMs: Long? = null
    var awaitingReconnect = false
        private set
    class Ticket(val generation: Long, val previousActivityMs: Long?) {
        // Only retain updates received after this particular read began.
        var pushedBuckets = emptyList<CodexRateLimitBucket>()
    }
    private var active: Ticket? = null
    fun begin(generation: Long, force: Boolean = false): Ticket? {
        if (awaitingReconnect || active?.generation == generation) return null
        val now = clockMs()
        if (!force && lastActivityMs?.let { now - it < 5 * 60_000L } == true) return null
        return Ticket(generation, lastActivityMs).also {
            active = it
            lastActivityMs = now
        }
    }
    fun isCurrent(ticket: Ticket, generation: Long) = active === ticket && ticket.generation == generation
    fun pushed(buckets: List<CodexRateLimitBucket>) {
        active?.let { it.pushedBuckets = RateLimitPayloadCodec.mergeRateLimitBuckets(it.pushedBuckets, buckets) }
        if (accountWeeklyQuota(buckets) != null) lastActivityMs = clockMs()
    }

    fun cancel(ticket: Ticket) {
        if (active === ticket && accountWeeklyQuota(ticket.pushedBuckets) == null) lastActivityMs = ticket.previousActivityMs
    }

    fun finish(ticket: Ticket) { if (active === ticket) active = null }
    fun reset(waitForReconnect: Boolean = false) {
        active = null
        lastActivityMs = null
        awaitingReconnect = waitForReconnect
    }
}
