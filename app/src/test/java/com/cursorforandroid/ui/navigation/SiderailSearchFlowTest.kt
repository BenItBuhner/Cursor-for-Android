package com.cursorforandroid.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.shortcuts.PaletteCopy
import com.cursorforandroid.ui.shortcuts.PaletteTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The left rail's search button: on a phone it opens the in-rail field (the narrow-view search) and not the palette;
 * on a wide window it opens the same search palette Ctrl+P does, with no in-rail field in the rail.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SiderailSearchFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showShell(wide: Boolean) {
        val graph = AppGraph(ApplicationProvider.getApplicationContext())
        runBlocking {
            graph.prefs.setNewChatHome(NewChatHome.RECENT)
            graph.session.enterDemo()
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                AppShell(
                    graph = graph,
                    user = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null),
                    isDemo = true,
                    wide = wide,
                    deepLinkAgentId = null,
                    onDeepLinkConsumed = {},
                )
            }
        }
        compose.waitUntil(30_000) { onScreen("Ask Cursor to build, fix bugs, explore") }
    }

    private fun onScreen(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun described(description: String) =
        compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `on a phone the sidebar search button opens the in-rail field and not the palette`() {
        showShell(wide = false)
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(20_000) { described("Search chats") }

        compose.onNodeWithContentDescription("Search chats").performClick()
        compose.waitUntil(10_000) { exists("sidebar-search") }
        compose.onNodeWithTag("sidebar-search").assertIsDisplayed()
        assertFalse(exists(PaletteTags.CARD))
        assertFalse(exists(PaletteTags.FIELD))
    }

    @Test
    @Config(qualifiers = "w1024dp-h768dp-night-mdpi")
    fun `on a wide window the rail search button opens the palette and not the in-rail field`() {
        showShell(wide = true)
        compose.waitUntil(20_000) { described("Search chats") }

        compose.onNodeWithContentDescription("Search chats").performClick()
        compose.waitUntil(10_000) { exists(PaletteTags.CARD) }
        compose.onNodeWithTag(PaletteTags.CARD).assertIsDisplayed()
        compose.onNodeWithTag(PaletteTags.FIELD).assertIsFocused()
        compose.waitUntil(10_000) { onScreen(PaletteCopy.PLACEHOLDER) }
        assertFalse(exists("sidebar-search"))
        assertTrue(described("Search chats"))
    }
}
