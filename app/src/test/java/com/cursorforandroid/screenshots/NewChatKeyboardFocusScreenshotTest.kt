package com.cursorforandroid.screenshots

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.agents.SidebarDestination
import com.cursorforandroid.ui.components.withKeyboard
import com.cursorforandroid.ui.home.HomeScreen
import com.cursorforandroid.ui.home.KeyboardDriver
import com.cursorforandroid.ui.home.KeyboardStandIn
import com.cursorforandroid.ui.home.NewChatHomeCopy
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.home.NewChatHomeTags
import com.cursorforandroid.ui.home.SuggestionStrip
import com.cursorforandroid.ui.navigation.SidebarRail
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
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
 * The New Chat page, Projects + Recent, with the on-screen keyboard up for its composer and after it has gone: on a
 * phone, and on a tablet in landscape beside the sidebar (whose docked keyboard leaves a phone's height, so it is given
 * over too), dark and light; the composer alone in the middle above the keyboard, then everything back with the draft
 * kept. Half way up on a phone, the lists part faded and slid. And a tablet with a hardware keyboard, the on-screen
 * keyboard down to its suggestion strip: the page as it is. The keyboard is moved through the platform's own IME
 * animation ([KeyboardDriver]) and drawn where its inset is ([KeyboardStandIn]), Robolectric having no keyboard window.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_DARK)
class NewChatKeyboardFocusScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private lateinit var graph: AppGraph
    private lateinit var keyboard: KeyboardDriver
    private lateinit var focusManager: FocusManager

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { NewChatHomeFixtures.NOW }
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
            graph.catalog.loadRepositories()
            graph.catalog.loadModels()
            graph.agents.refresh()
        }
    }

    @After
    fun tearDown() {
        runBlocking { graph.drafts.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun shortcuts() = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes().size

    private fun given() = compose.onAllNodes(hasTestTag(NewChatHomeTags.LISTED_HIDDEN)).fetchSemanticsNodes().any { it.layoutInfo.isPlaced }

    private fun show(mode: ThemeMode, tablet: Boolean, hardwareKeyboard: Boolean = false) {
        val list = NewChatHomeFixtures.list()
        compose.setContent {
            val base = LocalConfiguration.current
            val configuration = remember(base) { base.withKeyboard(attached = hardwareKeyboard) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                CursorTheme(mode = mode) {
                    focusManager = LocalFocusManager.current
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                        Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                            val page: @Composable (Modifier, Boolean) -> Unit = { modifier, withHeader ->
                                HomeScreen(
                                    graph = graph,
                                    listState = list,
                                    onOpenSidebar = if (withHeader) ({}) else null,
                                    onOpenAgent = {},
                                    onLaunchOpen = {},
                                    rowActions = ROW_ACTIONS,
                                    modifier = modifier,
                                    home = NewChatHome.PROJECTS_RECENT,
                                    projectsAvailable = true,
                                )
                            }
                            if (tablet) {
                                Row(Modifier.fillMaxSize()) {
                                    SidebarRail(expanded = true) {
                                        Sidebar(
                                            state = list,
                                            user = DEMO_USER,
                                            isDemo = true,
                                            selectedAgentId = null,
                                            selectedDestination = SidebarDestination.NewChat,
                                            onQueryChange = {},
                                            callbacks = SidebarCallbacks({}, {}, {}, {}, {}, ROW_ACTIONS, onNewProject = {}),
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                    page(Modifier.weight(1f).fillMaxHeight(), false)
                                }
                            } else {
                                page(Modifier.fillMaxSize(), true)
                            }
                            KeyboardStandIn(full = KEYBOARD, dark = mode == ThemeMode.Dark)
                        }
                    }
                }
            }
        }
        compose.waitUntil(30_000) { onScreen(NewChatHomeCopy.PLACEHOLDER) }
        compose.waitUntil(30_000) { onScreen(MODEL_CHIP) }
        compose.waitUntil(10_000) { shortcuts() == 5 }
        keyboard = KeyboardDriver(compose, navigationBar = px(NAVIGATION_BAR))
        keyboard.stand(0)
        compose.mainClock.autoAdvance = false
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER))).requestFocus()
        frames(2)
    }

    private fun px(dp: Dp): Int = with(compose.density) { dp.roundToPx() }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeBy(16L)
        compose.waitForIdle()
    }

    private fun field() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER)))

    /** The keyboard up for the composer, a draft typed into it. */
    private fun keyboardOpen(mode: ThemeMode, tablet: Boolean, frame: String) {
        show(mode, tablet)
        keyboard.show(px(KEYBOARD))
        field().performTextInput(DRAFT)
        frames(30)
        assertThat(given()).isTrue()
        assertThat(shortcuts()).isEqualTo(0)
        capture(frame)
    }

    /** Then the keyboard dismissed and the composer let go: the page as it was, the draft kept. */
    private fun keyboardClosed(mode: ThemeMode, tablet: Boolean, frame: String) {
        show(mode, tablet)
        keyboard.show(px(KEYBOARD))
        field().performTextInput(DRAFT)
        frames(30)
        keyboard.hide()
        compose.runOnUiThread { focusManager.clearFocus() }
        frames(30)
        assertThat(given()).isFalse()
        assertThat(shortcuts()).isEqualTo(5)
        assertThat(onScreen(DRAFT)).isTrue()
        capture(frame)
    }

    /** A hardware keyboard attached, the on-screen one down to its suggestion strip: nothing given over. */
    private fun hardwareKeyboard(mode: ThemeMode, frame: String) {
        show(mode, tablet = true, hardwareKeyboard = true)
        keyboard.show(px(SuggestionStrip))
        field().performTextInput(DRAFT)
        frames(30)
        assertThat(given()).isFalse()
        assertThat(shortcuts()).isEqualTo(5)
        capture(frame)
    }

    @Test
    fun openPhoneDark() = keyboardOpen(ThemeMode.Dark, tablet = false, "980_new_chat_keyboard_open_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun openPhoneLight() = keyboardOpen(ThemeMode.Light, tablet = false, "981_new_chat_keyboard_open_phone_light")

    @Test
    fun closedPhoneDark() = keyboardClosed(ThemeMode.Dark, tablet = false, "982_new_chat_keyboard_closed_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun closedPhoneLight() = keyboardClosed(ThemeMode.Light, tablet = false, "983_new_chat_keyboard_closed_phone_light")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_DARK)
    fun openTabletDark() = keyboardOpen(ThemeMode.Dark, tablet = true, "984_new_chat_keyboard_open_tablet_dark")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_LIGHT)
    fun openTabletLight() = keyboardOpen(ThemeMode.Light, tablet = true, "985_new_chat_keyboard_open_tablet_light")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_DARK)
    fun closedTabletDark() = keyboardClosed(ThemeMode.Dark, tablet = true, "986_new_chat_keyboard_closed_tablet_dark")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_LIGHT)
    fun closedTabletLight() = keyboardClosed(ThemeMode.Light, tablet = true, "987_new_chat_keyboard_closed_tablet_light")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_DARK)
    fun hardwareKeyboardTabletDark() = hardwareKeyboard(ThemeMode.Dark, "988_new_chat_hardware_keyboard_tablet_dark")

    @Test
    @Config(sdk = [35], qualifiers = TABLET_LIGHT)
    fun hardwareKeyboardTabletLight() = hardwareKeyboard(ThemeMode.Light, "989_new_chat_hardware_keyboard_tablet_light")

    /** Half way through the keyboard's rise: the composer part way to the middle, the lists part slid and faded. */
    @Test
    fun risingPhoneDark() {
        show(ThemeMode.Dark, tablet = false)
        var captured = false
        keyboard.show(px(KEYBOARD)) { inset, _ ->
            if (!captured && inset >= px(KEYBOARD) * 0.45f) {
                captured = true
                capture("990_new_chat_keyboard_rising_phone_dark")
            }
        }
        assertThat(captured).isTrue()
    }

    private companion object {
        val DEMO_USER = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null)
        val ROW_ACTIONS = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        const val MODEL_CHIP = "Claude Fable 5.1"
        const val DRAFT = "Fix the flaky upload test"
        val KEYBOARD = 300.dp
        val NAVIGATION_BAR = 24.dp
    }
}

private const val PHONE_DARK = "w411dp-h914dp-night-420dpi"
private const val PHONE_LIGHT = "w411dp-h914dp-notnight-420dpi"
private const val TABLET_DARK = "w1000dp-h720dp-night-320dpi"
private const val TABLET_LIGHT = "w1000dp-h720dp-notnight-320dpi"
