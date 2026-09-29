package com.cursorforandroid.notifications

import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.TrackedRun
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The live notification's posts: at most one per period, the latest state always posted last, a run's end at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePostPacerTest {

    private fun run(id: String, summary: String, phase: LivePhase = LivePhase.Running) =
        TrackedRun(id, "run-$id", "Agent $id", RunStatus.RUNNING, phase, startedAtMillis = 0L, summary = summary)

    private fun state(vararg runs: TrackedRun) = LiveActivityState(running = runs.toList(), hasReconciled = true, runningCount = runs.size)

    @Test
    fun `the first change goes out at once, the rest of the period's as one post of the latest when it closes`() = runTest {
        val posts = ArrayList<LiveActivityState>()
        val pacer = LivePostPacer(backgroundScope, periodMs = 1_000) { posts += it }

        pacer.offer(state(run("a", "one")))
        assertThat(posts.map { it.running.single().summary }).containsExactly("one")
        repeat(20) { i ->
            advanceTimeBy(40)
            pacer.offer(state(run("a", "token $i")))
        }
        // 800 ms in: still the first.
        assertThat(posts).hasSize(1)
        advanceTimeBy(201)
        runCurrent()
        assertThat(posts.map { it.running.single().summary }).containsExactly("one", "token 19").inOrder()
        // Nothing new: the period ends without a post, and the next change goes out at once again.
        advanceTimeBy(2_000)
        assertThat(posts).hasSize(2)
        pacer.offer(state(run("a", "later")))
        assertThat(posts.last().running.single().summary).isEqualTo("later")
    }

    @Test
    fun `tokens streaming for ten seconds post at most once a second`() = runTest {
        var posts = 0
        val pacer = LivePostPacer(backgroundScope, periodMs = 1_000) { posts++ }
        // Eight runs, each changing twenty times a second.
        repeat(10 * 20 * 8) { i ->
            pacer.offer(state(*Array(8) { r -> run("r$r", "t${if (r == i % 8) i else 0}") }))
            advanceTimeBy(1_000L / (20 * 8))
        }
        advanceTimeBy(1_001)
        runCurrent()
        assertThat(posts).isAtMost(12)
        assertThat(posts).isAtLeast(9)
    }

    @Test
    fun `a run leaving or asked to stop is posted at once, whatever the period`() = runTest {
        val posts = ArrayList<LiveActivityState>()
        val pacer = LivePostPacer(backgroundScope, periodMs = 1_000) { posts += it }
        pacer.offer(state(run("a", "one"), run("b", "two")))
        advanceTimeBy(100)
        pacer.offer(state(run("a", "one", LivePhase.Stopping), run("b", "two")))
        assertThat(posts.last().running.first().phase).isEqualTo(LivePhase.Stopping)
        advanceTimeBy(100)
        pacer.offer(state(run("b", "two")))
        assertThat(posts).hasSize(3)
        assertThat(posts.last().running.map { it.agentId }).containsExactly("b")
    }

    @Test
    fun `flush posts what the period holds back, cancel drops it`() = runTest {
        val posts = ArrayList<LiveActivityState>()
        val pacer = LivePostPacer(backgroundScope, periodMs = 1_000) { posts += it }
        pacer.offer(state(run("a", "one")))
        advanceTimeBy(100)
        pacer.offer(state(run("a", "two")))
        pacer.flush()
        assertThat(posts.map { it.running.single().summary }).containsExactly("one", "two").inOrder()
        advanceTimeBy(5_000)
        assertThat(posts).hasSize(2)

        pacer.offer(state(run("a", "three")))
        advanceTimeBy(100)
        pacer.offer(state(run("a", "four")))
        pacer.cancel()
        advanceTimeBy(5_000)
        assertThat(posts.map { it.running.single().summary }).containsExactly("one", "two", "three").inOrder()
    }
}
