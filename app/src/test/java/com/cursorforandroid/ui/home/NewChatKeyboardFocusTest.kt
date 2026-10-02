package com.cursorforandroid.ui.home

import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.cursorforandroid.ui.components.withKeyboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The New Chat page given over to its composer while the on-screen keyboard is up for it: on a phone, Projects +
 * Recent, the composer is where the keyboard's inset puts it on every frame of the keyboard's own animation — from
 * its place over the lists to the middle of the room above the keyboard, by the share of the keyboard risen, never a
 * frame behind — while the lists slide down and fade, leave the accessibility tree and take no touch; the keyboard
 * leaving (Back, or a dismiss) lands every pixel back where it was, the draft kept, and a predictive-back scrub
 * follows the finger and rewinds. Nothing changes with a hardware keyboard, a strip too short to be a keyboard, the
 * composer not focused, or a tablet in portrait with its keyboard docked; a tablet in landscape, where the keyboard
 * leaves a phone's height, is given over like a phone. With animations removed the lists fade without sliding.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class NewChatKeyboardFocusTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private lateinit var keyboard: KeyboardDriver
    private var withHeader = true
    private lateinit var focusManager: FocusManager

    @Before
    fun setUp() {
        AppClock.nowMillis = { NewChatHomeFixtures.NOW }
        graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
        }
    }

    @After
    fun tearDown() {
        runBlocking { graph.drafts.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun show(home: NewChatHome = NewChatHome.PROJECTS_RECENT, withHeader: Boolean = true, hardwareKeyboard: Boolean = false) {
        this.withHeader = withHeader
        compose.setContent {
            val base = LocalConfiguration.current
            val configuration = remember(base) { base.withKeyboard(attached = hardwareKeyboard) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                CursorTheme(mode = ThemeMode.Dark) {
                    focusManager = LocalFocusManager.current
                    Box(Modifier.fillMaxSize()) {
                        HomeScreen(
                            graph = graph,
                            listState = NewChatHomeFixtures.list(),
                            onOpenSidebar = if (withHeader) ({}) else null,
                            onOpenAgent = {},
                            onLaunchOpen = {},
                            rowActions = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}),
                            home = home,
                            projectsAvailable = true,
                        )
                    }
                }
            }
        }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(NewChatHomeCopy.PLACEHOLDER, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { tiles().isNotEmpty() }
        compose.waitForIdle()
        keyboard = KeyboardDriver(compose, navigationBar = px(NavigationBar).toInt())
        keyboard.stand(0)
        compose.mainClock.autoAdvance = false
    }

    private fun field() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(NewChatHomeTags.COMPOSER)))

    private fun focusComposer() {
        field().requestFocus()
        frames(2)
    }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeBy(16L)
        compose.waitForIdle()
    }

    private fun node(tag: String): SemanticsNode = compose.onNode(hasTestTag(tag)).fetchSemanticsNode()

    private fun composer(): SemanticsNode = node(NewChatHomeTags.COMPOSER)

    private fun page(): SemanticsNode = node(NewChatHomeTags.PAGE)

    private fun tiles(): List<SemanticsNode> = compose.onAllNodes(hasTestTag(NewChatHomeTags.PROJECT_SHORTCUT)).fetchSemanticsNodes()

    /** What the page lists, given over; the lazy list's items composed ahead and not placed are not on the page. */
    private fun hidden(): List<SemanticsNode> = compose.onAllNodes(hasTestTag(NewChatHomeTags.LISTED_HIDDEN)).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }

    private val SemanticsNode.top: Float get() = boundsInRoot.top
    private val SemanticsNode.bottom: Float get() = boundsInRoot.bottom

    private fun px(dp: Dp): Float = with(compose.density) { dp.toPx() }

    /** Where the composer stands alone: in the middle of the list's room under its top padding, above the keyboard. */
    private fun aloneTop(): Float {
        val page = page()
        val top = page.top + px(pageTopPadding(withHeader))
        return top + (page.bottom - top - composer().boundsInRoot.height) / 2f
    }

    /** The page as the reader sees it with nothing given over: the composer's top and every shortcut's bounds. */
    private fun restingPage() = composer().top to tiles().map { it.boundsInRoot }

    private fun assertGivenOver() {
        assertWithMessage("shortcuts left in the accessibility tree").that(tiles()).isEmpty()
        assertThat(hidden()).isNotEmpty()
        assertThat(composer().top).isWithin(1f).of(aloneTop())
    }

    private fun assertUntouched(resting: Pair<Float, List<Rect>>) {
        assertThat(hidden()).isEmpty()
        assertThat(composer().top).isWithin(0.5f).of(resting.first)
        assertThat(tiles().map { it.boundsInRoot }).isEqualTo(resting.second)
    }

    @Test
    fun `the keyboard's own frames carry the composer to the middle and the lists away, and back on the keyboard leaving`() {
        show()
        val resting = restingPage()
        focusComposer()
        assertUntouched(resting)

        val tops = mutableListOf<Float>()
        keyboard.show(px(Keyboard).toInt()) { _, risen ->
            val expected = resting.first + (aloneTop() - resting.first) * risen
            assertWithMessage("composer at %s risen", risen).that(composer().top).isWithin(1.5f).of(expected)
            tops += composer().top
            if (risen > 0f) assertThat(tiles()).isEmpty()
        }
        assertThat(tops).isInOrder()
        assertThat(tops.zipWithNext { a, b -> b - a }.count { it > 1f }).isAtLeast(8)
        assertGivenOver()
        // The field is still what TalkBack and the keyboard have.
        assertThat(field().fetchSemanticsNode().config.contains(SemanticsActions.SetText)).isTrue()

        field().performTextInput(DRAFT)
        frames(4)
        assertGivenOver()

        keyboard.hide { _, risen ->
            val expected = resting.first + (aloneTop() - resting.first) * risen
            assertWithMessage("composer at %s risen", risen).that(composer().top).isWithin(1.5f).of(expected)
        }
        frames(30)
        assertUntouched(resting)
        assertThat(compose.onAllNodes(hasText(DRAFT)).fetchSemanticsNodes()).isNotEmpty()
        // Every shortcut can be touched and read again.
        tiles().forEach { assertThat(it.config.contains(SemanticsActions.OnClick)).isTrue() }
    }

    @Test
    fun `the lists slide down under the composer by the share risen, and fade`() {
        show()
        focusComposer()
        var checked = 0
        keyboard.show(px(Keyboard).toInt()) { _, risen ->
            if (risen <= 0f) return@show
            // Unclipped: the list cuts off what it slides past its foot.
            val first = hidden().minOf { it.positionInRoot.y }
            val expected = composer().bottom + px(ComposerGap) + px(ListSlide) * risen
            assertWithMessage("first list item at %s risen", risen).that(first).isWithin(1.5f).of(expected)
            checked++
        }
        assertThat(checked).isAtLeast(8)
        assertThat(listAlpha(0f)).isEqualTo(1f)
        assertThat(listAlpha(0.3f)).isWithin(0.01f).of(0.5f)
        assertThat(listAlpha(0.6f)).isEqualTo(0f)
        assertThat(listAlpha(1f)).isEqualTo(0f)
    }

    @Test
    fun `a predictive-back scrub follows the finger down and rewinds on cancel`() {
        show()
        val resting = restingPage()
        focusComposer()
        keyboard.show(px(Keyboard).toInt())
        val alone = composer().top
        keyboard.scrubAndCancel(peek = 0.1f, frames = 6) { _, risen ->
            val expected = resting.first + (aloneTop() - resting.first) * risen
            assertThat(composer().top).isWithin(1.5f).of(expected)
        }
        assertGivenOver()
        assertThat(composer().top).isWithin(0.5f).of(alone)
    }

    @Test
    fun `typing a second line keeps the composer alone in the middle`() {
        show()
        focusComposer()
        keyboard.show(px(Keyboard).toInt())
        field().performTextInput("One\nTwo\nThree\nFour\nFive")
        frames(40)
        assertGivenOver()
    }

    @Test
    fun `focus leaving with the keyboard up eases the page back rather than in one frame`() {
        show()
        val resting = restingPage()
        focusComposer()
        keyboard.show(px(Keyboard).toInt())
        compose.runOnUiThread { focusManager.clearFocus() }
        val steps = mutableListOf<Float>()
        var last = composer().top
        repeat((KeyboardFocus.GateMillis / 16) + 6) {
            frames(1)
            steps += abs(composer().top - last)
            last = composer().top
        }
        keyboard.hide()
        frames(30)
        assertUntouched(resting)
        assertWithMessage("largest step of the composer").that(steps.max()).isLessThan(px(40.dp))
    }

    @Test
    fun `a hardware keyboard leaves the page as it is`() {
        show(hardwareKeyboard = true)
        val resting = restingPage()
        focusComposer()
        // What an on-screen keyboard still shows over a hardware one: its suggestion strip.
        keyboard.show(px(Strip).toInt()) { _, _ -> assertThat(hidden()).isEmpty() }
        assertThat(hidden()).isEmpty()
        assertThat(composer().top).isWithin(0.5f).of(resting.first)
        // Even a full one, were it shown.
        keyboard.hide()
        keyboard.show(px(Keyboard).toInt()) { _, _ -> assertThat(hidden()).isEmpty() }
        assertThat(composer().top).isWithin(0.5f).of(resting.first)
    }

    @Test
    fun `a strip too short to be a keyboard leaves the page as it is`() {
        show()
        val resting = restingPage()
        focusComposer()
        keyboard.show(px(Strip).toInt()) { _, _ -> assertThat(hidden()).isEmpty() }
        assertThat(composer().top).isWithin(0.5f).of(resting.first)
    }

    @Test
    fun `a keyboard up for something other than the composer leaves the page as it is`() {
        show()
        val resting = restingPage()
        keyboard.show(px(Keyboard).toInt()) { _, _ -> assertThat(hidden()).isEmpty() }
        assertThat(composer().top).isWithin(0.5f).of(resting.first)
    }

    @Test
    @Config(sdk = [35], qualifiers = TABLET_PORTRAIT)
    fun `a tablet in portrait keeps its page with the keyboard docked`() {
        show(withHeader = false)
        focusComposer()
        keyboard.show(px(TabletKeyboard).toInt()) { _, _ -> assertThat(hidden()).isEmpty() }
        assertThat(tiles()).isNotEmpty()
    }

    @Test
    @Config(sdk = [35], qualifiers = TABLET_LANDSCAPE)
    fun `a tablet in landscape, left a phone's height by its docked keyboard, is given over like a phone`() {
        show(withHeader = false)
        val resting = restingPage()
        focusComposer()
        keyboard.show(px(TabletKeyboard).toInt())
        assertGivenOver()
        keyboard.hide()
        frames(30)
        assertUntouched(resting)
    }

    @Test
    fun `with animations removed the lists fade where they are, without sliding`() {
        Settings.Global.putFloat(ApplicationProvider.getApplicationContext<Application>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            show()
            focusComposer()
            keyboard.show(px(Keyboard).toInt()) { _, risen ->
                if (risen <= 0f) return@show
                assertThat(hidden().minOf { it.positionInRoot.y }).isWithin(1.5f).of(composer().bottom + px(ComposerGap))
            }
            assertGivenOver()
        } finally {
            Settings.Global.putFloat(ApplicationProvider.getApplicationContext<Application>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    @Test
    fun `how far up the keyboard is, from its animation's insets`() {
        assertThat(keyboardRisen(now = 0, from = 0, to = 0)).isEqualTo(0f)
        assertThat(keyboardRisen(now = 300, from = 300, to = 300)).isEqualTo(1f)
        assertThat(keyboardRisen(now = 150, from = 0, to = 300)).isEqualTo(0.5f)
        assertThat(keyboardRisen(now = 75, from = 300, to = 0)).isEqualTo(0.25f)
        // Between two heights of its own, an emoji panel opened over it, it is up throughout.
        assertThat(keyboardRisen(now = 320, from = 300, to = 360)).isEqualTo(1f)
    }

    @Test
    fun `which keyboards give the page over`() {
        fun takes(hardware: Boolean = false, keyboard: Dp = 300.dp, width: Dp = 411.dp, room: Dp = 560.dp) =
            keyboardTakesPage(hardwareKeyboard = hardware, keyboard = keyboard, windowWidth = width, roomAbove = room)
        assertThat(takes()).isTrue()
        assertThat(takes(hardware = true)).isFalse()
        assertThat(takes(keyboard = 48.dp)).isFalse()
        assertThat(takes(keyboard = 0.dp)).isFalse()
        // A tablet or an unfolded foldable in portrait, its keyboard docked: room for the page above it.
        assertThat(takes(width = 800.dp, room = 900.dp)).isFalse()
        assertThat(takes(width = 673.dp, room = 520.dp)).isFalse()
        // In landscape a docked keyboard leaves a phone's height.
        assertThat(takes(width = 1000.dp, room = 420.dp)).isTrue()
        // A phone in landscape.
        assertThat(takes(width = 914.dp, room = 150.dp)).isTrue()
        // A folded foldable's cover screen.
        assertThat(takes(width = 360.dp, room = 420.dp)).isTrue()
    }

    private companion object {
        const val DRAFT = "Fix the flaky upload test"
        val NavigationBar = 24.dp
        val Keyboard = 300.dp
        val TabletKeyboard = 300.dp
        val Strip = 48.dp
    }
}

private const val PHONE = "w411dp-h914dp-night-420dpi"
private const val TABLET_PORTRAIT = "w800dp-h1280dp-night-320dpi"
private const val TABLET_LANDSCAPE = "w1000dp-h720dp-night-320dpi"
