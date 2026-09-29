package com.cursorforandroid.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How many of the cloud subagent runs the transcripts follow may be streamed at once. Every stream is a connection
 * and a thread of the 64 the hub's streams share with the chat on screen, its holds and the live notification, so a
 * coordinator with dozens of running workers on screen must not take one each (SCALE-7).
 *
 * Each follower holds a [Ticket] while it follows. The first [maxStreams] tickets are watched; the rest wait, in the
 * order they came, and meanwhile follow their run the way the hub follows one nobody watches (looked in on every so
 * often, see `LiveRunHub.snapshots`). A ticket given back hands its place to the one that has waited longest: a
 * follower keeps its place for as long as it follows, so the rows on screen do not trade their streams as more
 * arrive.
 */
class SubagentStreamGate(private val maxStreams: Int = MAX_STREAMS) {
    /** One follower's place; [watched] says whether it may stream. */
    class Ticket internal constructor() {
        internal val state = MutableStateFlow(false)
        val watched: StateFlow<Boolean> = state.asStateFlow()
    }

    private val lock = Any()
    private val holding = LinkedHashSet<Ticket>()
    private val waiting = ArrayDeque<Ticket>()

    fun acquire(): Ticket = Ticket().also { ticket ->
        synchronized(lock) {
            if (holding.size < maxStreams) grant(ticket) else waiting.addLast(ticket)
        }
    }

    /** Gives [ticket] back; idempotent. */
    fun release(ticket: Ticket) {
        synchronized(lock) {
            if (holding.remove(ticket)) {
                ticket.state.value = false
                while (holding.size < maxStreams) grant(waiting.removeFirstOrNull() ?: break)
            } else {
                waiting.remove(ticket)
            }
        }
    }

    /** Tickets watched now, and tickets waiting; for diagnostics and the benchmarks. */
    fun counts(): Pair<Int, Int> = synchronized(lock) { holding.size to waiting.size }

    private fun grant(ticket: Ticket) {
        holding += ticket
        ticket.state.value = true
    }

    companion object {
        /** About a phone screen of subagent rows, with room left in the hub's 64 threads for everything else. */
        const val MAX_STREAMS = 12
    }
}
