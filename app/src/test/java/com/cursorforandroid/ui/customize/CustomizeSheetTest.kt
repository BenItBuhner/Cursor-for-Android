package com.cursorforandroid.ui.customize

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import com.cursorforandroid.domain.FilterKind
import com.cursorforandroid.domain.GroupBy
import com.cursorforandroid.domain.SortOrder
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.components.PickerTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chats menu against an account with more repositories than fit a screen. The Repo filter is the only submenu
 * that can grow without bound, so it is the one that must compose lazily.
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
            agentListDispatcher = Dispatchers.Main,
        )
        graph.session.enterDemo()
    }

    /**
     * Waits for [condition] on the semantics tree, whose every read idles the rule first: for what the menu has drawn,
     * which is what the assertions after it read. The list is organized on the main thread (see
     * [AppGraph.agentListDispatcher]); from a background thread the menu could miss a state and never draw it.
     */
    private fun awaitOnScreen(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 10_000, condition = condition)

    private fun composed(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun readAllSays(state: String) =
        compose.onAllNodes(hasTestTag(READ_ALL_TAG) and hasText(state)).fetchSemanticsNodes().isNotEmpty()

    private fun open(): AgentsViewModel {
        val viewModel = AgentsViewModel(graph)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) { CustomizeSheet(viewModel, onDismiss = {}) }
        }
        // Wait for the list itself: a Repo filter of an account whose agents have not landed yet has no rows at all.
        awaitOnScreen { composed("Group by") && compose.runOnIdle { viewModel.uiState.value.unreadCount == repoCount } }
        return viewModel
    }

    private fun openRepoFilter(): AgentsViewModel {
        val viewModel = open()
        compose.onNodeWithTag(filterTag(FilterKind.Repo)).performClick()
        awaitOnScreen { composed("All repositories") }
        return viewModel
    }

    @Test
    fun `grouping is checked in place and the menu stays open`() {
        val viewModel = open()
        compose.onNodeWithText("Repository").performClick()
        awaitOnScreen { compose.runOnIdle { viewModel.uiState.value.prefs.groupBy == GroupBy.Repo } }
        compose.onNodeWithText("Repository").assertIsSelected()
        compose.onNodeWithText(GroupBy.Date.label).assertIsNotSelected()
        compose.onNodeWithTag(CHATS_MENU_TAG).assertIsDisplayed()
    }

    @Test
    fun `a sort order picked in its submenu steps back to the menu, which then names it`() {
        val viewModel = open()
        compose.onNodeWithText("Sort by", substring = true).performClick()
        awaitOnScreen { composed(SortOrder.Name.label) }
        compose.onNodeWithText(SortOrder.Name.label).performClick()
        awaitOnScreen { composed("Group by") }
        assertThat(viewModel.uiState.value.prefs.sortOrder).isEqualTo(SortOrder.Name)
        compose.onNodeWithText("Sort by", substring = true).assert(hasText(SortOrder.Name.label))
    }

    @Test
    fun `Read all sits at the foot, marks every chat read, and then goes off`() {
        val viewModel = open()
        // The foot of a long menu on a phone: below the fold until scrolled to.
        compose.onNodeWithTag(PickerTags.List).performScrollToNode(hasTestTag(READ_ALL_TAG))
        awaitOnScreen { readAllSays("$repoCount unread chats") }
        val readAll = compose.onNodeWithTag(READ_ALL_TAG)
        readAll.assertIsDisplayed().assertIsEnabled()
        // Default preferences: nothing to reset, so Read all is the last row.
        assertThat(composed("Reset")).isFalse()

        viewModel.setShowRuntime(true)
        compose.onNodeWithTag(PickerTags.List).performScrollToNode(hasTestTag(RESET_TAG))
        awaitOnScreen { composed("Reset") }
        val reset = compose.onNodeWithTag(RESET_TAG).fetchSemanticsNode().boundsInRoot
        assertThat(readAll.fetchSemanticsNode().boundsInRoot.bottom).isAtMost(reset.top)

        readAll.performClick()
        awaitOnScreen { readAllSays("Nothing unread") }
        readAll.assertIsNotEnabled()
        assertThat(viewModel.uiState.value.unreadCount).isEqualTo(0)
    }

    @Test
    fun `the repo filter composes only the rows on screen and scrolls to the rest`() {
        openRepoFilter()
        assertThat(composed("repo-00")).isTrue()
        assertThat(composed(lastSlug)).isFalse()

        compose.onNodeWithTag(PickerTags.List).performScrollToNode(hasText(lastSlug))
        compose.waitForIdle()
        assertThat(composed(lastSlug)).isTrue()
    }

    @Test
    fun `a repository tapped in the repo filter drops it from the filter`() {
        val viewModel = openRepoFilter()
        compose.onNodeWithText("repo-00", substring = true).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { compose.runOnIdle { viewModel.uiState.value.prefs.repos != null } }
        val repos = viewModel.uiState.value.prefs.repos
        assertThat(repos).doesNotContain("acme/repo-00")
        assertThat(repos).hasSize(repoCount - 1)
    }
}
