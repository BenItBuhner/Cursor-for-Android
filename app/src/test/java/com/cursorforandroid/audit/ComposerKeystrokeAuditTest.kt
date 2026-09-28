package com.cursorforandroid.audit

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.TranscriptPerf
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.conversation.ConversationScreen
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Audit probe (not an assertion suite): what one keystroke in the follow-up composer recomposes on the real chat
 * screen, against the composer alone with a plain hoisted string. Prints `AUDIT keystroke …` lines.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerKeystrokeAuditTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private val agentId = "bc-keystroke-audit"

    @Before fun setUp() { AppClock.nowMillis = { now } }

    @After fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
        TranscriptPerf.clearAll()
    }

    /** Scopes recomposed, named by the class of the lambda each one restarts (the composable or content lambda it belongs to). */
    class Recompositions : CompositionObserver, RecomposeScopeObserver {
        var scopes = 0
        val byName = HashMap<String, Int>()
        private val observed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<RecomposeScope, Boolean>())
        override fun onBeginComposition(composition: Composition, invalidationMap: Map<RecomposeScope, Set<Any>?>) {
            for (scope in invalidationMap.keys) if (observed.add(scope)) scope.observe(this)
        }
        override fun onEndComposition(composition: Composition) = Unit
        override fun onBeginScopeComposition(scope: RecomposeScope) {
            scopes++
            val name = nameOf(scope)
            byName[name] = (byName[name] ?: 0) + 1
        }
        /** Observes every scope in [data]'s groups, not only the invalidated ones, so children recomposed by a changed parameter count too. */
        fun observeAll(data: androidx.compose.runtime.tooling.CompositionData) {
            fun walk(g: androidx.compose.runtime.tooling.CompositionGroup) {
                for (d in g.data) {
                    if (d is RecomposeScope && observed.add(d)) d.observe(this)
                    subcompositions(d).forEach { sub -> sub.compositionGroups.forEach(::walk) }
                }
                for (c in g.compositionGroups) walk(c)
            }
            for (g in data.compositionGroups) walk(g)
        }
        /** The slot tables of subcompositions (BoxWithConstraints, LazyColumn items…) hanging off a remembered context. */
        private fun subcompositions(d: Any?): List<androidx.compose.runtime.tooling.CompositionData> = runCatching {
            var x: Any? = d ?: return emptyList()
            if (x!!.javaClass.name.endsWith("RememberObserverHolder")) x = x.javaClass.getDeclaredField("wrapped").apply { isAccessible = true }.get(x)
            if (x == null || !x.javaClass.name.endsWith("CompositionContextHolder")) return emptyList()
            val ref = x.javaClass.getDeclaredField("ref").apply { isAccessible = true }.get(x)
            val composers = ref.javaClass.getDeclaredField("composers").apply { isAccessible = true }.get(ref) as Collection<*>
            composers.mapNotNull { (it as? androidx.compose.runtime.Composer)?.compositionData }
        }.getOrDefault(emptyList())
        private fun nameOf(scope: RecomposeScope): String = runCatching {
            val f = scope.javaClass.getDeclaredField("block").apply { isAccessible = true }
            var b: Any? = f.get(scope)
            if (b != null && b.javaClass.name.startsWith("androidx.compose.runtime.internal.ComposableLambdaImpl")) {
                val outer = if (b.javaClass.name.endsWith("\$invoke\$1")) b.javaClass.declaredFields.firstOrNull { it.type.name.contains("ComposableLambdaImpl") }?.apply { isAccessible = true }?.get(b) else b
                val inner = outer?.javaClass?.getDeclaredField("_block")?.apply { isAccessible = true }?.get(outer)
                b = inner ?: b
            }
            val c = b?.javaClass ?: return@runCatching "?"
            // Hidden lambda classes carry no source name; what they capture says which lambda they are.
            if (c.name.contains("\$\$Lambda")) c.name.substringBefore("\$\$Lambda") + "{" + c.declaredFields.joinToString(",") { it.type.simpleName } + "}" else c.name
        }.getOrDefault("?")
        override fun onEndScopeComposition(scope: RecomposeScope) = Unit
        override fun onScopeDisposed(scope: RecomposeScope) { observed.remove(scope) }
        fun reset() { scopes = 0; byName.clear() }
        fun top(n: Int = 40) = byName.entries.sortedByDescending { it.value }.take(n).joinToString("\n") { "AUDIT keystroke   ${it.value}x ${it.key}" }
    }

    private object MainThread {
        private val bean: Any? = runCatching { Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null) }.getOrNull()
        private val cpu = runCatching { Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime") }.getOrNull()
        private val alloc = runCatching { Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes") }.getOrNull()
        fun cpuNanos(): Long = runCatching { cpu?.invoke(bean) as? Long }.getOrNull() ?: 0L
        fun allocatedBytes(): Long = runCatching { alloc?.invoke(bean) as? Long }.getOrNull() ?: 0L
    }

    private val rec = Recompositions()
    private var data: androidx.compose.runtime.tooling.CompositionData? = null

    private fun graph(): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, streamer, isDemo = true))
    }

    private fun typeMeasured(label: String, keystrokes: Int, screen: Boolean, rowsComposed: () -> Int = { 0 }) {
        val field = compose.onNode(if (screen) hasSetTextAction() and hasAnyAncestor(hasTestTag("follow-up-composer")) else hasSetTextAction())
        // Warm.
        repeat(150) { field.performTextInput("w"); compose.waitForIdle() }
        rec.observeAll(data!!)
        rec.reset()
        val rows0 = rowsComposed()
        val cpus = mutableListOf<Double>()
        var alloc = 0L
        repeat(keystrokes) { i ->
            val a0 = MainThread.allocatedBytes()
            val c0 = MainThread.cpuNanos()
            field.performTextInput(if (i % 6 == 5) " " else "a")
            compose.waitForIdle()
            cpus += (MainThread.cpuNanos() - c0) / 1e6
            alloc += MainThread.allocatedBytes() - a0
        }
        cpus.sort()
        println("AUDIT keystroke [$label] keystrokes=$keystrokes scopes/keystroke=${"%.1f".format(rec.scopes / keystrokes.toDouble())} " +
            "cpu median=${"%.2f".format(cpus[cpus.size / 2])}ms p90=${"%.2f".format(cpus[(cpus.size * 0.9).toInt()])}ms " +
            "alloc/keystroke=${alloc / keystrokes / 1024}KB rowsComposed=${rowsComposed() - rows0}")
        println(rec.top())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `chat screen - per keystroke`() {
        val prompts = Array(24) { Triple("run-${it + 1}", "Prompt ${it + 1}: tighten module ${it + 1} and list the files.", "Reply ${it + 1}\n\n- `File.kt`: split\n\n```kotlin\nval x = $it\n```") }
        api.addFinishedAgent(agentId, "Audit chat", *prompts, firstRunAt = Instant.ofEpochMilli(now - 24 * 3_600_000L).toString())
        val graph = graph()
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
        compose.waitUntil(60_000) { compose.onAllNodes(hasText("Prompt 24", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("follow-up-composer"))).performClick()
        compose.waitForIdle()
        typeMeasured("ConversationScreen, 24 turns", 120, screen = true) { TranscriptPerf.sessionOrNull(agentId)?.snapshot()?.rowCompositions ?: 0 }
    }

    @Test
    fun `composer alone - per keystroke`() {
        var value by mutableStateOf("")
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(rec) }
            val d = currentComposer.compositionData
            remember { data = d }
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = value, onValueChange = { value = it }, placeholder = "Follow up…", onSend = {}, modelLabel = "Opus 4.7")
            }
        }
        compose.onNode(hasSetTextAction()).performClick()
        typeMeasured("ComposerBox alone", 120, screen = false)
    }

    @Test
    fun `composer alone - long draft - per keystroke`() {
        var value by mutableStateOf(("Refactor the transcript engine so replays share one parse; keep the golden files. ").repeat(60))
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(rec) }
            val d = currentComposer.compositionData
            remember { data = d }
            CursorTheme(mode = ThemeMode.Dark) {
                ComposerBox(value = value, onValueChange = { value = it }, placeholder = "Follow up…", onSend = {}, modelLabel = "Opus 4.7")
            }
        }
        compose.onNode(hasSetTextAction()).performClick()
        typeMeasured("ComposerBox alone, ${value.length}-char draft", 120, screen = false)
    }
}
