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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.DayPart
import com.cursorforandroid.domain.FirstName
import com.cursorforandroid.domain.GreetingLog
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.agents.SidebarDestination
import com.cursorforandroid.ui.home.HomeScreen
import com.cursorforandroid.ui.home.KeyboardDriver
import com.cursorforandroid.ui.home.KeyboardStandIn
import com.cursorforandroid.ui.home.NewChatHomeCopy
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.home.NewChatHomeTags
import com.cursorforandroid.ui.home.pickNewChatGreeting
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
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/**
 * The New Chat page's greeting over the composer in each of the page's layouts — Composer only, Projects, Recent
 * agents, Projects + Recent — on a phone, a tablet beside the sidebar and an unfolded foldable, dark and light, with a
 * first name and without; with the keyboard up (it stays, the block centred above the keyboard) and on a phone in
 * landscape (it folds away for the composer); and part way through its entrance, the words popping up one by one.
 *
 * Each frame names the line it shows and finds a moment the page picks it at, so the frames stand while other lines
 * of the pool are edited.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_DARK)
class NewChatGreetingScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private lateinit var graph: AppGraph
    private var list by mutableStateOf(AgentListUiState())

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
        list = NewChatHomeFixtures.list()
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

    private fun greetingShown(): Boolean = compose.onAllNodes(hasTestTag(NewChatHomeTags.GREETING)).fetchSemanticsNodes().isNotEmpty()

    /**
     * Pins the clock to the first minute, from the fixtures' day on, in [part] where a fresh page greets [user] with
     * [lineId]: what the page picks there is what the frame shows.
     */
    private fun pinTo(lineId: String, part: DayPart, user: CursorUser?, after: Long = NewChatHomeFixtures.NOW, log: GreetingLog = GreetingLog(), firstOfVisit: Boolean = true): String {
        val name = FirstName.of(user)
        val start = Instant.ofEpochMilli(after).atZone(ZoneOffset.UTC).toLocalDate()
        for (day in 0L until 400L) for (hour in 0..23) {
            if (DayPart.of(hour) != part) continue
            for (minute in 0 until 60) {
                val at = start.plusDays(day).atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()
                if (at < after) continue
                val pick = pickNewChatGreeting(at, ZoneOffset.UTC, name, list, log, firstOfVisit)
                if (pick.greeting.line.id == lineId) {
                    AppClock.nowMillis = { at }
                    return pick.greeting.text
                }
            }
        }
        error("No moment in $part greets ${name ?: "a nameless reader"} with $lineId")
    }

    private fun show(mode: ThemeMode, home: NewChatHome, layout: Layout = Layout.Phone, user: CursorUser? = BENNETT) {
        compose.setContent {
            CursorTheme(mode = mode) {
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
                                home = home,
                                projectsAvailable = true,
                                user = user,
                            )
                        }
                        when (layout) {
                            Layout.Phone -> page(Modifier.fillMaxSize(), true)
                            Layout.Unfolded -> page(Modifier.fillMaxSize(), false)
                            Layout.Tablet -> Row(Modifier.fillMaxSize()) {
                                SidebarRail(expanded = true) {
                                    Sidebar(
                                        state = list,
                                        user = BENNETT,
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
                        }
                        KeyboardStandIn(full = KEYBOARD, dark = mode == ThemeMode.Dark)
                    }
                }
            }
        }
        compose.waitUntil(30_000) { onScreen(NewChatHomeCopy.PLACEHOLDER) }
        compose.waitUntil(30_000) { onScreen(MODEL_CHIP) }
        if (home != NewChatHome.COMPOSER && home != NewChatHome.RECENT) compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes().size == 5 }
        compose.waitForIdle()
    }

    private fun frame(lineId: String, part: DayPart, mode: ThemeMode, home: NewChatHome, name: String, layout: Layout = Layout.Phone, user: CursorUser? = BENNETT, running: Int = 0) {
        list = list.copy(runningCount = running)
        val text = pinTo(lineId, part, user)
        show(mode, home, layout, user)
        assertThat(onScreen(text)).isTrue()
        capture(name)
    }

    private fun px(dp: Dp): Int = with(compose.density) { dp.roundToPx() }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeBy(16L)
        compose.waitForIdle()
    }

    @Test
    fun composerOnlyPhoneDark() = frame("lunch-cooking", DayPart.Lunch, ThemeMode.Dark, NewChatHome.COMPOSER, "1000_new_chat_greeting_composer_only_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun composerOnlyPhoneLight() = frame("late-coffee", DayPart.LateNight, ThemeMode.Light, NewChatHome.COMPOSER, "1001_new_chat_greeting_composer_only_phone_light")

    @Test
    fun projectsPhoneDark() = frame("morning-good", DayPart.Morning, ThemeMode.Dark, NewChatHome.PROJECTS, "1002_new_chat_greeting_projects_phone_dark")

    @Test
    @Config(sdk = [35], qualifiers = PHONE_LIGHT)
    fun recentPhoneLight() = frame("afternoon-slump", DayPart.Afternoon, ThemeMode.Light, NewChatHome.RECENT, "1003_new_chat_greeting_recent_phone_light")

    @Test
    fun projectsRecentPhoneDark() = frame("running-chef", DayPart.Evening, ThemeMode.Dark, NewChatHome.PROJECTS_RECENT, "1004_new_chat_greeting_projects_recent_phone_dark", running = 3)

    @Test
    @Config(sdk = [35], qualifiers = PHONE_DARK)
    fun nameless() = frame("any-cursor", DayPart.Afternoon, ThemeMode.Dark, NewChatHome.PROJECTS, "1005_new_chat_greeting_nameless_phone_dark", user = DEMO_USER)

    @Test
    @Config(sdk = [35], qualifiers = TABLET_DARK)
    fun projectsRecentTabletDark() = frame("evening-hello", DayPart.Evening, ThemeMode.Dark, NewChatHome.PROJECTS_RECENT, "1006_new_chat_greeting_projects_recent_tablet_dark", Layout.Tablet)

    @Test
    @Config(sdk = [35], qualifiers = TABLET_LIGHT)
    fun composerOnlyTabletLight() = frame("small-3am", DayPart.SmallHours, ThemeMode.Light, NewChatHome.COMPOSER, "1007_new_chat_greeting_composer_only_tablet_light", Layout.Tablet)

    @Test
    @Config(sdk = [35], qualifiers = FOLD_DARK)
    fun projectsUnfoldedDark() = frame("any-building", DayPart.Morning, ThemeMode.Dark, NewChatHome.PROJECTS, "1008_new_chat_greeting_projects_unfolded_dark", Layout.Unfolded)

    /** The keyboard up on a phone: the greeting stays, heading the composer in the middle of the room above it. */
    @Test
    fun keyboardUpPhoneDark() {
        val text = pinTo("lunch-cooking", DayPart.Lunch, BENNETT)
        show(ThemeMode.Dark, NewChatHome.PROJECTS_RECENT)
        val keyboard = KeyboardDriver(compose, navigationBar = px(NAVIGATION_BAR))
        keyboard.stand(0)
        compose.mainClock.autoAdvance = false
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER))).requestFocus()
        frames(2)
        keyboard.show(px(KEYBOARD))
        frames(30)
        assertThat(greetingShown()).isTrue()
        assertThat(onScreen(text)).isTrue()
        capture("1009_new_chat_greeting_keyboard_up_phone_dark")
    }

    /** A phone in landscape: the keyboard leaves too little above it, and the greeting folds away for the composer. */
    @Test
    @Config(sdk = [35], qualifiers = LANDSCAPE_DARK)
    fun keyboardUpLandscapeDark() {
        pinTo("lunch-cooking", DayPart.Lunch, BENNETT)
        show(ThemeMode.Dark, NewChatHome.PROJECTS_RECENT)
        assertThat(greetingShown()).isTrue()
        val keyboard = KeyboardDriver(compose, navigationBar = px(NAVIGATION_BAR))
        keyboard.stand(0)
        compose.mainClock.autoAdvance = false
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER))).requestFocus()
        frames(2)
        keyboard.show(px(LANDSCAPE_KEYBOARD))
        frames(30)
        assertThat(greetingShown()).isFalse()
        capture("1010_new_chat_greeting_keyboard_up_landscape_dark")
    }

    /**
     * The clock turning to the evening with the page open: a new line, its words popping up one after another — caught
     * a few frames in, then settled.
     */
    @Test
    fun entrancePhoneDark() {
        pinTo("afternoon-next", DayPart.Afternoon, BENNETT)
        show(ThemeMode.Dark, NewChatHome.PROJECTS)
        compose.waitUntil(10_000) { graph.caches.greetings.peek().recent == listOf("afternoon-next") }
        val evening = pinTo("evening-golden", DayPart.Evening, BENNETT, after = AppClock.now(), log = graph.caches.greetings.peek(), firstOfVisit = false)
        compose.mainClock.autoAdvance = false
        list = list.copy(nowMillis = list.nowMillis + 1)
        frames(11)
        capture("1011_new_chat_greeting_entrance_phone_dark")
        frames(60)
        assertThat(onScreen(evening)).isTrue()
        capture("1012_new_chat_greeting_entrance_settled_phone_dark")
    }

    private enum class Layout { Phone, Tablet, Unfolded }

    private companion object {
        val BENNETT = CursorUser("Bennett's key", "bennett@example.com", "Bennett", "Buhner", null)
        val DEMO_USER = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null)
        val ROW_ACTIONS = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        const val MODEL_CHIP = "Claude Fable 5.1"
        val KEYBOARD = 300.dp
        val LANDSCAPE_KEYBOARD = 200.dp
        val NAVIGATION_BAR = 24.dp
    }
}

private const val PHONE_DARK = "w411dp-h914dp-night-420dpi"
private const val PHONE_LIGHT = "w411dp-h914dp-notnight-420dpi"
private const val TABLET_DARK = "w1000dp-h720dp-night-320dpi"
private const val TABLET_LIGHT = "w1000dp-h720dp-notnight-320dpi"
private const val FOLD_DARK = "w673dp-h841dp-night-420dpi"
private const val LANDSCAPE_DARK = "w914dp-h411dp-night-420dpi"
