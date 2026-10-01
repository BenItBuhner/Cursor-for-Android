package com.cursorforandroid.ui.navigation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.home.NewChatHomeTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The New Chat page on a launch after one that drew Projects: the shortcuts are on its very first frame, where they
 * stay, rather than an empty page the Projects slide into once the settings and the list have been read. The first
 * launch is the demo's shell left to load and write its page down; the second is a shell built from nothing over it —
 * a new process's graph reading the file, or a new activity over the live process.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class NewChatPageFirstFrameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var shown by mutableStateOf<Launch?>(null)

    private class Launch(val graph: AppGraph, val user: CursorUser) {
        /** A fresh activity's store: the list's view model is built again rather than handed over. */
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }

    private fun demoLaunch(graph: AppGraph = AppGraph(app)): Launch {
        runBlocking { graph.session.enterDemo() }
        val user = (graph.session.state.value as SessionState.SignedIn).user
        return Launch(graph, user)
    }

    private fun tiles(): List<SemanticsNode> = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes()

    private fun composerTop(): Float = compose.onNode(hasTestTag(NewChatHomeTags.COMPOSER)).fetchSemanticsNode().positionInRoot.y

    private val pageFile: File get() = File(app.cacheDir, "cursor/newchat/page.json")

    /** The first launch: the demo's page shown until its Projects are in and written down. Their count. */
    private fun firstLaunch(launch: Launch): Int {
        pageFile.delete()
        shown = launch
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                val current = shown ?: return@CursorTheme
                key(current) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides current.owner) {
                        AppShell(graph = current.graph, user = current.user, isDemo = true, wide = false, deepLinkAgentId = null, onDeepLinkConsumed = {})
                    }
                }
            }
        }
        compose.waitUntil(30_000) { tiles().isNotEmpty() }
        compose.waitUntil(30_000) { pageFile.isFile }
        compose.waitForIdle()
        return tiles().size
    }

    /** Swaps in [next]'s shell with the clock held, and checks the frames from its first. */
    private fun secondLaunch(next: Launch, projects: Int) {
        compose.mainClock.autoAdvance = false
        shown = next
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        assertWithMessage("Project shortcuts on the first frame").that(tiles()).hasSize(projects)
        File("build/outputs/roborazzi").mkdirs()
        captureScreenRoboImage("build/outputs/roborazzi/new_chat_first_frame.png", RoborazziOptions(taskType = RoborazziTaskType.Record))
        val first = composerTop()
        repeat(60) {
            compose.mainClock.advanceTimeBy(FRAME_MILLIS)
            compose.waitForIdle()
            assertWithMessage("shortcuts, frame ${it + 2}").that(tiles()).hasSize(projects)
            assertWithMessage("the composer, frame ${it + 2}").that(composerTop()).isWithin(1f).of(first)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(tiles()).hasSize(projects)
        assertThat(composerTop()).isWithin(1f).of(first)
    }

    @Test
    fun `a new process draws the Projects on its first frame, from the page the last one wrote down`() {
        val projects = firstLaunch(demoLaunch())
        val next = demoLaunch()
        next.graph.caches.newChatPage.warm()
        secondLaunch(next, projects)
    }

    @Test
    fun `an activity built again over the live process opens on the Projects too`() {
        val first = demoLaunch()
        val projects = firstLaunch(first)
        secondLaunch(Launch(first.graph, first.user), projects)
    }

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}
