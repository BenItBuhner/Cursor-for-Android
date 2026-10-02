package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.view.KeyEvent as NativeKeyEvent

/**
 * The shared picker driven as a desktop's dropdown is: from a physical keyboard, by a pointer, and on a phone, where
 * a submenu has no room beside it, as against a tablet, where it cascades.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CursorPickerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var picked = mutableListOf<String>()
    private var dismissed = false
    private var thinking by mutableStateOf(false)
    private var open by mutableStateOf(false)

    private fun entries(query: String): List<PickerEntry> = buildList {
        val fruit = listOf("Apple", "Banana", "Cherry").filter { pickerMatches(query, it) }
        if (fruit.isNotEmpty()) add(PickerSection("fruit", "Fruit"))
        fruit.forEach { name -> add(PickerItem(name.lowercase(), name, selected = name == "Cherry", onPick = { picked += name })) }
        if (query.isBlank()) {
            add(
                PickerItem(
                    key = "more",
                    label = "More",
                    detail = "3 kinds",
                    submenu = PickerSubmenu("More", searchPlaceholder = "Search more") { q ->
                        listOf("Date", "Elderberry", "Fig").filter { pickerMatches(q, it) }.map { name ->
                            PickerItem("more-$name", name, dismiss = PickerDismiss.Submenu, onPick = { picked += name })
                        } + PickerToggle("thinking", "Thinking", thinking, onToggle = { thinking = it })
                    },
                ),
            )
            add(PickerItem("grape", "Grape", enabled = false, onPick = { picked += "Grape" }))
        }
        if (query.isNotBlank() && fruit.isEmpty()) add(PickerNote("none", "No fruit matches"))
        add(PickerDivider("divider"))
        add(PickerAction("add", "Add fruit", onClick = { picked += "add" }, dismiss = PickerDismiss.All))
    }

    /** An anchor near the top of the window and, a frame later, the picker opened from it, as a tap on a chip does. */
    private fun show(anchored: Boolean = true, search: Boolean = true) {
        compose.setContent {
            val anchor = rememberPopoverAnchor()
            CursorTheme(mode = ThemeMode.Dark) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 80.dp).size(120.dp, 36.dp).popoverAnchor(anchor).testTag("anchor"))
                    if (open) {
                        CursorPicker(
                            onDismiss = { dismissed = true; open = false },
                            anchor = if (anchored) anchor else null,
                            title = "Fruit",
                            searchPlaceholder = if (search) "Search fruit" else null,
                            entries = ::entries,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        open = true
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(PickerTags.Picker)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private val picker get() = compose.onNodeWithTag(PickerTags.Picker)

    private fun press(keyCode: Int, ctrl: Boolean = false, keyboard: Keyboard = Keyboard.Physical) {
        picker.pressKey(keyCode, keyboard = keyboard, ctrl = ctrl)
        compose.waitForIdle()
    }

    private fun type(text: String) = text.forEach { char ->
        press(NativeKeyEvent.keyCodeFromString("KEYCODE_${char.uppercaseChar()}"))
    }

    private val highlighted = SemanticsMatcher.expectValue(PickerHighlighted, true)

    private fun assertHighlighted(label: String) {
        compose.onNode(hasText(label, substring = true) and highlighted).assertExists("$label is not the highlighted row")
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `an anchored picker opens in its own window under the anchor`() {
        show()
        val anchor = compose.onNodeWithTag("anchor").fetchSemanticsNode().boundsInWindow
        val first = compose.onNodeWithText("Fruit").fetchSemanticsNode().boundsInWindow
        assertThat(first.top).isGreaterThan(anchor.bottom)
        assertThat(first.left).isAtLeast(anchor.left)
        assertThat(compose.onAllNodes(isDialog()).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `with no anchor the picker is a bottom sheet with the same rows`() {
        show(anchored = false)
        assertThat(compose.onAllNodes(isDialog()).fetchSemanticsNodes()).isNotEmpty()
        compose.onNodeWithText("Banana").assertIsDisplayed()
        compose.onNodeWithText("Add fruit").assertIsDisplayed()
    }

    @Test
    fun `the highlight starts on the selection and the arrows move it round the ends, past what cannot be picked`() {
        show()
        assertHighlighted("Cherry")
        press(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        assertHighlighted("More")
        // Grape is disabled; the divider and header are not rows.
        press(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        assertHighlighted("Add fruit")
        press(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        assertHighlighted("Apple")
        press(NativeKeyEvent.KEYCODE_DPAD_UP)
        assertHighlighted("Add fruit")
    }

    @Test
    fun `Ctrl+N and Ctrl+P move as the arrows do`() {
        show()
        press(NativeKeyEvent.KEYCODE_N, ctrl = true)
        assertHighlighted("More")
        press(NativeKeyEvent.KEYCODE_P, ctrl = true)
        assertHighlighted("Cherry")
        assertThat(picked).isEmpty()
    }

    @Test
    fun `Enter picks the highlighted row and the picker closes`() {
        show()
        press(NativeKeyEvent.KEYCODE_DPAD_UP)
        press(NativeKeyEvent.KEYCODE_DPAD_UP)
        assertHighlighted("Apple")
        press(NativeKeyEvent.KEYCODE_ENTER)
        assertThat(picked).containsExactly("Apple")
        compose.waitUntil(10_000) { dismissed }
    }

    @Test
    fun `typing searches, the highlight lands on the first match, and Enter picks it`() {
        show()
        type("ban")
        compose.onNodeWithTag(PickerTags.Search).assert(hasText("ban"))
        assertThat(compose.onAllNodes(hasText("Apple")).fetchSemanticsNodes()).isEmpty()
        assertHighlighted("Banana")
        press(NativeKeyEvent.KEYCODE_ENTER)
        assertThat(picked).containsExactly("Banana")
    }

    @Test
    fun `a search that matches nothing says so and Enter picks the action left`() {
        show()
        type("zz")
        compose.onNodeWithText("No fruit matches").assertIsDisplayed()
        assertHighlighted("Add fruit")
        press(NativeKeyEvent.KEYCODE_ENTER)
        assertThat(picked).containsExactly("add")
        compose.waitUntil(10_000) { dismissed }
    }

    @Test
    fun `Esc closes the picker without picking`() {
        show()
        press(NativeKeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(10_000) { dismissed }
        assertThat(picked).isEmpty()
    }

    @Test
    fun `the on-screen keyboard's keys are the field's, not the picker's`() {
        show()
        press(NativeKeyEvent.KEYCODE_DPAD_DOWN, keyboard = Keyboard.OnScreen)
        assertHighlighted("Cherry")
    }

    @Test
    fun `a tap outside the picker closes it`() {
        show()
        picker.performTouchInput { click(bottomRight - androidx.compose.ui.geometry.Offset(20f, 20f)) }
        compose.waitUntil(10_000) { dismissed }
        assertThat(picked).isEmpty()
    }

    @Test
    fun `TalkBack can close the picker from outside it, after its rows`() {
        show()
        compose.onNodeWithTag(PickerTags.Backdrop).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Close Fruit")))
        compose.onNodeWithTag(PickerTags.Backdrop).performClick()
        compose.waitUntil(10_000) { dismissed }
    }

    @Test
    fun `tapping a row picks it and the picker closes`() {
        show()
        compose.onNodeWithText("Apple").performClick()
        assertThat(picked).containsExactly("Apple")
        compose.waitUntil(10_000) { dismissed }
    }

    @Test
    fun `on a phone a submenu opens in the picker's place, and Left or Esc steps back out`() {
        show()
        press(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        assertThat(exists(PickerTags.Back)).isTrue()
        assertThat(exists(PickerTags.Submenu)).isFalse()
        assertHighlighted("Date")
        assertThat(compose.onAllNodes(hasText("Banana")).fetchSemanticsNodes()).isEmpty()

        press(NativeKeyEvent.KEYCODE_DPAD_LEFT)
        compose.onNodeWithText("Banana").assertIsDisplayed()
        assertHighlighted("More")

        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        press(NativeKeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithText("Banana").assertIsDisplayed()
        assertThat(dismissed).isFalse()
        press(NativeKeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(10_000) { dismissed }
    }

    @Test
    fun `a tap on a row with a submenu opens it, and the back row returns`() {
        show()
        compose.onNodeWithText("More", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Elderberry").assertIsDisplayed()
        compose.onNodeWithTag(PickerTags.Back).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Banana").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-320dpi")
    fun `on a tablet a submenu cascades beside the picker, which stays as it was`() {
        show()
        press(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        assertThat(exists(PickerTags.Submenu)).isTrue()
        assertThat(exists(PickerTags.Back)).isFalse()
        compose.onNodeWithText("Banana").assertIsDisplayed()
        // The row that opened it stays lit; the keyboard is in the submenu now.
        assertHighlighted("More")
        assertHighlighted("Date")
        val root = compose.onNodeWithText("More", substring = true).fetchSemanticsNode().boundsInWindow
        val sub = compose.onNodeWithTag(PickerTags.Submenu).fetchSemanticsNode().boundsInWindow
        assertThat(sub.left).isAtLeast(root.right)
        assertThat(sub.top).isWithin(16f).of(root.top)

        press(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        assertHighlighted("Elderberry")
        press(NativeKeyEvent.KEYCODE_ENTER)
        assertThat(picked).containsExactly("Elderberry")
        // Elderberry closes its submenu only.
        compose.waitUntil(10_000) { !exists(PickerTags.Submenu) }
        assertThat(dismissed).isFalse()
        compose.onNodeWithText("Banana").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-320dpi")
    fun `a submenu's own search takes typing while it is open, and Esc goes back one level at a time`() {
        show()
        press(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        type("fi")
        assertHighlighted("Fig")
        assertThat(compose.onAllNodes(hasText("Date")).fetchSemanticsNodes()).isEmpty()
        // The picker's own search is untouched.
        compose.onNodeWithText("Banana").assertIsDisplayed()
        press(NativeKeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(10_000) { !exists(PickerTags.Submenu) }
        assertThat(dismissed).isFalse()
        press(NativeKeyEvent.KEYCODE_ESCAPE)
        compose.waitUntil(10_000) { dismissed }
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-320dpi")
    fun `Space flips a switch and keeps the submenu open`() {
        show()
        press(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        press(NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        press(NativeKeyEvent.KEYCODE_DPAD_UP)
        assertHighlighted("Thinking")
        press(NativeKeyEvent.KEYCODE_SPACE)
        assertThat(thinking).isTrue()
        assertThat(exists(PickerTags.Submenu)).isTrue()
        compose.onNode(hasText("Thinking") and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)).assertExists()
    }

    @Test
    fun `a page with no search jumps to the row a letter starts`() {
        show(search = false)
        type("b")
        assertHighlighted("Banana")
        type("a")
        assertHighlighted("Add fruit")
        type("a")
        assertHighlighted("Apple")
        assertThat(picked).isEmpty()
    }

    @Test
    fun `TalkBack hears a titled pane, the selection, headings, a switch and which rows open a submenu`() {
        show()
        picker.assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Fruit"))
        compose.onNodeWithText("Cherry").assertIsSelected()
        compose.onNodeWithText("Fruit").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("More", substring = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Has submenu"))
        compose.onNodeWithText("Add fruit").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNodeWithText("More", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Thinking").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
    }

    @Test
    fun `phone rows are 48dp touch targets`() {
        show()
        val row = compose.onNodeWithText("Banana").fetchSemanticsNode().boundsInWindow
        assertThat(row.height / compose.density.density).isWithin(0.5f).of(48f)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-night-320dpi")
    fun `tablet rows are 44dp, tighter for a pointer`() {
        show()
        val row = compose.onNodeWithText("Banana").fetchSemanticsNode().boundsInWindow
        assertThat(row.height / compose.density.density).isWithin(0.5f).of(44f)
    }
}
