package com.cursorforandroid.ui.components

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The headers in a desktop window's caption bar: a 40px bar with the app menu over the first 160px and the window
 * controls over the last 200px of a 1000px window (mdpi, so pixels are dp). Each header stands in the bar's row;
 * rail buttons pack just after the app-menu chip; trailing buttons hug the window controls in that same pixel space
 * even when a panel stands between the chat column and those controls. A higher-density config and a one-bar report
 * use the same occupancy.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1000dp-h700dp-mdpi")
class CaptionHeaderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val appMenu = IntRect(0, 0, 160, 40)
    private val windowControls = IntRect(800, 0, 1000, 40)
    private val bar = CaptionBar(40, listOf(appMenu, windowControls), 1000)

    private fun header(bar: CaptionBar?, railWidth: Int = 0, chat: Boolean = false, panelWidth: Int = 0) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CaptionBarScope(bar) {
                    Row(Modifier.fillMaxSize()) {
                        if (railWidth > 0) Box(Modifier.width(railWidth.dp))
                        Box(Modifier.weight(1f)) {
                            val leading: @Composable RowScope.() -> Unit = { FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = {}) }
                            val trailing: @Composable RowScope.() -> Unit = {
                                FlatIconButton(CursorIcons.Sidebar, "Hide panel", onClick = {})
                                FlatIconButton(CursorIcons.More, "More", onClick = {})
                            }
                            if (chat) ChatHeader("Chat", leading = leading, trailing = trailing) else CursorHeader(leading = leading, trailing = trailing)
                        }
                        if (panelWidth > 0) Box(Modifier.width(panelWidth.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(description: String): DpRect = compose.onNodeWithContentDescription(description).getUnclippedBoundsInRoot()

    /** The hit layer overflows the 32dp visual box by 6dp; occupancy is judged against the visual edge. */
    private fun touchReachPx(): Float = with(compose.density) { ((CursorDimens.touchTarget - CursorDimens.iconButton) / 2).toPx() }

    private fun visualLeftPx(description: String): Float = with(compose.density) { bounds(description).left.toPx() } + touchReachPx()

    private fun visualRightPx(description: String): Float = with(compose.density) { bounds(description).right.toPx() } - touchReachPx()

    private fun assertClearOfExclusive(description: String, bar: CaptionBar = this.bar) {
        val button = bounds(description)
        val occ = bar.occupancy()
        assertWithMessage("$description at $button under the right cap ${occ.rightExclusive}").that(visualRightPx(description)).isAtMost(occ.rightExclusive.toFloat())
        assertWithMessage("$description at $button under the left cap ${occ.leftExclusive}").that(visualLeftPx(description)).isAtLeast(occ.leftExclusive.toFloat())
    }

    private fun assertHugsLeft(description: String, bar: CaptionBar = this.bar) {
        assertThat(visualLeftPx(description)).isWithin(2f).of(bar.occupancy().leftExclusive.toFloat())
    }

    private fun assertHugsRight(description: String, bar: CaptionBar = this.bar) {
        assertThat(visualRightPx(description)).isWithin(2f).of(bar.occupancy().rightExclusive.toFloat())
    }

    private fun assertCentredOnBar(description: String, bar: CaptionBar = this.bar) {
        val button = bounds(description)
        val density = compose.density
        val mid = with(density) { ((button.top + button.bottom) / 2).toPx() }
        assertThat(mid).isWithin(1f).of(bar.heightPx / 2f)
    }

    @Test
    fun `a full-width header stands in the bar clear of the app menu and the window controls`() {
        header(bar)
        assertThat(compose.onNodeWithTag("cursor-header").getUnclippedBoundsInRoot().run { bottom - top }).isEqualTo(40.dp)
        assertClearOfExclusive("Open sidebar")
        assertClearOfExclusive("More")
        assertHugsRight("More")
        assertCentredOnBar("Open sidebar")
        assertCentredOnBar("More")
    }

    @Test
    fun `a chat header stands in the bar, and trailing buttons hug the window controls`() {
        header(bar, railWidth = 300, chat = true)
        assertThat(compose.onNodeWithTag("chat-header").getUnclippedBoundsInRoot().run { bottom - top }).isEqualTo(40.dp)
        assertThat(bounds("Open sidebar").left).isLessThan(310.dp)
        assertHugsRight("More")
        assertCentredOnBar("More")
    }

    @Test
    fun `panel and More hug the window controls when a panel stands between the chat and those controls`() {
        header(bar, railWidth = 300, chat = true, panelWidth = 280)
        assertHugsRight("More")
        assertClearOfExclusive("Hide panel")
        assertThat(visualRightPx("Hide panel")).isAtMost(visualLeftPx("More") + 1f)
        assertThat(visualLeftPx("More")).isGreaterThan(700f)
    }

    @Test
    fun `a chat header's title stands beside back in the bar, centred on it and short of the window controls`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CaptionBarScope(bar) {
                    ChatHeader(
                        "Chat",
                        title = "A chat whose name runs on long enough to reach the window's own controls at the end",
                        leading = { FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = {}) },
                        trailing = { FlatIconButton(CursorIcons.More, "More", onClick = {}) },
                    )
                }
            }
        }
        compose.waitForIdle()
        val title = compose.onNodeWithTag("chat-header-title", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat((title.top + title.bottom).value / 2).isWithin(0.5f).of(bar.heightPx / 2f)
        val back = bounds("Back")
        assertThat(title.left.value).isGreaterThan((back.left + back.right).value / 2)
        assertThat(title.left.value).isAtLeast(appMenu.right.toFloat())
        assertThat(title.right.value).isAtMost(bounds("More").left.value)
        assertThat(title.right.value).isAtMost(windowControls.left.toFloat())
        assertClearOfExclusive("Back")
        assertHugsRight("More")
        assertCentredOnBar("Back")
    }

    @Test
    fun `the rail's header packs just after the app-menu chip, its logo left to the system's`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CaptionBarScope(bar) {
                    Sidebar(
                        state = AgentListUiState(hasLoaded = true),
                        user = CursorUser("key", "a@b.com", "Demo", "User", 1),
                        isDemo = true,
                        selectedAgentId = null,
                        selectedDestination = null,
                        onQueryChange = {},
                        callbacks = SidebarCallbacks(
                            onNewChat = {},
                            onSettings = {},
                            onCustomize = {},
                            onToggleSidebar = {},
                            onRefresh = {},
                            rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                        ),
                        modifier = Modifier.width(300.dp),
                    )
                }
            }
        }
        // The window reports the bar as an inset too, as a desktop window does; the rail must not pad by it as well.
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.captionBar(), Insets.of(0, 40, 0, 0)).build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0), insets)
        }
        compose.waitForIdle()
        assertHugsLeft("New chat")
        for (button in listOf("New chat", "Search chats", "Toggle sidebar")) {
            assertClearOfExclusive(button)
            assertCentredOnBar(button)
        }
        val toggle = bounds("Toggle sidebar")
        assertThat(toggle.right.value).isLessThan(300f)
        compose.onNodeWithContentDescription("Cursor").assertDoesNotExist()
    }

    @Test
    fun `one bar across the top still sits the buttons against the end caps`() {
        val oneBar = CaptionBar(40, listOf(IntRect(0, 0, 1000, 40)), 1000)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CaptionBarScope(oneBar) {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(
                            state = AgentListUiState(hasLoaded = true),
                            user = CursorUser("key", "a@b.com", "Demo", "User", 1),
                            isDemo = true,
                            selectedAgentId = null,
                            selectedDestination = null,
                            onQueryChange = {},
                            callbacks = SidebarCallbacks(
                                onNewChat = {},
                                onSettings = {},
                                onCustomize = {},
                                onToggleSidebar = {},
                                onRefresh = {},
                                rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                            ),
                            modifier = Modifier.width(300.dp),
                        )
                        Box(Modifier.weight(1f)) {
                            ChatHeader(
                                "Chat",
                                leading = { FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = {}) },
                                trailing = { FlatIconButton(CursorIcons.More, "More", onClick = {}) },
                            )
                        }
                        Box(Modifier.width(280.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertHugsRight("More", oneBar)
        assertHugsLeft("New chat", oneBar)
        assertClearOfExclusive("New chat", oneBar)
        assertThat(oneBar.occupancy().rightExclusive - oneBar.occupancy().leftExclusive).isGreaterThan(600)
    }

    @Test
    fun `trailing buttons follow the window controls when the window's scale changes`() {
        val current = androidx.compose.runtime.mutableStateOf(bar)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CaptionBarScope(current.value) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(300.dp))
                        Box(Modifier.weight(1f)) {
                            ChatHeader(
                                "Chat",
                                leading = { FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = {}) },
                                trailing = { FlatIconButton(CursorIcons.More, "More", onClick = {}) },
                            )
                        }
                        Box(Modifier.width(280.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertHugsRight("More", bar)
        val scaled = CaptionBar(40, listOf(IntRect(0, 0, 160, 40), IntRect(700, 0, 900, 40)), 1000)
        current.value = scaled
        compose.waitForIdle()
        assertHugsRight("More", scaled)
        assertThat(scaled.occupancy().rightExclusive).isEqualTo(700)
    }

    @Test
    fun `without a caption bar the headers keep their usual rows`() {
        header(null)
        assertThat(compose.onNodeWithTag("cursor-header").getUnclippedBoundsInRoot().run { bottom - top }).isEqualTo(CursorDimens.headerHeight)
        assertThat(bounds("Open sidebar").left).isLessThan(20.dp)
        assertThat(bounds("More").right).isGreaterThan(980.dp)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1000dp-h700dp-xhdpi")
class CaptionHeaderHdpiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val bar = CaptionBar(80, listOf(IntRect(0, 0, 320, 80), IntRect(1600, 0, 2000, 80)), 2000)

    @Test
    fun `rail and trailing buttons hug the caption controls in the window's pixel space at hdpi`() {
        compose.setContent {
            val density = LocalDensity.current
            CursorTheme(mode = ThemeMode.Dark) {
                CaptionBarScope(bar) {
                    Row(Modifier.fillMaxSize()) {
                        Sidebar(
                            state = AgentListUiState(hasLoaded = true),
                            user = CursorUser("key", "a@b.com", "Demo", "User", 1),
                            isDemo = true,
                            selectedAgentId = null,
                            selectedDestination = null,
                            onQueryChange = {},
                            callbacks = SidebarCallbacks(
                                onNewChat = {},
                                onSettings = {},
                                onCustomize = {},
                                onToggleSidebar = {},
                                onRefresh = {},
                                rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                            ),
                            modifier = Modifier.width(with(density) { 600.toDp() }),
                        )
                        Box(Modifier.weight(1f)) {
                            ChatHeader(
                                "Chat",
                                leading = { FlatIconButton(CursorIcons.Sidebar, "Open sidebar", onClick = {}) },
                                trailing = { FlatIconButton(CursorIcons.More, "More", onClick = {}) },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val density = compose.density
        val reach = with(density) { ((CursorDimens.touchTarget - CursorDimens.iconButton) / 2).toPx() }
        val newChatLeft = with(density) { compose.onNodeWithContentDescription("New chat").getUnclippedBoundsInRoot().left.toPx() } + reach
        val moreRight = with(density) { compose.onNodeWithContentDescription("More").getUnclippedBoundsInRoot().right.toPx() } - reach
        assertThat(newChatLeft).isWithin(2f).of(320f)
        assertThat(moreRight).isWithin(2f).of(1600f)
    }
}
