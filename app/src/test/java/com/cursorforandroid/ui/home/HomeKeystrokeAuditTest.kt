package com.cursorforandroid.ui.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
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
import com.cursorforandroid.audit.ComposerKeystrokeAuditTest
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentRowActions
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

/** Audit probe (prints `AUDIT keystroke …`): what one keystroke in the New Chat composer recomposes on the home pane. */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class HomeKeystrokeAuditTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private val rec = ComposerKeystrokeAuditTest.Recompositions()
    private var data: androidx.compose.runtime.tooling.CompositionData? = null

    @Before
    fun setUp() {
        AppClock.nowMillis = { NewChatHomeFixtures.NOW }
        graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
        }
    }

    @After
    fun tearDown() {
        runBlocking { graph.drafts.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    @Test
    fun `new chat home - per keystroke`() {
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(rec) }
            val d = currentComposer.compositionData
            remember { data = d }
            CursorTheme(mode = ThemeMode.Dark) {
                HomeScreen(
                    graph = graph,
                    listState = NewChatHomeFixtures.list(),
                    onOpenSidebar = {},
                    onOpenAgent = {},
                    onLaunchOpen = {},
                    rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                    home = NewChatHome.RECENT,
                    projectsAvailable = true,
                )
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(NewChatHomeCopy.PLACEHOLDER, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER)))
        field.performClick()
        repeat(150) { field.performTextInput("w"); compose.waitForIdle() }
        rec.observeAll(data!!)
        rec.reset()
        val n = 120
        repeat(n) { i -> field.performTextInput(if (i % 6 == 5) " " else "a"); compose.waitForIdle() }
        println("AUDIT keystroke [HomeScreen, New Chat, recent list] keystrokes=$n scopes/keystroke=${"%.1f".format(rec.scopes / n.toDouble())}")
        println(rec.top(25))
    }
}
