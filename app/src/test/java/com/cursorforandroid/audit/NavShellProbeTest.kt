package com.cursorforandroid.audit

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.agents.SidebarTags
import com.cursorforandroid.ui.home.NewChatHomeTags
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.settings.SettingsTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Audit probes for the shell: recomposition per frame / per tick, Settings' first frames, rapid navigation. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class NavShellProbeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private var deepLink by mutableStateOf<String?>(null)
    private lateinit var graph: AppGraph

    private val watched = listOf(
        "AppShell", "Sidebar", "AgentRowItem", "CursorDrawer", "CursorNavHost", "EntryHost", "HomeScreen", "HomeBlockView",
        "RecentChatRow", "ConversationScreen", "SettingsScreen", "SettingsToggleRow", "PaletteHost", "CommandPalette", "ComposerBox",
    )

    @Before
    fun setUp() {
        RecomposeCounter.install()
    }

    private fun launch(before: suspend AppGraph.() -> Unit = {}) {
        graph = AppGraph(context)
        runBlocking {
            graph.session.enterDemo()
            graph.before()
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AppShell(
                    graph = graph,
                    user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                    isDemo = true,
                    wide = false,
                    deepLinkAgentId = deepLink,
                    onDeepLinkConsumed = { deepLink = null },
                )
            }
        }
        compose.waitUntil(30_000) { onScreen(HOME_PLACEHOLDER) }
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        compose.waitForIdle()
    }

    private val vm: AgentsViewModel get() = ViewModelProvider(compose.activity, AgentsViewModel.Factory(graph))[AgentsViewModel::class.java]

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun chatHeaders(): Int = compose.onAllNodes(hasTestTag("chat-header")).fetchSemanticsNodes().size

    private fun chatShown(name: String) = compose.onAllNodes(hasTestTag("chat-header") and hasContentDescription(name)).fetchSemanticsNodes().isNotEmpty()

    private fun watchedLine(): String = watched.map { it to RecomposeCounter.count(it) }.filter { it.second > 0 }.joinToString(" ") { "${it.first}=${it.second}" }

    private fun settle() = repeat(4) {
        Thread.sleep(150)
        compose.waitForIdle()
    }

    private fun perFrame(frames: Int, names: List<String>): String = (1..frames).joinToString(" | ") {
        val before = RecomposeCounter.snapshot()
        compose.mainClock.advanceTimeByFrame()
        val after = RecomposeCounter.snapshot()
        names.joinToString(",") { name ->
            val d = after.filterKeys { k -> k.endsWith(".$name") }.values.sum() - before.filterKeys { k -> k.endsWith(".$name") }.values.sum()
            "${name.take(6)}=$d"
        }
    }

    private fun openChat() {
        deepLink = IDLE_CHAT_ID
        compose.waitUntil(30_000) { chatShown(IDLE_CHAT) && !onScreen(HOME_PLACEHOLDER) }
        settle()
    }

    @Test
    fun drawerOverChatRecomposition() {
        launch()
        openChat()
        val names = listOf("AppShell", "Sidebar", "AgentRowItem", "ConversationScreen", "CursorNavHost")
        AuditLog.line("=== drawer over a chat (phone) ===")
        compose.mainClock.autoAdvance = false
        val root = compose.onRoot()
        root.performTouchInput { down(Offset(width * 0.45f, height * 0.6f)) }
        compose.mainClock.advanceTimeByFrame()
        RecomposeCounter.reset()
        val drag = (1..20).joinToString(" | ") {
            val before = RecomposeCounter.snapshot()
            root.performTouchInput { moveBy(Offset(16f, 0f)) }
            compose.mainClock.advanceTimeByFrame()
            val after = RecomposeCounter.snapshot()
            names.joinToString(",") { name ->
                val d = after.filterKeys { k -> k.endsWith(".$name") }.values.sum() - before.filterKeys { k -> k.endsWith(".$name") }.values.sum()
                "${name.take(6)}=$d"
            }
        }
        AuditLog.line("drag 20 frames per frame: $drag")
        AuditLog.line("drag total -> ${watchedLine()}")
        root.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        settle()
        val open = compose.onAllNodes(hasContentDescription("Close navigation menu")).fetchSemanticsNodes().isNotEmpty()
        AuditLog.line("after drag release: drawer open=$open")
        if (!open) {
            root.performTouchInput {
                down(Offset(width * 0.3f, height * 0.6f))
                repeat(30) { moveBy(Offset(24f, 0f)) }
                up()
            }
            settle()
        }
        AuditLog.line("after long drag: drawer open=${compose.onAllNodes(hasContentDescription("Close navigation menu")).fetchSemanticsNodes().isNotEmpty()}")
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasContentDescription("Close navigation menu")).onFirst().performClick()
        RecomposeCounter.reset()
        AuditLog.line("close via scrim, per frame: ${perFrame(22, names)}")
        AuditLog.line("close total -> ${watchedLine()}")
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun listTicksRecomposition() {
        launch()
        AuditLog.line("=== list ticks ===")
        val emissions = mutableListOf<Long>()
        val job = MainScope().launch { vm.uiState.collect { emissions += System.nanoTime() } }
        settle()
        AuditLog.line("demo: agents=${vm.uiState.value.allAgents.size} recentRows=${vm.uiState.value.recentRows.size}")

        fun probe(label: String, action: () -> Unit) {
            RecomposeCounter.reset(); emissions.clear()
            action()
            settle()
            AuditLog.line("$label -> emissions=${emissions.size} ${watchedLine()}")
        }

        probe("home: list pref write (collapse unknown section)") { vm.setSectionCollapsed("zz-none", true) }
        probe("home: unrelated pref write (rail width)") { runBlocking { graph.prefs.setRailWidthDp(301) } }
        probe("home: refresh() (demo)") { vm.refresh() }

        openChat()
        probe("chat on top, drawer shut: list pref write") { vm.setSectionCollapsed("zz-none-2", true) }
        AuditLog.line(RecomposeCounter.top(20))
        probe("chat on top, drawer shut: unrelated pref write") { runBlocking { graph.prefs.setRailWidthDp(302) } }
        probe("chat on top, drawer shut: refresh()") { vm.refresh() }
        job.cancel()
    }

    @Test
    fun homeComposerKeystrokes() {
        launch()
        AuditLog.line("=== New Chat composer keystrokes ===")
        val field = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER))).onFirst()
        field.performClick()
        compose.waitForIdle()
        RecomposeCounter.reset()
        val word = "hello"
        word.forEach { c ->
            field.performTextInput(c.toString())
            compose.waitForIdle()
        }
        AuditLog.line("${word.length} keystrokes -> ${watchedLine()}")
        AuditLog.line(RecomposeCounter.top(25))
    }

    @Test
    fun settingsFirstFrames() {
        launch {
            prefs.setLiveNotifications(false)
            prefs.setConfirmStop(false)
            prefs.setShortenSidebarLists(false)
            prefs.setThemeMode(ThemeMode.Light)
        }
        AuditLog.line("=== Settings first frames (stored: Light, live notifications off, confirm stop off, shorten off) ===")
        compose.onAllNodes(hasContentDescription("Open sidebar")).onFirst().performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onAllNodes(hasTestTag(SidebarTags.ACCOUNT)).onFirst().performClick()
        RecomposeCounter.reset()
        val lines = mutableListOf<String>()
        for (frame in 0 until 14) {
            compose.mainClock.advanceTimeByFrame()
            val settings = compose.onAllNodes(hasText("Appearance")).fetchSemanticsNodes().isNotEmpty()
            if (!settings) { lines += "f$frame: -"; continue }
            val oled = compose.onAllNodes(hasText("OLED black"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            val checkMatch = compose.onAllNodes(hasText("Match system") and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes().isNotEmpty()
            fun toggle(tag: String): String {
                val nodes = compose.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState), useUnmergedTree = true).fetchSemanticsNodes()
                return nodes.firstOrNull()?.config?.get(SemanticsProperties.ToggleableState)?.name ?: "?"
            }
            lines += "f$frame: oledRow=$oled matchSystemChecked=$checkMatch confirmStop=${toggle(SettingsTags.CONFIRM_STOP)} shorten=${toggle(SettingsTags.SHORTEN_PROJECTS)} settingsRecomp=${RecomposeCounter.count("SettingsScreen")}"
        }
        lines.forEach(AuditLog::line)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        AuditLog.line("settled: oledRow=${compose.onAllNodes(hasText("OLED black"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()}")
    }

    @Test
    fun rapidNavigation() {
        launch()
        AuditLog.line("=== rapid navigation ===")
        val dispatcher = compose.activity.onBackPressedDispatcher
        // 1. Double tap on a recent row within one frame.
        compose.mainClock.autoAdvance = false
        val row = compose.onAllNodes(hasText(IDLE_CHAT)).onFirst()
        row.performClick(); row.performClick()
        compose.mainClock.advanceTimeBy(1_500)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(20_000) { chatShown(IDLE_CHAT) }
        compose.waitForIdle()
        dispatcher.onBackPressed(); compose.waitForIdle()
        AuditLog.line("double tap row, then one back -> home=${onScreen(HOME_PLACEHOLDER) && chatHeaders() == 0} headers=${chatHeaders()} canBack=${dispatcher.hasEnabledCallbacks()}")

        // 2. Back pressed twice during a push (2 frames in), then a new deep link 1 frame later.
        compose.mainClock.autoAdvance = false
        deepLink = IDLE_CHAT_ID
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        dispatcher.onBackPressed(); dispatcher.onBackPressed()
        compose.mainClock.advanceTimeByFrame()
        deepLink = OTHER_CHAT_ID
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        AuditLog.line("push, back x2 mid-push, deep link -> headers=${chatHeaders()} idleShown=${chatShown(IDLE_CHAT)} home=${onScreen(HOME_PLACEHOLDER)}")
        dispatcher.onBackPressed(); compose.waitForIdle()
        AuditLog.line("  then back -> headers=${chatHeaders()} home=${onScreen(HOME_PLACEHOLDER)} canBack=${dispatcher.hasEnabledCallbacks()}")

        // 3. Predictive back started mid-push, cancelled.
        compose.mainClock.autoAdvance = false
        deepLink = IDLE_CHAT_ID
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
        compose.mainClock.advanceTimeByFrame()
        dispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 400f, 0.3f, BackEventCompat.EDGE_LEFT))
        compose.mainClock.advanceTimeByFrame()
        dispatcher.dispatchOnBackCancelled()
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        AuditLog.line("gesture mid-push, cancelled -> headers=${chatHeaders()} idleShown=${chatShown(IDLE_CHAT)} home=${onScreen(HOME_PLACEHOLDER)}")

        // 4. Predictive back committed, then a second back immediately while the first settles.
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 400f, 0.5f, BackEventCompat.EDGE_LEFT))
        compose.mainClock.autoAdvance = false
        dispatcher.onBackPressed()
        compose.mainClock.advanceTimeByFrame()
        dispatcher.onBackPressed()
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        AuditLog.line("gesture commit + extra back -> headers=${chatHeaders()} home=${onScreen(HOME_PLACEHOLDER)} activityFinishing=${compose.activity.isFinishing}")
    }

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val IDLE_CHAT = "Cli exploration"
        const val IDLE_CHAT_ID = "bc-demo-0004"
        const val OTHER_CHAT_ID = "bc-demo-0005"
    }
}
