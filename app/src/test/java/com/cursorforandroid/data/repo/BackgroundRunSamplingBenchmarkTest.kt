package com.cursorforandroid.data.repo

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.LiveActivityState
import com.cursorforandroid.domain.LivePhase
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.notifications.LiveNotificationRenderer
import com.cursorforandroid.notifications.LiveNotifications
import com.cursorforandroid.notifications.LivePostPacer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * SCALE-6: eight runs the live notification follows, each streaming twenty events a second for ten seconds, through
 * the real [LiveRunHub] and [RunMonitor] and the service's post path (pace, build, `notify`) on a thread of its own
 * standing in for Main. Counts the snapshots the hub built, the digests the monitor made, the notifications posted
 * and the CPU the Default pool and "Main" spent, printed as a `SCALE` line; asserts the posts stay at about one a
 * second and the digests at the monitor's sample rate, and that nothing the runs said was lost to the pacing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackgroundRunSamplingBenchmarkTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer(replay = 4_096)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainExecutor = Executors.newSingleThreadExecutor { Thread(it, MAIN_THREAD) }
    private val main = mainExecutor.asCoroutineDispatcher()
    private lateinit var monitor: RunMonitor

    @After
    fun tearDown() {
        if (::monitor.isInitialized) monitor.stop()
        scope.cancel()
        mainExecutor.shutdownNow()
    }

    @Test
    fun `eight streaming runs post the live notification about once a second`() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        LiveNotifications.ensureChannels(app)
        val prefs = PreferencesStore(app)
        val backend = CursorBackend(api, streamer, isDemo = true)
        val session = SessionManager(SecureKeyStore(app), prefs, backend, backend)
        session.enterDemo()
        val agents = AgentRepository(session, prefs, AttachmentStore(app))
        val hub = LiveRunHub(session, agents, pollIntervalMs = 500, releaseGraceMs = 200, scope = scope)
        monitor = RunMonitor(agents, hub, runRecord = { agentId, runId -> api.getRun(agentId, runId) }, refreshIntervalMs = 600_000)

        val ids = (0 until RUNS).map { "bc-$it" }
        ids.forEach { id ->
            api.addRunningAgent(id, "Worker $id", "run-$id")
            // A long turn already under way: the digest and the summary walk every item on each snapshot.
            streamer.emit("run-$id", RunStreamEvent.Status("run-$id", RunStatus.RUNNING))
            repeat(HISTORY_CALLS) { c ->
                streamer.emit("run-$id", RunStreamEvent.Thinking("Looking at part $c."))
                streamer.emit("run-$id", tool("$id-c$c", "src/File$c.kt"))
                streamer.emit("run-$id", RunStreamEvent.Assistant("Step $c done. "))
            }
        }
        agents.refresh()

        val posts = AtomicInteger()
        var shown: LiveActivityState? = null
        // The service's post path: the monitor's state, paced, built and posted on "Main".
        val pacer = withContext(main) {
            LivePostPacer(CoroutineScope(scope.coroutineContext + main)) { state ->
                shown = state
                LiveNotifications.post(app, LiveNotificationRenderer.LIVE_ID, LiveNotificationRenderer.live(app, state))
                posts.incrementAndGet()
            }
        }
        scope.launch(main) { monitor.state.collect { state -> if (state.running.isNotEmpty()) pacer.offer(state) else pacer.flush() } }
        monitor.start()
        withTimeout(30_000) {
            while (monitor.state.value.running.count { it.phase == LivePhase.Running } < RUNS || hub.current("bc-0", "run-bc-0")?.items.orEmpty().size < HISTORY_CALLS) delay(20)
        }
        delay(1_500)

        val publishedAt = hub.published.get()
        val digestsAt = monitor.digests.get()
        val postsAt = posts.get()
        val cpuAt = cpu()
        val started = System.nanoTime()
        var token = 0
        while (System.nanoTime() - started < STREAM_MS * 1_000_000) {
            ids.forEach { id -> streamer.emit("run-$id", RunStreamEvent.Assistant("tok$token ")) }
            token++
            delay(1_000L / EVENTS_PER_SECOND)
        }
        val streamedMs = (System.nanoTime() - started) / 1_000_000
        // The last period's post, then every run's reply as streamed, whole, in the notification's state.
        val lastToken = "tok${token - 1} "
        withTimeout(5_000) {
            while (monitor.state.value.running.any { !it.summary.orEmpty().endsWith(lastToken) } || shown?.running?.any { !it.summary.orEmpty().endsWith(lastToken) } != false) delay(20)
        }
        val cpuEnd = cpu()
        val published = hub.published.get() - publishedAt
        val digests = monitor.digests.get() - digestsAt
        val posted = posts.get() - postsAt
        val events = token * RUNS
        val seconds = streamedMs / 1_000.0

        // Each run's end reaches the monitor at once, not a period later.
        val finishedAt = System.nanoTime()
        ids.forEach { id ->
            api.runs["run-$id"] = api.runs.getValue("run-$id").copy(status = "FINISHED")
            streamer.emit("run-$id", RunStreamEvent.Result("run-$id", RunStatus.FINISHED, "Done.", 60_000, null))
            streamer.emit("run-$id", RunStreamEvent.Done)
        }
        withTimeout(10_000) { while (monitor.state.value.running.isNotEmpty()) delay(5) }
        val finishMs = (System.nanoTime() - finishedAt) / 1_000_000

        println(
            "SCALE background_run_sampling runs=$RUNS eventsPerRunPerSec=$EVENTS_PER_SECOND streamedMs=$streamedMs events=$events " +
                "snapshotsBuilt=$published digests=$digests notifyPosts=$posted postsPerSec=${f(posted / seconds)} " +
                "defaultCpuMs=${(cpuEnd.default - cpuAt.default) / 1_000_000} mainCpuMs=${(cpuEnd.main - cpuAt.main) / 1_000_000} " +
                "allFinishedMs=$finishMs",
        )
        // About one post a second (leading + trailing), not one per event.
        assertThat(posted).isAtMost((seconds + 3).toInt())
        // The monitor hears each run at its sample rate: ~2 a second, not 20.
        assertThat(digests).isAtMost((RUNS * (seconds * 1_000 / RunMonitor.SAMPLE_MS + 3)).toInt())
        assertThat(published).isAtMost((RUNS * (seconds * 1_000 / RunMonitor.SAMPLE_MS + 3)).toInt())
        assertThat(finishMs).isLessThan(RunMonitor.SAMPLE_MS * 4)
    }

    private fun tool(id: String, path: String) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = id, name = "read_file", status = "completed", args = buildJsonObject { put("path", JsonPrimitive(path)) }),
    )

    private class Cpu(val default: Long, val main: Long)

    // Through reflection: the unit tests compile against android.jar, which has no java.lang.management.
    private val threads: Any = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)!!
    private val cpuTimeOf = Class.forName("java.lang.management.ThreadMXBean").getMethod("getThreadCpuTime", Long::class.javaPrimitiveType)

    private fun cpu(): Cpu {
        var default = 0L
        var main = 0L
        Thread.getAllStackTraces().keys.forEach { thread ->
            val nanos = (cpuTimeOf.invoke(threads, thread.id) as Long).coerceAtLeast(0)
            when {
                thread.name.startsWith("DefaultDispatcher-worker") -> default += nanos
                thread.name == MAIN_THREAD -> main += nanos
            }
        }
        return Cpu(default, main)
    }

    private fun f(value: Double) = String.format(Locale.US, "%.2f", value)

    private companion object {
        const val RUNS = RunMonitor.MAX_TRACKED
        const val EVENTS_PER_SECOND = 20
        const val STREAM_MS = 10_000L
        const val HISTORY_CALLS = 150
        const val MAIN_THREAD = "bench-main"
    }
}
