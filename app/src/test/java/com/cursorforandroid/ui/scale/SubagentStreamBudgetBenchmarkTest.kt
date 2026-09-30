package com.cursorforandroid.ui.scale

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.fixtures.ScaleFleet
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A Project coordinator's transcript with 128 cloud workers all working at once ([SubagentStretchScene]: seven
 * stretches closed, the eighth open, its rows listed below it as far as the phone's screen reaches), inside a whole
 * fleet ([ScaleFleet], S200 and S500) that ticks every second. Every worker's run
 * streams about a hundred events a second (never more) through the app's [LiveRunHub][com.cursorforandroid.data.repo.LiveRunHub]
 * and [SubagentActivity][com.cursorforandroid.data.repo.SubagentActivity]: thinking, a file read, a step announced, a
 * reply a word at a time. Frames are paced at sixty a second of real time with the clock held (see [ScaleMeter]).
 *
 * `SCALE subagent-streams` reports the frames' wall and main-thread CPU times, the main thread's allocations, the
 * scopes recomposed per frame, the shared dispatcher pool's CPU and allocations (the hub's reading of the streams
 * included: IO and Default share it), how many of the subagents' states were published per second and how many of
 * their derivations reached the main thread, and the streams: one per worker, every one read, counted and not capped.
 *
 * `SCALE_FLEETS=S200,S500` measures S500 as well (the default is S50 and S200).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SubagentStreamBudgetBenchmarkTest {

    /**
     * Effects queued and run on the main thread, in its frames, as a phone's main looper runs them. The rule's own
     * dispatcher is unconfined: a collector woken by the hub's IO thread would go on on that thread, and the work a
     * phone does on its main thread would not be measured there.
     */
    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(StandardTestDispatcher())

    private val now = Instant.parse("2026-09-28T12:00:00Z").toEpochMilli()
    private lateinit var rig: ScaleRig

    @After
    fun tearDown() {
        if (::rig.isInitialized) rig.tearDown()
    }

    @Test
    fun `0 warm-up on S50, unmeasured`() {
        assumeTrue("warm-up for S200", ScaleFleet.Size.S200 in ScaleFleet.Size.enabled())
        scenario(ScaleFleet.Size.S50, frames = WARM_UP_FRAMES)
    }

    @Test
    fun `1 S200`() = measure(ScaleFleet.Size.S200)

    @Test
    fun `2 S500`() = measure(ScaleFleet.Size.S500)

    private fun measure(size: ScaleFleet.Size) {
        assumeTrue("$size is not in SCALE_FLEETS", size in ScaleFleet.Size.enabled())
        val r = scenario(size, frames = MEASURED_FRAMES)
        println(r.phase.line())
        val line = r.phase.line()
        // Every worker has its stream, and every stream is read to its newest event.
        assertWithMessage(line).that(r.streams).isEqualTo(WORKERS)
        assertWithMessage(line).that(r.unread).isEqualTo(0)
        assertWithMessage(line).that(r.workingLines).isEqualTo(STRETCHES)
        // One collector per worker's run, shared by its row and its stretch's line.
        assertWithMessage(line).that(r.hubSubscribers).isAtMost(WORKERS)
        // What the subagents' states cost the main thread: derivations never on it, one publication a frame for all.
        assertWithMessage(line).that(r.mainDeliveriesPerSecond).isAtMost(MAX_MAIN_DELIVERIES_PER_SEC)
        assertWithMessage(line).that(r.phase.allocated / 1024.0 / r.phase.frames).isAtMost(MAX_MAIN_ALLOC_KB_PER_FRAME)
        assertWithMessage(line).that(r.phase.scopes.toDouble() / r.phase.frames).isAtMost(MAX_SCOPES_PER_FRAME)
    }

    private class Result(
        val phase: ScaleMeter.Phase,
        val streams: Int,
        val unread: Int,
        val workingLines: Int,
        val hubSubscribers: Int,
        val mainDeliveriesPerSecond: Double,
    )

    private val workers = SubagentStretchScene.workers

    private fun scenario(size: ScaleFleet.Size, frames: Int): Result {
        rig = ScaleRig(compose, size, now)
        SubagentStretchScene.install(rig.api, now)
        rig.start(bigTurns = BIG_TURNS)
        val graph = rig.graph
        val meter = rig.meter
        runBlocking { workers.forEach { rig.streamer.emit("run-$it", RunStreamEvent.Status("run-$it", RunStatus.RUNNING)) } }

        val mainLooper = Looper.getMainLooper()
        val mainDeliveries = AtomicLong()
        val deliveries = AtomicLong()
        val source: (String) -> Flow<SubagentChild?> = { id ->
            graph.subagentActivity.of(id).onEach {
                deliveries.incrementAndGet()
                if (Looper.myLooper() == mainLooper) mainDeliveries.incrementAndGet()
            }
        }
        compose.setContent { SubagentStretchScene.Screen(graph, source, meter) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        val driver = StreamDriver(rig)
        driver.start()
        try {
            paced(null, WARM_FRAMES)
            val publications = AtomicLong()
            val writes = Snapshot.registerGlobalWriteObserver { written -> if ((written as? State<*>)?.value is SubagentChild) publications.incrementAndGet() }
            val phase = rig.phase("subagent-streams")
            val deliveries0 = deliveries.get()
            val main0 = mainDeliveries.get()
            val started = System.nanoTime()
            paced(phase, frames)
            meter.end(phase)
            writes.dispose()
            val realSeconds = (System.nanoTime() - started) / 1e9
            val mainPerSec = (mainDeliveries.get() - main0) / realSeconds
            val mainCpuPerSec = phase.cpu.sum() / realSeconds
            phase.extra["realSec"] = ScaleMeter.Phase.f(realSeconds)
            phase.extra["fps"] = ScaleMeter.Phase.f(phase.frames / realSeconds)
            phase.extra["mainCpuMsPerRealSec"] = ScaleMeter.Phase.f(mainCpuPerSec)
            phase.extra["mainAllocKBPerFrame"] = (phase.allocated / 1024 / phase.frames.coerceAtLeast(1))
            phase.extra["scopesPerFrame"] = ScaleMeter.Phase.f(phase.scopes.toDouble() / phase.frames.coerceAtLeast(1))
            phase.extra["publicationsPerSec"] = ScaleMeter.Phase.f(publications.get() / realSeconds)
            phase.extra["derivationsPerSec"] = ScaleMeter.Phase.f((deliveries.get() - deliveries0) / realSeconds)
            phase.extra["mainDeliveriesPerSec"] = ScaleMeter.Phase.f(mainPerSec)
            phase.extra["eventsPerSec"] = ScaleMeter.Phase.f(driver.emittedSince(started) / realSeconds)
            phase.extra["streams"] = rig.streamer.connections.distinct().count { it.startsWith("run-bc-sub-") }
            phase.extra["connections"] = rig.streamer.connections.count { it.startsWith("run-bc-sub-") }
            val hub = graph.liveRuns.stats()
            phase.extra["hub"] = hub.replace(' ', ',')
            driver.stop()
            // What every stream said has reached the hub: nothing held back, nothing sampled.
            meter.settle(null, quiet = 3, max = 60)
            val unread = workers.count { id -> (graph.liveRuns.current(id, "run-$id")?.eventCount ?: 0) < driver.emitted(id) }
            val lines = SubagentStretchScene.lines(compose)
            phase.extra["stretchesShown"] = lines.size
            phase.extra["rowsShown"] = compose.onAllNodes(hasTestTag("subagent-row")).fetchSemanticsNodes().size
            phase.extra["unread"] = unread
            return Result(
                phase = phase,
                streams = phase.extra["streams"] as Int,
                unread = unread,
                workingLines = lines.count { "$PER_STRETCH Working" in it },
                hubSubscribers = Regex("subscribers=(\\d+)").find(hub)!!.groupValues[1].toInt(),
                mainDeliveriesPerSecond = mainPerSec,
            )
        } finally {
            driver.stop()
        }
    }

    /** [frames] frames at sixty a second of real time, the fleet ticking once a second. */
    private fun paced(phase: ScaleMeter.Phase?, frames: Int) {
        val t0 = System.nanoTime()
        for (i in 0 until frames) {
            val due = t0 + i * FRAME_NANOS
            val wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            if (i % 60 == 59) rig.fleet.deliver(rig.api, rig.graph, rig.fleet.tick())
            rig.meter.frame(phase)
        }
    }

    /**
     * Every worker's run, an event each [BEAT_MS] (a hundred a second, never more), off the main thread: see
     * [WorkerScript]. Each worker starts at its own place in the script, so they do not move in step.
     */
    private class StreamDriver(private val rig: ScaleRig) {
        private val runs = (0 until WORKERS).map(SubagentStretchScene::run)
        private val ticks = LongArray(WORKERS)
        private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "stream-driver").apply { isDaemon = true } }
        private val log = ArrayList<Pair<Long, Int>>()

        fun start() {
            executor.scheduleAtFixedRate({
                runBlocking {
                    for (w in 0 until WORKERS) {
                        val k = ticks[w]
                        rig.streamer.emit(runs[w], WorkerScript.event(w, k))
                        ticks[w] = k + 1
                    }
                }
                synchronized(log) { log += System.nanoTime() to WORKERS }
            }, 0, BEAT_MS, TimeUnit.MILLISECONDS)
        }

        fun stop() {
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }

        /** Events emitted on [id]'s run so far, its opening status included. */
        fun emitted(id: String): Int = ticks[runs.indexOf("run-$id")].toInt() + 1

        fun emittedSince(t: Long): Long = synchronized(log) { log.filter { it.first >= t }.sumOf { it.second.toLong() } }
    }

    private companion object {
        const val STRETCHES = SubagentStretchScene.STRETCHES
        const val PER_STRETCH = SubagentStretchScene.PER_STRETCH
        const val WORKERS = SubagentStretchScene.WORKERS
        const val BIG_TURNS = 20
        const val BEAT_MS = 10L
        const val FRAME_NANOS = 16_666_667L
        const val WARM_FRAMES = 180
        const val WARM_UP_FRAMES = 300
        const val MEASURED_FRAMES = 600

        const val MAX_MAIN_DELIVERIES_PER_SEC = 60.0
        const val MAX_MAIN_ALLOC_KB_PER_FRAME = 600.0
        const val MAX_SCOPES_PER_FRAME = 6.0
    }
}
