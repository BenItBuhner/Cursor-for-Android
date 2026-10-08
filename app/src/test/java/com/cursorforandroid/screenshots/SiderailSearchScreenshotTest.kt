package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.navigation.AppShell
import com.cursorforandroid.ui.shortcuts.PaletteCopy
import com.cursorforandroid.ui.shortcuts.PaletteTags
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * The left siderail's search button: the in-rail field on a phone, and the existing search palette on a tablet,
 * opened from that button rather than Ctrl+P. Written to `screenshots/`; CI compares them pixel for pixel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class SiderailSearchScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun pinClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { FIXED_NOW }
    }

    @After
    fun restoreClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun graph(): AppGraph {
        val context = this.context
        return AppGraph(
            context,
            SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) },
            appVersion = SCREENSHOT_APP_VERSION,
            agentListDispatcher = Dispatchers.Main,
        ).also {
            runBlocking {
                it.prefs.setNewChatHome(NewChatHome.RECENT)
                it.session.enterDemo()
            }
        }
    }

    private fun show(wide: Boolean) {
        val graph = graph()
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    AppShell(
                        graph = graph,
                        user = DEMO_USER,
                        isDemo = true,
                        wide = wide,
                        deepLinkAgentId = null,
                        onDeepLinkConsumed = {},
                    )
                }
            }
        }
        compose.waitUntil(30_000) { onScreen("Ask Cursor to build, fix bugs, explore") }
    }

    private fun onScreen(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun described(description: String) =
        compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    private val sidebarList =
        hasScrollToNodeAction() and hasAnyDescendant(hasText("Pinned") or hasText("Today"))

    private fun waitForSidebarTop() {
        compose.waitUntil(30_000) { compose.onAllNodes(sidebarList).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(sidebarList).onFirst().performScrollToIndex(0)
        compose.waitUntil(30_000) { onScreen("Projects") }
    }

    @Test
    fun phoneInRailSearch() {
        show(wide = false)
        compose.onNodeWithContentDescription("Open sidebar").performClick()
        compose.waitUntil(20_000) { described("Search chats") }
        waitForSidebarTop()
        compose.onNodeWithContentDescription("Search chats").performClick()
        compose.waitUntil(10_000) { tagged("sidebar-search") }
        capture("1140_siderail_search_phone")
    }

    @Test
    @Config(qualifiers = TABLET)
    fun tabletPaletteFromRail() {
        show(wide = true)
        compose.waitUntil(20_000) { described("Search chats") }
        waitForSidebarTop()
        compose.onNodeWithContentDescription("Search chats").performClick()
        compose.waitUntil(10_000) { tagged(PaletteTags.CARD) }
        compose.waitUntil(20_000) { onScreen(PaletteCopy.PLACEHOLDER) }
        compose.waitUntil(20_000) { !onScreen(PaletteCopy.READING) }
        compose.waitForIdle()
        capture("1141_siderail_search_tablet")
    }

    private companion object {
        val DEMO_USER = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null)
        const val FIXED_NOW = 1_736_949_600_000L
    }
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
private const val TABLET = "w1280dp-h800dp-night-320dpi"
