package com.cursorforandroid.ui.conversation

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.RecomposeScopeObserver
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.panel.PanelViewModel
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Audit harness: what the side panel costs while it is closed, and its opening frames. The real conversation screen
 * over the demo graph (a 32-turn chat, a live run): reply deltas and tool calls streamed a frame at a time with the
 * panel shut, the panel view model's state emissions and the root composition's recomposed scopes counted; then the
 * panel opened with the clock held, each frame timed. Prints; asserts nothing.
 */
@OptIn(ExperimentalComposeRuntimeApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ClosedPanelCostAuditTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer(replay = 4_096)
    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private val agentId = "bc-panel-audit"
    private val turns = 32
    private val live = "run-$turns"
    private val watch = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        watch.cancel()
        AppClock.nowMillis = System::currentTimeMillis
    }

    private class Recompositions : CompositionObserver, RecomposeScopeObserver {
        var scopes = 0
        private val observed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<RecomposeScope, Boolean>())
        override fun onBeginComposition(composition: Composition, invalidationMap: Map<RecomposeScope, Set<Any>?>) {
            for (scope in invalidationMap.keys) if (observed.add(scope)) scope.observe(this)
        }
        override fun onEndComposition(composition: Composition) = Unit
        override fun onBeginScopeComposition(scope: RecomposeScope) { scopes++ }
        override fun onEndScopeComposition(scope: RecomposeScope) = Unit
        override fun onScopeDisposed(scope: RecomposeScope) { observed.remove(scope) }
    }

    private fun call(runId: String, n: Int, status: String = "completed") = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "$runId-c$n",
            name = "read_file",
            status = status,
            args = buildJsonObject { put("path", JsonPrimitive("app/src/main/java/com/example/module$n/File$n.kt")) },
            result = if (status == "completed") buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("line\n".repeat(40))); put("path", JsonPrimitive("app/src/File$n.kt")) }) } else null,
        ),
    )

    private fun frame(): Double {
        val t0 = System.nanoTime()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        return (System.nanoTime() - t0) / 1e6
    }

    private fun emit(vararg events: RunStreamEvent) = runBlocking { events.forEach { streamer.emit(live, it) } }

    @Test
    fun `closed panel cost while streaming, and the opening frames`() {
        AppClock.nowMillis = { now }
        val prompts = Array(turns) { Triple("run-${it + 1}", "Prompt ${it + 1}", "Reply ${it + 1}") }
        api.addFinishedAgent(agentId, "Panel audit", *prompts, firstRunAt = Instant.ofEpochMilli(now - turns * 3_600_000L).toString())
        api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
        api.transcripts[agentId] = api.transcripts.getValue(agentId).dropLast(1)
        runBlocking {
            for (i in 1 until turns) {
                val runId = "run-$i"
                streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
                repeat(12) { n -> streamer.emit(runId, call(runId, n + 1)) }
                streamer.emit(runId, RunStreamEvent.Assistant("Reply $i"))
                streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $i", 30_000, null))
                streamer.emit(runId, RunStreamEvent.Done)
            }
            streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, streamer, isDemo = true))
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        val recompositions = Recompositions()
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(recompositions) }
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) { ConversationScreen(graph, agentId, onBack = {}) }
            }
        }
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("Prompt $turns", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(120_000) { graph.conversations.state(agentId).value.let { !it.isLoading && it.traceStatus.pending == 0 } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        repeat(60) { frame() }

        val panelVm = ViewModelProvider(compose.activity, PanelViewModel.Factory(graph, agentId))["panel-$agentId", PanelViewModel::class.java]
        val emissions = AtomicInteger()
        watch.launch { panelVm.state.collect { emissions.incrementAndGet() } }
        Thread.sleep(200)

        fun phase(label: String, frames: Int, step: (Int) -> Unit) {
            val e0 = emissions.get()
            val s0 = recompositions.scopes
            val walls = ArrayList<Double>()
            repeat(frames) { i ->
                step(i)
                Thread.sleep(4)
                walls += frame()
            }
            repeat(10) { frame() }
            Thread.sleep(300)
            println("AUDIT[$label] frames=$frames panelStateEmissions=${emissions.get() - e0} rootScopes=${recompositions.scopes - s0} wall median=${"%.2f".format(walls.sorted()[walls.size / 2])}ms max=${"%.2f".format(walls.max())}ms")
        }

        var text = ""
        phase("panel closed: 120 reply deltas", 120) { d -> text = "word$d "; emit(RunStreamEvent.Assistant(text)) }
        phase("panel closed: 40 tool calls landing", 40) { n -> emit(call(live, 100 + n)) }
        phase("panel closed: idle", 30) { }

        compose.onNodeWithContentDescription("Open panel").performClick()
        val opening = List(30) { frame() }
        println("AUDIT[panel open] first=${"%.1f".format(opening[0])}ms second=${"%.1f".format(opening[1])}ms max=${"%.1f".format(opening.max())}ms total30=${"%.1f".format(opening.sum())}ms")
        phase("panel open: 60 reply deltas", 60) { d -> text = "open$d "; emit(RunStreamEvent.Assistant(text)) }
    }
}
