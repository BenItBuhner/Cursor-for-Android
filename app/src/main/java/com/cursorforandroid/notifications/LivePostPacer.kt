package com.cursorforandroid.notifications

import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Paces the live notification's posts. Its text is the newest reply of each run it follows, so with a few runs
 * streaming it changes many times a second, and Android drops a package's updates past about five a second after
 * each has been built and sent. A change goes out at once when nothing went out for [periodMs]; the changes after it
 * are folded into one post at the end of the period, the latest winning, so the last state is always the one shown.
 * A run leaving the notification, or asked to stop, goes out at once whatever the period.
 *
 * Not thread-safe: [offer], [flush] and [cancel] are called on [scope]'s single thread, as the service's are on Main.
 */
internal class LivePostPacer(
    private val scope: CoroutineScope,
    private val periodMs: Long = PERIOD_MS,
    private val post: (LiveActivityState) -> Unit,
) {
    private var last: LiveActivityState? = null
    private var pending: LiveActivityState? = null
    private var period: Job? = null

    fun offer(state: LiveActivityState) {
        val shown = last
        if (period?.isActive == true && shown != null && !ends(shown, state)) {
            pending = state
            return
        }
        period?.cancel()
        emit(state)
        period = scope.launch {
            while (true) {
                delay(periodMs)
                val next = pending ?: break
                emit(next)
            }
        }
    }

    /** Posts what the period is holding back now, and ends the period. */
    fun flush() {
        period?.cancel()
        period = null
        pending?.let(::emit)
    }

    /** Drops what the period is holding back, and ends it. */
    fun cancel() {
        period?.cancel()
        period = null
        pending = null
    }

    private fun emit(state: LiveActivityState) {
        pending = null
        last = state
        post(state)
    }

    private fun ends(shown: LiveActivityState, next: LiveActivityState): Boolean {
        val phases = next.running.associate { it.agentId to it.phase }
        return shown.running.any { run ->
            val phase = phases[run.agentId]
            phase == null || (phase != run.phase && (phase == LivePhase.Stopping || phase == LivePhase.Finished))
        }
    }

    companion object {
        const val PERIOD_MS = 1_000L
    }
}
