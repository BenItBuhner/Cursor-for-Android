package com.cursorforandroid.ui.projects

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.RecomposeScopeObserver
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.AgentParentKind
import com.cursorforandroid.domain.LineageSignal
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/**
 * Audit harness: what the panel's Project section recomposes when the agent list moves. The real [projectPanelItems]
 * over the demo graph, a Project of [WORKERS] primaries among [OTHERS] unrelated chats; prints scopes per change.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectSectionRecompositionAuditTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()

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

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun frames(n: Int = 10) = repeat(n) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test
    fun `project section recompositions per list change`() {
        AppClock.nowMillis = { now }
        val api = FakeCursorApi()
        val iso = { ms: Long -> Instant.ofEpochMilli(ms).toString() }
        api.addIdleAgent(ROOT, "Audit Project", "run-root", createdAt = iso(now - 7_200_000L))
        repeat(WORKERS) { api.addIdleAgent("bc-w$it", "Worker $it", "run-w$it", createdAt = iso(now - 3_600_000L - it * 1_000L)) }
        repeat(OTHERS) { api.addIdleAgent("bc-o$it", "Other $it", "run-o$it", createdAt = iso(now - 60_000L - it * 60_000L)) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, FakeRunStreamer(replay = 64), isDemo = true))
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        runBlocking { while (graph.agents.state.value.hasMore) graph.agents.loadMore() }
        graph.agents.patch(ROOT) { it.copy(isProject = true) }
        graph.agents.applyLineage(ROOT, (0 until WORKERS).associate { "bc-w$it" to AgentParentKind.PROJECT_WORKER }, LineageSignal.ACTION)
        println("AUDIT list size=${graph.agents.state.value.agents.size} workers=${graph.agents.state.value.agents.count { it.parent?.id == ROOT }}")

        val recompositions = Recompositions()
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(recompositions) }
            CursorTheme(mode = ThemeMode.Dark) {
                val items = projectPanelItems(graph, ROOT, onOpenAgent = {}, onNotify = {})
                LazyColumn(Modifier.fillMaxSize()) { items() }
            }
        }
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Worker 0")).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.autoAdvance = false
        frames(30)

        fun measure(label: String, change: () -> Unit) {
            val before = recompositions.scopes
            val t0 = System.nanoTime()
            change()
            frames(10)
            val shown = compose.onAllNodes(hasText("Worker 1 renamed")).fetchSemanticsNodes().size
            println("AUDIT[$label] renamedWorkerShown=$shown scopes=${recompositions.scopes - before} wall=${"%.1f".format((System.nanoTime() - t0) / 1e6)}ms")
        }
        measure("idle, nothing changes") { }
        measure("unrelated chat renamed") { graph.agents.patch("bc-o5") { it.copy(name = "Other 5 renamed") } }
        measure("unrelated chat renamed again") { graph.agents.patch("bc-o6") { it.copy(name = "Other 6 renamed") } }
        measure("one worker renamed") { graph.agents.patch("bc-w1") { it.copy(name = "Worker 1 renamed") } }
        measure("clock +60 s, nothing else") { AppClock.nowMillis = { now + 60_000L }; graph.agents.patch("bc-o7") { it.copy(name = "Other 7 renamed") } }
    }

    private companion object {
        const val ROOT = "bc-audit-root"
        const val WORKERS = 30
        const val OTHERS = 300
    }
}
