package com.cursorforandroid.data.repo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Caps how many cloud subagent runs the transcript follows live at once (SCALE-7). Rows [track] interest while they
 * want a stream; [watched] is true only for the [MAX_STREAMS] most recently touched agents that still want one.
 * Everyone else reads the list row and the hub's look pass — no dedicated SSE subscriber.
 */
class SubagentStreamGate(private val maxStreams: Int = MAX_STREAMS) {
    private data class Slot(var wants: Boolean, var lastSeen: Long)

    private val lock = Any()
    private val slots = LinkedHashMap<String, Slot>()
    private val grants = HashMap<String, MutableStateFlow<Boolean>>()

    /** Whether [agentId]'s subagent stream should be watched on the hub when it has a run. */
    fun watched(agentId: String): StateFlow<Boolean> = synchronized(lock) {
        grants.getOrPut(agentId) { MutableStateFlow(false) }.asStateFlow()
    }

    /** Register or refresh interest in a live stream for [agentId]. */
    fun track(agentId: String) {
        synchronized(lock) {
            val now = System.nanoTime()
            slots.getOrPut(agentId) { Slot(true, now) }.also { it.wants = true; it.lastSeen = now }
            rebalance()
        }
    }

    /** Drop interest when the row leaves composition or no longer wants a stream. */
    fun untrack(agentId: String) {
        synchronized(lock) {
            slots.remove(agentId)
            rebalance()
        }
    }

    /** How many agents currently hold a stream grant (for diagnostics and benchmarks). */
    fun grantedCount(): Int = synchronized(lock) { grants.values.count { it.value } }

    private fun rebalance() {
        val winners = slots.filter { it.value.wants }
            .entries
            .sortedByDescending { it.value.lastSeen }
            .take(maxStreams)
            .map { it.key }
            .toSet()
        val touched = slots.keys + grants.keys
        for (id in touched) {
            val flow = grants.getOrPut(id) { MutableStateFlow(false) }
            val grant = id in winners
            if (flow.value != grant) flow.value = grant
        }
    }

    companion object {
        const val MAX_STREAMS = 8
    }
}
