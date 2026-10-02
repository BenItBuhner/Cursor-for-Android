package com.cursorforandroid.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.components.pressKey
import com.cursorforandroid.ui.home.NewChatHomeCopy
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.view.KeyEvent.KEYCODE_DPAD_LEFT as DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT as DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_TAB as TAB

/**
 * Settings › New chat page: the four layouts as radio buttons in one row on a phone and a tablet alike, each named in
 * full, standing — and read by TalkBack, and stepped through by Tab and the D-pad — Composer only, Projects, Recent
 * agents, then Projects + Recent; until one is tapped the one ringed is the page's own pick — Projects for an account
 * with any, else Recent agents — the tap written at once; the miniatures say nothing of their own; and, with no Projects to pin, the note
 * of what the Projects layout shows meanwhile.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class NewChatHomePickerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var graph: AppGraph
    private lateinit var inputMode: InputModeManager

    @Before
    fun setUp() {
        graph = AppGraph(ApplicationProvider.getApplicationContext<Application>())
    }

    private fun composeSettings(isDemo: Boolean, list: AgentListUiState = AgentListUiState()) {
        compose.setContent {
            inputMode = LocalInputModeManager.current
            CursorTheme(mode = ThemeMode.Dark) {
                SettingsScreen(graph, USER, isDemo = isDemo, onOpenSidebar = null, onBack = {}, newChatList = list)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun option(home: NewChatHome) = compose.onNodeWithTag(NewChatHomePickerTags.of(home))

    private fun saved(): NewChatHome = runBlocking { graph.prefs.newChatHome.first() }

    @Test
    fun `on a phone the four layouts stand abreast as radio buttons, each read as its name in full`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        laidOut()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h780dp-night-420dpi")
    fun `on a narrow phone the four still stand abreast, their names whole`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        laidOut()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h720dp-night-320dpi")
    fun `on a tablet the four share one row, each name on one line`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        laidOut(linesAtMost = 1)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w840dp-h900dp-night-420dpi")
    fun `on a foldable's inner screen the four share one row`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        laidOut()
    }

    @Test
    fun `the four are offered Composer only, Projects, Recent agents, then Projects + Recent, each layout once`() {
        assertThat(NewChatHomePickerOrder).containsExactlyElementsIn(NewChatHome.entries)
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        option(NewChatHomePickerOrder.first()).performScrollTo()
        val leftToRight = NewChatHome.entries.sortedBy { option(it).fetchSemanticsNode().positionInRoot.x }
        assertThat(leftToRight.map(NewChatHomePickerCopy::label))
            .containsExactly("Composer only", "Projects", "Recent agents", "Projects + Recent").inOrder()
        assertThat(leftToRight).isEqualTo(NewChatHomePickerOrder)
    }

    @Test
    fun `TalkBack reads the four in the order they stand, as one group of radio buttons`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        val group = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup) and hasAnyChild(hasTestTag(NewChatHomePickerTags.COMPOSER)))
            .fetchSemanticsNode()
        val read = group.children.filter { SemanticsProperties.Role in it.config }
        assertThat(read.map { it.config[SemanticsProperties.TestTag] })
            .containsExactlyElementsIn(NewChatHomePickerOrder.map(NewChatHomePickerTags::of)).inOrder()
        // Nothing reorders them for a screen reader: no traversal index or group of their own.
        for (node in read) {
            assertThat(SemanticsProperties.TraversalIndex in node.config).isFalse()
            assertThat(SemanticsProperties.IsTraversalGroup in node.config).isFalse()
        }
    }

    /**
     * With a keyboard, Tab and Shift+Tab, and the D-pad's right and left, step from option to option in the order they
     * stand — never into a miniature, whose page has fields and buttons of its own — and Tab goes on past the last.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `Tab and the D-pad go through the four in the order they stand, and back again`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        option(NewChatHomePickerOrder.first()).performScrollTo()
        // The options take focus as they do with a keyboard attached: out of touch mode.
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        for ((forward, back) in listOf(Key(TAB) to Key(TAB, shift = true), Key(DPAD_RIGHT) to Key(DPAD_LEFT))) {
            NewChatHomePickerOrder.zipWithNext { from, to -> assertWithMessage("$forward from $from").that(press(forward, from)).isEqualTo(to) }
            NewChatHomePickerOrder.reversed().zipWithNext { from, to -> assertWithMessage("$back from $from").that(press(back, from)).isEqualTo(to) }
        }
        press(Key(TAB), NewChatHomePickerOrder.last())
        assertThat(focusedNodes()).isNotEmpty()
        assertThat(NewChatHomePickerOrder.filter(::focused)).isEmpty()
    }

    private data class Key(val code: Int, val shift: Boolean = false)

    /** [key] pressed with [from] focused: the option holding the focus after it, if any does. */
    private fun press(key: Key, from: NewChatHome): NewChatHome? {
        option(from).pressKey(key.code, shift = key.shift)
        compose.waitForIdle()
        return NewChatHomePickerOrder.singleOrNull(::focused)
    }

    private fun focusedNodes() = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true), useUnmergedTree = true).fetchSemanticsNodes()

    private fun focused(home: NewChatHome) = option(home).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }

    /**
     * The four in one row, in the order they are offered, as wide and as tall as one another and their checks in a
     * line at the foot; every name laid out whole on [linesAtMost] lines at most, none cut short or run over.
     */
    private fun laidOut(linesAtMost: Int = 2) {
        option(NewChatHomePickerOrder.first()).performScrollTo()
        val row = NewChatHomePickerOrder.map { option(it).fetchSemanticsNode() }
        row.zipWithNext { left, right ->
            assertThat(right.positionInRoot.y).isEqualTo(left.positionInRoot.y)
            assertThat(right.size.height).isEqualTo(left.size.height)
            // Equal shares of the row, to the pixel the row has to split.
            assertThat(right.size.width).isWithin(1).of(left.size.width)
            assertThat(right.positionInRoot.x).isGreaterThan(left.positionInRoot.x + left.size.width - 1)
        }
        for (home in NewChatHomePickerOrder) {
            val label = NewChatHomePickerCopy.label(home)
            option(home)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(label))))
            val text = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(NewChatHomePickerTags.of(home))), useUnmergedTree = true).fetchSemanticsNode()
            val layout = mutableListOf<TextLayoutResult>().also { text.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.single()
            assertWithMessage(label).that(layout.lineCount).isAtMost(linesAtMost)
            assertWithMessage(label).that(layout.getLineEnd(layout.lineCount - 1)).isEqualTo(label.length)
            assertWithMessage(label).that(layout.didOverflowHeight).isFalse()
            for (line in 0 until layout.lineCount) assertWithMessage(label).that(layout.isLineEllipsized(line)).isFalse()
            // A name broken over two lines breaks at its space, never inside a word.
            if (layout.lineCount == 2) assertWithMessage(label).that(label[layout.getLineEnd(0, visibleEnd = true)]).isEqualTo(' ')
        }
    }

    @Test
    fun `Recent agents is chosen until another is tapped, and each tap is written at once`() {
        composeSettings(isDemo = true)
        assertThat(saved()).isEqualTo(NewChatHome.RECENT)
        option(NewChatHome.RECENT).assertIsSelected()
        option(NewChatHome.PROJECTS).assertIsNotSelected()
        option(NewChatHome.PROJECTS_RECENT).assertIsNotSelected()
        option(NewChatHome.COMPOSER).assertIsNotSelected()

        for (home in listOf(NewChatHome.COMPOSER, NewChatHome.PROJECTS, NewChatHome.PROJECTS_RECENT, NewChatHome.RECENT)) {
            option(home).performScrollTo().performClick()
            compose.waitUntil(10_000) { saved() == home }
            compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(NewChatHomePickerTags.of(home)) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
            for (other in NewChatHome.entries - home) option(other).assertIsNotSelected()
        }
    }

    /**
     * A tap on the picture itself — where a miniature draws a recent chat's row or a Project's shortcut, each a
     * control on the real page — chooses the layout, rather than being taken by the row drawn there.
     */
    @Test
    fun `with nothing chosen an account with Projects has Projects ringed, and one without has Recent agents`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        option(NewChatHome.PROJECTS).performScrollTo().assertIsSelected()
        option(NewChatHome.RECENT).assertIsNotSelected()
        // Ringed for the account as it stands, not written: the reader has not chosen.
        assertThat(runBlocking { graph.prefs.newChatHomeChoice.first() }.chosen).isNull()
    }

    @Test
    fun `with nothing chosen an account without Projects has Recent agents ringed`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.withoutProjects())
        option(NewChatHome.RECENT).performScrollTo().assertIsSelected()
        option(NewChatHome.PROJECTS).assertIsNotSelected()
    }

    @Test
    fun `a tap anywhere on a miniature chooses its layout, over the rows drawn in it`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        option(NewChatHome.PROJECTS).performScrollTo().performTouchInput { click(Offset(centerX, height * 0.6f)) }
        compose.waitUntil(10_000) { saved() == NewChatHome.PROJECTS }
        option(NewChatHome.RECENT).performTouchInput { click(Offset(centerX, height * 0.6f)) }
        compose.waitUntil(10_000) { saved() == NewChatHome.RECENT }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(NewChatHomePickerTags.RECENT) and isSelected()).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `the miniatures are the page, yet nothing in them is read out or can be reached`() {
        composeSettings(isDemo = true, list = NewChatHomeFixtures.list())
        compose.onAllNodes(hasText(NewChatHomeCopy.PLACEHOLDER, substring = true)).assertCountEquals(0)
        // The miniature's composer is a real text field; Settings has none of its own.
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun `without Extended mode Projects can still be chosen, and the card says what it shows until the mode is on`() {
        composeSettings(isDemo = false)
        compose.onNodeWithTag(NewChatHomePickerTags.PROJECTS).performScrollTo()
        compose.onAllNodes(hasText(NewChatHomePickerCopy.NEEDS_MODE)).assertCountEquals(1)
        option(NewChatHome.PROJECTS).performClick()
        compose.waitUntil(10_000) { saved() == NewChatHome.PROJECTS }
    }

    @Test
    fun `with Projects to pin there is no note`() {
        composeSettings(isDemo = true)
        compose.onAllNodes(hasText(NewChatHomePickerCopy.NEEDS_MODE)).assertCountEquals(0)
    }

    private companion object {
        val USER = CursorUser("Cursor for Android (Pixel 9)", "alex@example.com", "Alex", "Rivera", 7L)
    }
}
