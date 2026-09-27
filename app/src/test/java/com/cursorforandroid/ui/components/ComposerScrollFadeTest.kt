package com.cursorforandroid.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
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
import kotlin.math.roundToInt

/**
 * The composer's text fades at whichever edge it runs past once it scrolls inside the field — the top once scrolled
 * down, the bottom while more is below — and not at all while it fits; collapsed and expanded alike. Read off the
 * field's own pixels: every line carries ascenders and descenders, so a strip at the text's first or last ink always
 * crosses a line, and a strip whose brightest ink stays well short of the text's full brightness is faded.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerScrollFadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var draft by mutableStateOf("")
    private var density = 1f

    private fun show(lines: Int) {
        draft = (1..lines).joinToString("\n") { "Hgjpqy $it Hgjpqy Hgjpqy" }
        compose.setContent {
            density = LocalDensity.current.density
            CursorTheme(mode = ThemeMode.Dark) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth())
                    ComposerBox(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = "Follow up…",
                        onSend = {},
                        plusMenu = ComposerMenuActions(onPickMedia = {}),
                        modelLabel = "Claude Fable 5.1",
                        onModel = {},
                    )
                }
            }
        }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private val field get() = compose.onNode(hasSetTextAction())

    private fun swipe(up: Boolean, times: Int = 1) {
        repeat(times) { field.performTouchInput { if (up) swipeUp() else swipeDown() } }
        settle()
    }

    private fun expand() {
        compose.onNode(hasTestTag("composer-expand"), useUnmergedTree = true).performClick()
        settle()
    }

    /** Whether the field's top and bottom edges are faded. */
    private fun faded(): Pair<Boolean, Boolean> {
        // Drawn here rather than through captureToImage, whose forced redraw never completes under Robolectric.
        val root = compose.activity.window.decorView
        val window = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(window)) }
        val box = field.fetchSemanticsNode().boundsInWindow
        val bitmap = Bitmap.createBitmap(window, box.left.roundToInt(), box.top.roundToInt(), box.width.roundToInt(), box.height.roundToInt())
        fun luminance(x: Int, y: Int): Float {
            val c = bitmap.getPixel(x, y)
            return 0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)
        }
        fun brightest(rows: IntRange) = rows.maxOf { y -> (0 until bitmap.width).maxOf { x -> luminance(x, y) } }
        val all = 0 until bitmap.height
        val background = all.minOf { y -> (0 until bitmap.width).minOf { x -> luminance(x, y) } }
        val full = brightest(all) - background
        // Measured from the first and last rows with any ink, so a field taller than its text is judged by the text.
        val inked = all.filter { y -> (0 until bitmap.width).any { x -> luminance(x, y) - background > 0.15f * full } }
        val strip = (12 * density).toInt()
        fun isFaded(rows: IntRange) = (brightest(rows) - background) / full < 0.75f
        return isFaded(inked.first() until inked.first() + strip) to isFaded(inked.last() - strip + 1..inked.last())
    }

    @Test
    fun `text that fits is not faded at either edge`() {
        show(lines = 3)
        assertThat(faded()).isEqualTo(false to false)
    }

    @Test
    fun `overflowing at the top, only the bottom fades`() {
        show(lines = 30)
        assertThat(faded()).isEqualTo(false to true)
    }

    @Test
    fun `scrolled into the middle, both edges fade`() {
        show(lines = 30)
        field.performTouchInput { swipeUp(startY = centerY + 60f, endY = centerY - 60f) }
        settle()
        assertThat(faded()).isEqualTo(true to true)
    }

    @Test
    fun `scrolled to the end, only the top fades, and back at the start only the bottom`() {
        show(lines = 30)
        swipe(up = true, times = 4)
        assertThat(faded()).isEqualTo(true to false)
        swipe(up = false, times = 4)
        assertThat(faded()).isEqualTo(false to true)
    }

    @Test
    fun `expanded, text that now fits is not faded`() {
        show(lines = 20)
        assertThat(faded()).isEqualTo(false to true)
        expand()
        assertThat(faded()).isEqualTo(false to false)
    }

    @Test
    fun `expanded, text that still overflows fades at the edge it runs past`() {
        show(lines = 90)
        expand()
        assertThat(faded()).isEqualTo(false to true)
        swipe(up = true, times = 8)
        assertThat(faded()).isEqualTo(true to false)
    }
}
