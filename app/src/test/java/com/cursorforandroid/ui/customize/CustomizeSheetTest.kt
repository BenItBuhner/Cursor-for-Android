package com.cursorforandroid.ui.customize

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.awaitSynced
import com.cursorforandroid.util.holdFrameClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The filter sheet against an account with more repositories than fit a screen. The Repo page is the only one that
 * can grow without bound, so it is the one that must compose lazily.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CustomizeSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private val repoCount = 60
    private val lastSlug = "repo-%02d".format(repoCount - 1)

    @Before
    fun setUp() = runBlocking<Unit> {
        val api = FakeCursorApi()
        repeat(repoCount) { i ->
            api.addIdleAgent(
                id = "bc-%02d".format(i),
                name = "Chat %02d".format(i),
                runId = "run-%02d".format(i),
                repo = "https://github.com/acme/repo-%02d".format(i),
            )
        }
        graph = AppGraph(
            ApplicationProvider.getApplicationContext<Context>(),
            demo = CursorBackend(api, FakeRunStreamer(), isDemo = true),
        )
        graph.session.enterDemo()
    }

    private fun composed(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun readAllStateDescription(): String? =
        compose.onAllNodesWithTag("sheet-header-action-$READ_ALL").fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.StateDescription)

    private fun openRepoPage(): AgentsViewModel {
        val viewModel = AgentsViewModel(graph)
        compose.holdFrameClock()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { CustomizeSheet(viewModel, onDismiss = {}) }
        }
        // Wait for the list itself: the Repo page of an account whose agents have not landed yet has no rows at all.
        compose.awaitSynced { composed("Repo") && viewModel.uiState.value.repoSlugs.size == repoCount }
        compose.onNodeWithText("Repo").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.awaitSynced { composed("All repositories") && !composed("Grouping") }
        return viewModel
    }

    @Test
    fun `Read all sits in the header left of Reset, marks every chat read, and then goes off`() {
        val viewModel = AgentsViewModel(graph)
        compose.holdFrameClock()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { CustomizeSheet(viewModel, onDismiss = {}) }
        }
        compose.awaitSynced {
            composed("Grouping") && viewModel.uiState.value.unreadCount == repoCount &&
                readAllStateDescription() == "$repoCount unread chats"
        }
        val readAll = compose.onNodeWithTag("sheet-header-action-$READ_ALL")
        readAll.assertIsDisplayed().assertIsEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "$repoCount unread chats"))
        // In the header row, beside the title, and above the first section: the sheet opens on Grouping.
        val readAllBounds = readAll.fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Chats").fetchSemanticsNode().boundsInRoot
        assertThat(readAllBounds.left).isAtLeast(title.right)
        assertThat(readAllBounds.center.y).isWithin(1f).of(title.center.y)
        assertThat(readAllBounds.bottom).isLessThan(compose.onNodeWithText("Grouping").fetchSemanticsNode().boundsInRoot.top)
        // The Actions section is gone: no header of its own, no card.
        assertThat(composed("Actions")).isFalse()
        assertThat(compose.onAllNodesWithTag("sheet-actions").fetchSemanticsNodes()).isEmpty()
        // Default preferences: nothing to reset, so Read all stands alone.
        assertThat(composed("Reset")).isFalse()

        viewModel.setShowRuntime(true)
        compose.awaitSynced { composed("Reset") }
        val reset = compose.onNodeWithTag("sheet-header-action-Reset").fetchSemanticsNode().boundsInRoot
        val readAllBeside = readAll.fetchSemanticsNode().boundsInRoot
        // Directly left of Reset, the two touch areas side by side without overlapping.
        assertThat(readAllBeside.right).isAtMost(reset.left)
        assertThat(readAllBeside.center.y).isWithin(1f).of(reset.center.y)

        readAll.performClick()
        compose.awaitSynced { viewModel.uiState.value.unreadCount == 0 && readAllStateDescription() == "Nothing unread" }
        readAll.assertIsNotEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Nothing unread"))
        assertThat(compose.onAllNodesWithText(READ_ALL).fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun `the repo page composes only the rows on screen and scrolls to the rest`() {
        openRepoPage()
        assertThat(composed("repo-00")).isTrue()
        assertThat(composed(lastSlug)).isFalse()

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(lastSlug))
        compose.waitForIdle()
        assertThat(composed(lastSlug)).isTrue()
    }

    @Test
    fun `a repository tapped on the repo page drops it from the filter`() {
        val viewModel = openRepoPage()
        compose.onNodeWithText("repo-00").performClick()
        compose.awaitSynced { viewModel.uiState.value.prefs.repos != null }
        val repos = viewModel.uiState.value.prefs.repos
        assertThat(repos).doesNotContain("acme/repo-00")
        assertThat(repos).hasSize(repoCount - 1)
    }
}
