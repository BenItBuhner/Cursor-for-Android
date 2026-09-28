package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.RecomposeScopeObserver
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A keystroke in the follow-up composer recomposes the composer and nothing else on the chat screen: not the screen's
 * dock and side-panel content, not the transcript's lazy layout, however long the chat. The composer alone, with a
 * hoisted string, costs 4 scopes a keystroke; the screen cost 7 (and re-derived an O(items) id set) while it read the
 * draft at its own level.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ChatKeystrokeScopeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private val agentId = "bc-keystroke-scope"
    private val rec = Recompositions()
    private var data: CompositionData? = null

    @Before fun setUp() { AppClock.nowMillis = { now } }

    @After fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        TranscriptPerf.clearAll()
    }

    @Test
    fun `a keystroke recomposes the composer alone`() {
        val turns = Array(24) { Triple("run-${it + 1}", "Prompt ${it + 1}: tighten module ${it + 1} and list the files.", "Reply ${it + 1}\n\n- `File.kt`: split\n\n```kotlin\nval x = $it\n```") }
        open(turns, newest = "Prompt 24")
        assertComposerAlone(measure("24 turns"))
    }

    @Test
    fun `a keystroke recomposes the composer alone on a chat of thousands of items`() {
        val runs = 560
        val turns = Array(runs) { Triple("run-${it + 1}", "Prompt ${it + 1}: tighten module ${it + 1}.", "Reply ${it + 1}") }
        for (i in 1..runs) runBlocking {
            val runId = "run-$i"
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            repeat(2) { n ->
                streamer.emit(
                    runId,
                    RunStreamEvent.ToolCall(
                        SseToolCallDto(
                            callId = "$runId-c$n",
                            name = "read_file",
                            status = "completed",
                            args = buildJsonObject { put("path", JsonPrimitive("app/src/File$n.kt")) },
                            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("val x = $n")) }) },
                        ),
                    ),
                )
            }
            streamer.emit(runId, RunStreamEvent.Assistant("Reply $i"))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, "Reply $i", 30_000, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        val graph = open(turns, newest = "Prompt $runs")
        var pages = 0
        while (pages < 80) {
            val state = graph.conversations.state(agentId).value
            if (!state.hasOlder && !state.isLoadingOlder) break
            val before = state.items.size
            graph.conversations.loadOlder(agentId)
            compose.waitUntil(60_000) { graph.conversations.state(agentId).value.let { s -> !s.isLoadingOlder && (s.items.size > before || !s.hasOlder) } }
            compose.waitForIdle()
            pages++
        }
        compose.waitUntil(120_000) { graph.conversations.state(agentId).value.traceStatus.pending == 0 }
        compose.waitForIdle()
        val viewModel = ViewModelProvider(compose.activity)["conversation-$agentId", ConversationViewModel::class.java]
        val items = viewModel.presented.value.items.size
        assertThat(items).isAtLeast(2_000)
        assertComposerAlone(measure("$items items"))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open(turns: Array<Triple<String, String, String>>, newest: String): AppGraph {
        api.addFinishedAgent(agentId, "Keystroke chat", *turns, firstRunAt = Instant.ofEpochMilli(now - turns.size * 3_600_000L).toString())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, streamer, isDemo = true))
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(rec) }
            val d = currentComposer.compositionData
            remember { data = d }
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    SendMotionHost { ConversationScreen(graph, agentId, onBack = {}) }
                }
            }
        }
        compose.waitUntil(60_000) { compose.onAllNodes(hasText(newest, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return graph
    }

    private class Measured(val scopesPerKeystroke: Double, val byName: Map<String, Int>, val rowsComposed: Int)

    private fun measure(label: String, keystrokes: Int = 60): Measured {
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("follow-up-composer")))
        field.performClick()
        repeat(20) { field.performTextInput("w"); compose.waitForIdle() }
        rec.observeAll(data!!)
        rec.reset()
        val rows0 = rowsComposed()
        var allocated = 0L
        repeat(keystrokes) { i ->
            val before = allocatedBytes()
            field.performTextInput(if (i % 6 == 5) " " else "a")
            compose.waitForIdle()
            allocated += allocatedBytes() - before
        }
        val measured = Measured(rec.scopes / keystrokes.toDouble(), HashMap(rec.byName), rowsComposed() - rows0)
        println(
            "keystroke [$label] scopes/keystroke=${"%.1f".format(measured.scopesPerKeystroke)} alloc/keystroke=${allocated / keystrokes / 1024}KB " +
                "rowsComposed=${measured.rowsComposed}\n" + measured.byName.entries.sortedByDescending { it.value }.joinToString("\n") { "  ${it.value}x ${it.key}" },
        )
        return measured
    }

    private fun assertComposerAlone(measured: Measured) {
        assertThat(measured.rowsComposed).isEqualTo(0)
        assertThat(measured.byName.keys.filter { "LazyLayout" in it }).isEmpty()
        // The composer's own 4, with one to spare.
        assertThat(measured.scopesPerKeystroke).isAtMost(5.0)
    }

    private fun rowsComposed(): Int = TranscriptPerf.sessionOrNull(agentId)?.snapshot()?.rowCompositions ?: 0

    private fun allocatedBytes(): Long = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes").invoke(bean) as Long
    }.getOrDefault(0L)

    /** Scopes recomposed, named by the class of the lambda each one restarts (the composable or content lambda it belongs to). */
    private class Recompositions : CompositionObserver, RecomposeScopeObserver {
        var scopes = 0
        val byName = HashMap<String, Int>()
        private val observed = Collections.newSetFromMap(IdentityHashMap<RecomposeScope, Boolean>())

        override fun onBeginComposition(composition: Composition, invalidationMap: Map<RecomposeScope, Set<Any>?>) {
            for (scope in invalidationMap.keys) if (observed.add(scope)) scope.observe(this)
        }
        override fun onEndComposition(composition: Composition) = Unit
        override fun onBeginScopeComposition(scope: RecomposeScope) {
            scopes++
            val name = nameOf(scope)
            byName[name] = (byName[name] ?: 0) + 1
        }
        override fun onEndScopeComposition(scope: RecomposeScope) = Unit
        override fun onScopeDisposed(scope: RecomposeScope) { observed.remove(scope) }

        /** Observes every scope in [data]'s groups, not only the invalidated ones, so children recomposed by a changed parameter count too. */
        fun observeAll(data: CompositionData) {
            fun walk(g: CompositionGroup) {
                for (d in g.data) {
                    if (d is RecomposeScope && observed.add(d)) d.observe(this)
                    subcompositions(d).forEach { sub -> sub.compositionGroups.forEach(::walk) }
                }
                for (c in g.compositionGroups) walk(c)
            }
            for (g in data.compositionGroups) walk(g)
        }

        fun reset() { scopes = 0; byName.clear() }

        /** The slot tables of subcompositions (BoxWithConstraints, LazyColumn items…) hanging off a remembered context. */
        private fun subcompositions(d: Any?): List<CompositionData> = runCatching {
            var x: Any? = d ?: return emptyList()
            if (x!!.javaClass.name.endsWith("RememberObserverHolder")) x = x.javaClass.getDeclaredField("wrapped").apply { isAccessible = true }.get(x)
            if (x == null || !x.javaClass.name.endsWith("CompositionContextHolder")) return emptyList()
            val ref = x.javaClass.getDeclaredField("ref").apply { isAccessible = true }.get(x)
            val composers = ref.javaClass.getDeclaredField("composers").apply { isAccessible = true }.get(ref) as Collection<*>
            composers.mapNotNull { (it as? Composer)?.compositionData }
        }.getOrDefault(emptyList())

        private fun nameOf(scope: RecomposeScope): String = runCatching {
            val f = scope.javaClass.getDeclaredField("block").apply { isAccessible = true }
            var b: Any? = f.get(scope)
            if (b != null && b.javaClass.name.startsWith("androidx.compose.runtime.internal.ComposableLambdaImpl")) {
                val outer = if (b.javaClass.name.endsWith("\$invoke\$1")) b.javaClass.declaredFields.firstOrNull { it.type.name.contains("ComposableLambdaImpl") }?.apply { isAccessible = true }?.get(b) else b
                b = outer?.javaClass?.getDeclaredField("_block")?.apply { isAccessible = true }?.get(outer) ?: b
            }
            val c = b?.javaClass ?: return@runCatching "?"
            // Hidden lambda classes carry no source name; what they capture says which lambda they are.
            if (c.name.contains("\$\$Lambda")) c.name.substringBefore("\$\$Lambda") + "{" + c.declaredFields.joinToString(",") { it.type.simpleName } + "}" else c.name
        }.getOrDefault("?")
    }
}
