package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt
import android.graphics.Canvas as AndroidCanvas

/**
 * The pull's indicator leaving, read off the pixels at the middle of its disc, and the transcript's rows under the gap
 * it stands in. Answered, it fades and shrinks out over a run of frames as the transcript goes home, still spinning —
 * never whole one frame and gone the next — and a pull let go short fades out with the gap closing on it. Under
 * reduced motion it is gone at once. While the pull is out, the lazy transcript is laid out past its bottom edge, so a
 * row the answer brought in shows under the indicator rather than the list's edge cut flat across the gap; the
 * scroll's range is the same either way.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class CatchUpExitFadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val status = MutableStateFlow<CatchUpStatus>(CatchUpStatus.Idle)
    private lateinit var pull: CatchUpPull
    private lateinit var screenDensity: Density
    private var container = Color.Unspecified
    private val rows = mutableStateListOf<String>()

    /**
     * The screen's own arrangement: a following (bottom-anchored) lazy transcript lifted by the pull with its reach,
     * its bottom padding the pull's, over a composer's band; the indicator over the transcript's bottom edge.
     */
    private fun screen(animate: Boolean = true, rowCount: Int = 12) {
        rows.clear()
        repeat(rowCount) { rows += "row-$it" }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                screenDensity = LocalDensity.current
                container = CursorTheme.colors.elevated
                pull = remember { catchUpPullFor(screenDensity) }
                Column(Modifier.fillMaxSize().background(Backdrop)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            state = rememberLazyListState(),
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize().catchUpLift(pull, reach = Padding).testTag("list"),
                            contentPadding = catchUpPadding(pull, Padding),
                        ) {
                            items(rows.asReversed(), key = { it }) { key ->
                                Box(Modifier.fillMaxWidth().height(RowHeight).background(if (key == NEW_ROW) Fresh else Row))
                            }
                        }
                        CatchUpIndicator(pull, status, onSettled = {}, modifier = Modifier.align(Alignment.BottomCenter), edgeGap = EdgeGap, animate = { animate })
                    }
                    Box(Modifier.fillMaxWidth().height(96.dp).testTag("composer"))
                }
            }
        }
        settle()
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun settle() = repeat(60) { frame() }

    private fun px(dp: Float): Float = with(screenDensity) { dp.dp.toPx() }

    private val edge: Float get() = compose.onNodeWithTag("composer").fetchSemanticsNode().boundsInRoot.top

    private fun shot(): Bitmap {
        val view = composeView()
        val whole = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(AndroidCanvas(whole)) }
        return whole
    }

    /**
     * How much of the disc covers the middle of the gap, 0 to 1: the pixel at the disc's centre (inside the spinner's
     * ring, which no arrow or arc reaches) between the backdrop showing through and the disc's own fill.
     */
    private fun Bitmap.discOpacity(): Float {
        val x = width / 2
        val y = (edge - (px(EdgeGap.value) + pull.lift) / 2f).roundToInt()
        val pixel = getPixel(x, y)
        val behind = Backdrop.toArgb()
        val disc = container.toArgb()
        // The green channel parts the two the most.
        val g = { c: Int -> ((c shr 8) and 0xFF).toFloat() }
        return ((g(pixel) - g(behind)) / (g(disc) - g(behind))).coerceIn(0f, 1f)
    }

    private fun answered(): List<Float> {
        pull.stretch(pull.thresholdPx * 1.6f)
        frame()
        assertThat(pull.release()).isTrue()
        status.value = CatchUpStatus.Checking
        settle()
        assertThat(shot().discOpacity()).isGreaterThan(0.97f)
        status.value = CatchUpStatus.Done(newMessages = 0, changed = false)
        return buildList {
            repeat(40) {
                frame()
                add(shot().discOpacity())
            }
        }
    }

    @Test
    fun `answered, the indicator fades out over a run of frames as the transcript goes home, never cut`() {
        screen()
        val opacity = answered()
        // Every frame a little less of it, never more back.
        opacity.zipWithNext { a, b -> assertWithMessage("opacity by frame: $opacity").that(b).isAtMost(a + 0.03f) }
        // Out over at least eight frames in between, none of them a cut of more than a quarter.
        assertWithMessage("opacity by frame: $opacity").that(opacity.count { it in 0.05f..0.95f }).isAtLeast(8)
        (listOf(1f) + opacity).zipWithNext { a, b -> assertWithMessage("opacity by frame: $opacity").that(a - b).isLessThan(0.25f) }
        // Gone before the transcript is home: the gap never closes over a disc still drawn.
        val goneAt = opacity.indexOfFirst { it < 0.02f }
        assertThat(goneAt).isAtLeast(0)
        settle()
        assertThat(pull.lift).isEqualTo(0f)
        assertThat(pull.out).isFalse()
    }

    @Test
    fun `a pull let go short fades with the gap closing on it`() {
        screen()
        pull.stretch(pull.thresholdPx * 0.9f)
        frame()
        assertThat(shot().discOpacity()).isGreaterThan(0.97f)
        assertThat(pull.release()).isFalse()
        val opacity = buildList {
            repeat(40) {
                frame()
                add(shot().discOpacity())
            }
        }
        assertWithMessage("opacity by frame: $opacity").that(opacity.count { it in 0.05f..0.95f }).isAtLeast(4)
        (listOf(1f) + opacity).zipWithNext { a, b -> assertWithMessage("opacity by frame: $opacity").that(a - b).isLessThan(0.3f) }
        assertThat(opacity.last()).isLessThan(0.02f)
    }

    @Test
    fun `under reduced motion the answered indicator is gone at once`() {
        screen(animate = false)
        val opacity = answered()
        assertThat(opacity.first()).isLessThan(0.02f)
        assertThat(pull.lift).isGreaterThan(0f)
    }

    @Test
    fun `while the pull is out, a row the answer brought in shows under the indicator, not the list's edge`() {
        screen()
        val list = compose.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot
        val restingBottom = list.bottom - px(EdgeGap.value)
        pull.stretch(pull.thresholdPx * 1.6f)
        frame()
        pull.release()
        status.value = CatchUpStatus.Checking
        settle()
        // A row lands below the newest, where the list's keyed anchoring leaves it: past the bottom edge.
        rows += NEW_ROW
        repeat(2) { frame() }
        val shown = shot()
        val x = px(24f).roundToInt()
        // Past the list's own bottom edge, lifted, where its edge used to cut everything off: the row that came in.
        val listEdge = (list.bottom - pull.lift).roundToInt()
        assertThat(listEdge.toFloat()).isGreaterThan(restingBottom - pull.lift)
        val under = (listEdge + px(2f).roundToInt() until edge.roundToInt()).filter { close(shown.getPixel(x, it), Fresh.toArgb()) }
        assertWithMessage("rows drawn under the gap past the list's edge").that(under.size).isAtLeast(px(8f).roundToInt())
        status.value = CatchUpStatus.Done(newMessages = 1, changed = true)
        settle()
        assertThat(pull.out).isFalse()
    }

    @Test
    fun `at rest the reach adds nothing, the newest row resting the edge gap over the composer`() {
        screen()
        val shown = shot()
        val x = px(24f).roundToInt()
        val rowBottom = (0 until edge.roundToInt()).last { close(shown.getPixel(x, it), Row.toArgb()) } + 1
        assertThat(rowBottom.toFloat()).isWithin(1.5f).of(edge - px(EdgeGap.value))
        assertThat(pull.out).isFalse()
    }

    private fun composeView(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return checkNotNull(find(compose.activity.findViewById(android.R.id.content))) { "No AndroidComposeView in the activity" }
    }

    private fun close(a: Int, b: Int): Boolean = maxOf(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
        abs((a and 0xFF) - (b and 0xFF)),
    ) <= 3

    private companion object {
        const val NEW_ROW = "row-new"
        val Backdrop = Color(0xFF00C853)
        val Row = Color(0xFF2962FF)
        val Fresh = Color(0xFFFF6D00)
        val EdgeGap = 12.dp
        val RowHeight = 64.dp
        val Padding = PaddingValues(bottom = EdgeGap)
    }
}
