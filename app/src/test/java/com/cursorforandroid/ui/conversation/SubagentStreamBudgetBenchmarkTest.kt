package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultRig
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.data.faults.Meter
import com.cursorforandroid.data.repo.SubagentActivity
import com.cursorforandroid.data.repo.SubagentStreamGate
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentPlacement
import com.cursorforandroid.domain.SubagentRows
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SCALE-7 at Bennett's peak: a Project coordinator's transcript with 120 cloud workers at once, started in eight
 * batches of fifteen, every one still working, each writing a step a beat. The transcript's stretches are drawn by the
 * real [StretchView] over the real [SubagentActivity], [LiveRunHub][com.cursorforandroid.data.repo.LiveRunHub] and an
 * HTTP/2 server that holds each run's stream open as the API does. As main draws it every worker's run is streamed
 * for as long as its stretch is composed; here only the child a closed line draws is, with the one before it looked
 * in on, and an opened stretch's rows stream through the rows' twelve places. What the lines say must not change.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SubagentStreamBudgetBenchmarkTest {

    @get:Rule val compose = createComposeRule()

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig
    private val now = 1_800_000_000_000L

    @After
    fun tearDown() {
        if (::rig.isInitialized) rig.close()
        if (::server.isInitialized) server.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    private val ids = (0 until BATCHES * PER_BATCH).map { "bc-worker-$it" }

    /** Eight turns of the coordinator's, each starting fifteen workers: eight stretches, closed. */
    private val transcript: List<TimelineItem> = buildList {
        repeat(BATCHES) { b ->
            add(UserMessage("u$b", "Start batch ${b + 1} of the migration."))
            add(
                ActivityGroup(
                    "g$b",
                    (0 until PER_BATCH).map { i ->
                        val n = b * PER_BATCH + i
                        ToolCall("t$n", "task", ToolKind.Task, ToolCall.STATUS_COMPLETED, "Worker $n", payload = ToolPayload.Subagent("Worker $n", agentId = ids[n], isBackground = true))
                    },
                ),
            )
            add(AssistantMessage("a$b", "Batch ${b + 1} is under way."))
            add(RunFooter("f$b", "run-coordinator-$b", RunStatus.FINISHED, 38_000, emptyList()))
        }
    }

    private class Sample {
        var serverStreams = 0
        var serverStreamSum = 0L
        var samples = 0
        var hubStreaming = 0
        var gateHolding = 0
        var gateWaiting = 0
        var appThreads = 0
        var streamRequests = 0
        val serverStreamMean get() = if (samples == 0) 0 else (serverStreamSum / samples).toInt()
    }

    private data class Phase(val sample: Sample, val lines: List<String>, val streamRequestsPerSecond: Double)

    private data class Result(val closed: Phase, val opened: Phase)

    private fun stat(stats: String, name: String): Int = Regex("\\b$name=(\\d+)").find(stats)!!.groupValues[1].toInt()

    private fun scenario(capped: Boolean): Result {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.clock = { now }
        server.liveRunStreams = true
        server.liveRunBeatMs = 500L
        server.liveRunGenerator = { runId, tick ->
            val worker = runId.removePrefix("run-bc-worker-")
            listOf(
                "tool_call" to """{"callId":"s$tick","name":"shell","status":"completed","args":{"command":"./gradlew test --tests T$tick"},"result":{"output":"PASS","exitCode":0}}""",
                "assistant" to """{"text":"Worker $worker at step $tick."}""",
            )
        }
        val coordinator = "bc-coordinator"
        server.composers[coordinator] = FaultServer.Composer(coordinator, "Coordinator", activityMs = now, project = true, running = true)
        ids.forEachIndexed { i, id ->
            server.agents[id] = AgentDto(id = id, name = "Worker $i", status = "ACTIVE", createdAt = iso(now - i), updatedAt = iso(now - i), latestRunId = "run-$id", url = "https://cursor.com/agents/$id")
            server.v0[id] = V0AgentDto(id = id, name = "Worker $i", status = "RUNNING")
            server.composers[id] = FaultServer.Composer(id, "Worker $i", activityMs = now - i, manager = coordinator, running = true)
            server.startTurnElsewhere(id, "Migrate slice $i.", "run-$id")
        }
        server.workers[coordinator] = ids.map { it to "MANAGER_SPAWN_KIND_CREATED" }
        rig = FaultRig(server.baseUrl, folder.newFolder("disk"), readTimeoutMs = 10_000L, extended = true, engine = TranscriptEngine.BETA, http2 = true).also { it.now = now }
        kotlinx.coroutines.runBlocking {
            rig.agents.refresh()
            repeat(3) { runCatching { rig.agents.loadMore() } }
        }
        assertThat(rig.agents.state.value.agents.count { it.isRunning && it.id in ids }).isEqualTo(ids.size)

        // Main's stretch streams every worker it holds and caps nothing: an unbounded gate and no list-only readers are that.
        val gate = SubagentStreamGate(maxStreams = if (capped) SubagentStreamGate.MAX_STREAMS else Int.MAX_VALUE)
        val activity = SubagentActivity(rig.agents, rig.hub, MutableStateFlow(LiveModelCatalog.models), gate)
        val rows = TranscriptRows.of(transcript, coordinatorMode = true)
        val controls = TranscriptControls(
            onOpenAgent = {},
            agentById = { id -> rig.agents.state.value.agents.firstOrNull { it.id == id } },
            coordinatorMode = true,
            models = LiveModelCatalog.models,
            subagents = SubagentRows.index(rows),
            placement = SubagentPlacement.Cloud,
            subagentActivity = activity::of,
            subagentListed = if (capped) activity::listed else null,
            subagentLine = if (capped) activity::line else null,
        )
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalTranscriptControls provides controls) {
                    Column { rows.forEach { TranscriptRowView(it) } }
                }
            }
        }
        compose.waitForIdle()

        val closed = phase(gate)
        compose.onAllNodes(hasClickAction() and hasParent(hasTestTag("stretch")))[BATCHES - 1].performClick()
        compose.waitForIdle()
        val opened = phase(gate)
        return Result(closed, opened).also { r ->
            listOf("closed" to r.closed, "opened" to r.opened).forEach { (name, p) ->
                val s = p.sample
                println(
                    "SCALE subagent_streams ${if (capped) "branch" else "main"} $name: serverRunStreams(peak=${s.serverStreams} mean=${s.serverStreamMean}) " +
                        "hubStreaming=${s.hubStreaming} gate(holding=${s.gateHolding} waiting=${s.gateWaiting}) appThreads=${s.appThreads} " +
                        "streamReq/s=${"%.1f".format(p.streamRequestsPerSecond)} lines=${p.lines}",
                )
            }
        }
    }

    /** [SETTLE_MS] for the streams to open, then [PHASE_MS] sampled every quarter second off the main thread. */
    private fun phase(gate: SubagentStreamGate): Phase {
        pump(SETTLE_MS)
        val into = Sample()
        val streamsBefore = server.requests(FaultServer.Route.Stream).size
        val sampler = Executors.newSingleThreadScheduledExecutor()
        sampler.scheduleAtFixedRate({
            val open = server.liveRunOpen.get()
            into.serverStreams = maxOf(into.serverStreams, open)
            into.serverStreamSum += open
            into.samples++
            into.hubStreaming = maxOf(into.hubStreaming, stat(rig.hub.stats(), "streaming"))
            val (holding, waiting) = gate.counts()
            into.gateHolding = maxOf(into.gateHolding, holding)
            into.gateWaiting = maxOf(into.gateWaiting, waiting)
            into.appThreads = maxOf(into.appThreads, Thread.getAllStackTraces().keys.count { Meter.isApp(it.name) })
        }, 0, 250, TimeUnit.MILLISECONDS)
        pump(PHASE_MS)
        sampler.shutdown()
        sampler.awaitTermination(1, TimeUnit.SECONDS)
        into.streamRequests = server.requests(FaultServer.Route.Stream).size - streamsBefore
        val lines = compose.onAllNodes(hasClickAction() and hasParent(hasTestTag("stretch"))).fetchSemanticsNodes().map { node ->
            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString(" ") { it.text }
                .ifEmpty { node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { emptyList() }.joinToString(" ") }
        }
        return Phase(into, lines, into.streamRequests * 1_000.0 / PHASE_MS)
    }

    private fun pump(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < until) {
            Thread.sleep(100)
            compose.waitForIdle()
        }
    }

    @Test
    fun `a coordinator's 120 working subagents stream only what their lines draw`() {
        val branch = scenario(capped = true)

        // Closed, a line draws one child at a time: one stream per line, and the one before it looked in on.
        assertThat(branch.closed.sample.gateHolding).isEqualTo(0)
        assertThat(branch.closed.sample.serverStreams).isAtMost(2 * BATCHES)
        assertThat(branch.closed.sample.hubStreaming).isAtMost(2 * BATCHES)
        // Opened, the stretch's thirteen rows past its line's two take the rows' twelve places; the lines take none.
        assertThat(branch.opened.sample.gateHolding).isEqualTo(SubagentStreamGate.MAX_STREAMS)
        assertThat(branch.opened.sample.gateWaiting).isEqualTo(PER_BATCH - 2 - SubagentStreamGate.MAX_STREAMS)
        assertThat(branch.opened.sample.serverStreams).isAtMost(SubagentStreamGate.MAX_STREAMS + 2 * BATCHES)
        // Every closed line still reads the newest worker's action off its stream.
        assertThat(branch.closed.lines.filter { "$PER_BATCH Working" in it && "at step" in it }).hasSize(BATCHES)
    }

    @Test
    fun `as main draws it, every one of the 120 is streamed`() {
        val main = scenario(capped = false)
        assertThat(main.closed.sample.serverStreams).isGreaterThan(4 * SubagentStreamGate.MAX_STREAMS)
        assertThat(main.closed.lines.filter { "$PER_BATCH Working" in it && "at step" in it }).hasSize(BATCHES)
    }

    private companion object {
        const val BATCHES = 8
        const val PER_BATCH = 15
        const val SETTLE_MS = 4_000L
        const val PHASE_MS = 8_000L
    }
}
