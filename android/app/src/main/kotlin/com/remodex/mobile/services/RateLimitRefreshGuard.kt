package com.remodex.mobile.services

/** Caller synchronizes on this guard while publishing state, including connection resets and pushes. */
internal class RateLimitRefreshGuard {
    class Ticket(val generation: Long, val pushRevision: Long)
    private var active: Ticket? = null
    private var pushRevision = 0L
    fun begin(generation: Long): Ticket? {
        if (active?.generation == generation) return null
        return Ticket(generation, pushRevision).also { active = it }
    }
    fun isCurrent(ticket: Ticket, generation: Long) = active === ticket && ticket.generation == generation
    fun hasNewerPush(ticket: Ticket) = ticket.pushRevision != pushRevision
    fun pushed() { pushRevision++ }
    fun finish(ticket: Ticket) { if (active === ticket) active = null }
    fun reset() { active = null; pushRevision++ }
}
