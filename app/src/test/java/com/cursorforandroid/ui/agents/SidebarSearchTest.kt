package com.cursorforandroid.ui.agents

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The header search button: on a phone it opens the in-rail field; with [SidebarCallbacks.onSearch] it opens that
 * instead and never composes the field — the shell wires the palette there on wide windows.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SidebarSearchTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(onSearch: (() -> Unit)? = null, searchRequests: Int = 0) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Sidebar(
                    state = AgentListUiState(hasLoaded = true),
                    user = CursorUser("key", "bennett@example.com", "Bennett", "Buhner", 1),
                    isDemo = false,
                    selectedAgentId = null,
                    selectedDestination = null,
                    onQueryChange = {},
                    searchRequests = searchRequests,
                    callbacks = SidebarCallbacks(
                        onNewChat = {},
                        onSettings = {},
                        onCustomize = {},
                        onToggleSidebar = {},
                        onRefresh = {},
                        rowActions = AgentRowActions({}, {}, {}, {}, null, { _, _ -> }, {}),
                        onSearch = onSearch,
                    ),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun inRailSearchShown() =
        compose.onAllNodes(hasTestTag("sidebar-search")).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the header button opens the in-rail field when the palette callback is absent`() {
        show()
        assertThat(inRailSearchShown()).isFalse()

        compose.onNodeWithContentDescription("Search chats").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("sidebar-search").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `the header button opens the palette callback and never shows the in-rail field`() {
        var opened = 0
        show(onSearch = { opened++ })

        compose.onNodeWithContentDescription("Search chats").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertThat(opened).isEqualTo(1)
        assertThat(inRailSearchShown()).isFalse()
    }

    @Test
    fun `searchRequests do not open the in-rail field when the palette callback is set`() {
        show(onSearch = {}, searchRequests = 1)
        compose.waitForIdle()
        assertThat(inRailSearchShown()).isFalse()
    }
}
