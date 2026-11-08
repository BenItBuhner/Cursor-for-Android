package com.cursorforandroid.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.GreetingBucket
import com.cursorforandroid.domain.GreetingLog
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * The New Chat page's greeting as it lives on the page: read out as a heading and recorded as shown; the same line
 * as the page recomposes and the minute ticks, a new one when the part of the day turns or the page is opened again;
 * a name arriving filling in the line rather than replacing it, and a line that needs the name replaced when it goes.
 * Its entrance plays once per line — not again as the page recomposes — and not at all with animations removed. With
 * the keyboard up it stays on a phone in portrait and folds away, out of the accessibility tree, in landscape; it folds
 * away too while the composer is expanded up the page.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class NewChatGreetingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private var list by mutableStateOf(AgentListUiState())
    private var user by mutableStateOf<CursorUser?>(BENNETT)
    private var open by mutableStateOf(true)
    private var now = NewChatHomeFixtures.NOW

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        AppClock.nowMillis = { now }
        graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
        }
        list = NewChatHomeFixtures.list()
    }

    @After
    fun tearDown() {
        runBlocking { graph.drafts.clear() }
        AppClock.nowMillis = System::currentTimeMillis
        motion(on = true)
    }

    private fun show() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                Box(Modifier.fillMaxSize()) {
                    if (open) {
                        HomeScreen(
                            graph = graph,
                            listState = list,
                            onOpenSidebar = {},
                            onOpenAgent = {},
                            onLaunchOpen = {},
                            rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                            home = NewChatHome.PROJECTS_RECENT,
                            projectsAvailable = true,
                            user = user,
                        )
                    }
                }
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(NewChatHomeCopy.PLACEHOLDER, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { recent().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun recent(): List<String> = graph.caches.greetings.peek().recent

    private fun greetings() = compose.onAllNodes(hasTestTag(NewChatHomeTags.GREETING)).fetchSemanticsNodes()

    private fun text(): String = greetings().single().config[SemanticsProperties.Text].joinToString("") { it.text }

    /** The greeting as drawn now; drawn directly, since captureToImage waits for a redraw the held clock never lets happen. */
    private fun image(): Bitmap {
        val root = compose.activity.window.decorView
        val screen = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(screen)) }
        val box = greetings().single().boundsInRoot
        return Bitmap.createBitmap(screen, box.left.toInt(), box.top.toInt(), box.width.toInt(), box.height.toInt())
    }

    /**
     * Waits for the page to record a line other than [last] as shown (the history may hold lines of earlier runs): the
     * page brought round first, its effect launched, then the record written off the main thread.
     */
    private fun awaitShownAfter(last: String) {
        compose.waitForIdle()
        compose.waitUntil(5_000) { recent().lastOrNull().let { it != null && it != last } }
        compose.waitForIdle()
    }

    /** The clock moved on to [millis], and the list's minute tick bringing the page round to it. */
    private fun tickTo(millis: Long) {
        now = millis
        list = list.copy(nowMillis = millis)
    }

    /** The first minute after now in another part of the day. */
    private fun nextPart(): Long {
        val bucket = GreetingBucket.of(now, ZoneOffset.UTC)
        return generateSequence(now) { it + MINUTE }.first { GreetingBucket.of(it, ZoneOffset.UTC) != bucket }
    }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeBy(16L)
        compose.waitForIdle()
    }

    private fun motion(on: Boolean) = Settings.Global.putFloat(
        ApplicationProvider.getApplicationContext<Application>().contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        if (on) 1f else 0f,
    )

    private fun px(dp: Dp): Int = with(compose.density) { dp.roundToPx() }

    @Test
    fun `it heads the page as a heading, the line the page picks, recorded as shown`() {
        val before = graph.caches.greetings.peek()
        show()
        val expected = pickNewChatGreeting(now, ZoneOffset.UTC, "Bennett", list, before, firstOfVisit = true).greeting
        val node = greetings().single()
        assertThat(node.config.getOrNull(SemanticsProperties.Heading)).isNotNull()
        assertThat(text()).isEqualTo(expected.text)
        assertThat(recent().last()).isEqualTo(expected.line.id)
    }

    @Test
    fun `the line holds as the page recomposes and the minute ticks within its part of the day`() {
        show()
        val first = text()
        val shown = recent()
        repeat(5) { minute ->
            tickTo(NewChatHomeFixtures.NOW + (minute + 1) * MINUTE)
            compose.waitForIdle()
            assertThat(text()).isEqualTo(first)
        }
        assertThat(recent()).isEqualTo(shown)
    }

    @Test
    fun `a new part of the day picks a new line, kept off the last`() {
        show()
        val first = text()
        val firstId = recent().last()
        tickTo(nextPart())
        awaitShownAfter(firstId)
        assertThat(text()).isNotEqualTo(first)
    }

    @Test
    fun `each visit to the page picks afresh, kept off the last`() {
        show()
        val first = text()
        val firstId = recent().last()
        open = false
        compose.waitForIdle()
        assertThat(greetings()).isEmpty()
        open = true
        awaitShownAfter(firstId)
        assertThat(text()).isNotEqualTo(first)
    }

    @Test
    fun `a name arriving fills in the line rather than picking another`() {
        user = null
        show()
        val shown = recent()
        assertThat(text()).doesNotContain("{")
        user = BENNETT
        compose.waitForIdle()
        assertThat(recent()).isEqualTo(shown)
    }

    @Test
    fun `a line that needs the name is replaced when the name goes`() {
        now = generateSequence(NewChatHomeFixtures.NOW) { it + MINUTE }.first {
            pickNewChatGreeting(it, ZoneOffset.UTC, "Bennett", list, GreetingLog(), firstOfVisit = true).greeting.line.needsName
        }
        list = list.copy(nowMillis = now)
        show()
        assertThat(text()).contains("Bennett")
        val firstId = recent().last()
        user = null
        awaitShownAfter(firstId)
        assertThat(text()).doesNotContain("Bennett")
        assertThat(text()).doesNotContain("{")
    }

    @Test
    fun `the entrance plays for a new line and not again as the page recomposes`() {
        show()
        val settled = image()
        compose.mainClock.autoAdvance = false
        tickTo(now + MINUTE)
        frames(2)
        assertThat(image().sameAs(settled)).isTrue()

        tickTo(nextPart())
        frames(4)
        val entering = image()
        frames(90)
        assertThat(entering.sameAs(image())).isFalse()
    }

    @Test
    fun `with animations removed the line is simply there`() {
        motion(on = false)
        show()
        compose.mainClock.autoAdvance = false
        tickTo(nextPart())
        frames(2)
        val first = image()
        frames(90)
        assertThat(first.sameAs(image())).isTrue()
    }

    @Test
    fun `with the keyboard up on a phone it stays, a heading over the composer`() {
        show()
        val keyboard = KeyboardDriver(compose, navigationBar = px(NavigationBar))
        keyboard.stand(0)
        compose.mainClock.autoAdvance = false
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER))).requestFocus()
        frames(2)
        keyboard.show(px(Keyboard))
        frames(30)
        val node = greetings().single()
        assertThat(node.config.getOrNull(SemanticsProperties.Heading)).isNotNull()
        val composer = compose.onNode(hasTestTag(NewChatHomeTags.COMPOSER)).fetchSemanticsNode()
        assertThat(node.boundsInRoot.top).isAtLeast(composer.boundsInRoot.top)
        assertThat(node.boundsInRoot.height).isGreaterThan(0f)
    }

    @Test
    fun `the composer expanded up the page folds it away, and collapsed brings it back`() {
        show()
        val field = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER)))
        field.performTextInput((1..14).joinToString("\n") { "Line $it of a long prompt" })
        val expand = hasTestTag("composer-expand") and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER))
        compose.waitUntil(5_000) { compose.onAllNodes(expand, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(expand, useUnmergedTree = true).onFirst().performClick()
        compose.waitForIdle()
        assertThat(greetings()).isEmpty()
        compose.onAllNodes(expand, useUnmergedTree = true).onFirst().performClick()
        compose.waitForIdle()
        assertThat(greetings()).hasSize(1)
    }

    @Test
    @Config(sdk = [35], qualifiers = LANDSCAPE)
    fun `with the keyboard up on a phone in landscape it folds away, out of the accessibility tree`() {
        show()
        assertThat(greetings()).hasSize(1)
        val keyboard = KeyboardDriver(compose, navigationBar = px(NavigationBar))
        keyboard.stand(0)
        compose.mainClock.autoAdvance = false
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER))).requestFocus()
        frames(2)
        keyboard.show(px(LandscapeKeyboard))
        frames(30)
        assertThat(greetings()).isEmpty()
        keyboard.hide()
        frames(30)
        assertThat(greetings()).hasSize(1)
    }

    private companion object {
        val BENNETT = CursorUser("Bennett's key", "bennett@example.com", "Bennett", "Buhner", null)
        const val MINUTE = 60_000L
        val NavigationBar = 24.dp
        val Keyboard = 300.dp
        val LandscapeKeyboard = 200.dp
    }
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
private const val LANDSCAPE = "w914dp-h411dp-night-420dpi"
