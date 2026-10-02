package com.cursorforandroid.ui.conversation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A row of the transcript's list that changes height while it crosses the top edge absorbs the change itself, so the
 * rows below it stay where they are (see [TranscriptScroll.absorbingHeight], [absorbingGrowth]) — at rest and in
 * the middle of a fling. A row wholly on screen that changes height moves the rows below it, as a list does; and a
 * following (bottom-anchored) list is left to itself.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-420dpi")
class TranscriptRowGrowthTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val heights = mutableStateListOf<Int>().apply { repeat(ROWS) { add(ROW_DP) } }
    private lateinit var list: LazyListState
    private lateinit var scroll: TranscriptScroll

    private fun show(following: Boolean = false) {
        compose.setContent {
            list = rememberLazyListState()
            val pinned = remember { mutableStateOf(following) }
            scroll = remember { TranscriptScroll(list, pinned) }
            // Dragged and flung the way the screen's list is (see [ScreenScroll]): the list's own fling is of the
            // priority a re-anchoring cancels.
            val reader = rememberReaderScroll(scroll)
            LazyColumn(state = list, reverseLayout = following, userScrollEnabled = false, modifier = Modifier.fillMaxWidth().height(LIST_DP.dp).testTag("list").readerScrolling(reader)) {
                items(ROWS, key = { "r$it" }) { i ->
                    val growth = remember { RowGrowth() }
                    Box(Modifier.fillMaxWidth().height(heights[i].dp).testTag("r$i").absorbingGrowth(scroll, "r$i", growth))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun top(i: Int): Float = compose.onNodeWithTag("r$i").fetchSemanticsNode().positionInRoot.y

    private fun height(i: Int): Int = compose.onNodeWithTag("r$i").fetchSemanticsNode().size.height

    private fun composed(i: Int): Boolean = compose.onAllNodesWithTag("r$i").fetchSemanticsNodes().isNotEmpty()

    /** Every composed row's unclipped top, by index. */
    private fun tops(): Map<Int, Float> =
        (0 until ROWS).mapNotNull { i -> compose.onAllNodesWithTag("r$i").fetchSemanticsNodes().singleOrNull()?.let { i to it.positionInRoot.y } }.toMap()

    private val px: Float get() = compose.density.density

    /** Row [index] across the top edge: its top [hidden] dp above the viewport. */
    private fun straddle(index: Int, hidden: Int) {
        runBlocking { list.scrollToItem(index, (hidden * px).toInt()) }
        compose.waitForIdle()
        assertThat(top(index)).isWithin(1f).of(-hidden * px)
    }

    @Test
    fun `a row across the top edge that grows at rest leaves the rows below it where they were`() {
        show()
        straddle(3, hidden = 60)
        val below = (4..6).associateWith { top(it) }
        val firstOffset = list.firstVisibleItemScrollOffset

        heights[3] = ROW_DP + 300
        compose.waitForIdle()

        for ((i, was) in below) assertWithMessage("row $i").that(top(i)).isWithin(0.5f).of(was)
        assertThat(height(3).toFloat()).isWithin(1f).of((ROW_DP + 300) * px)
        // The list anchors the row that much higher: its hidden part grew by what the row grew.
        assertThat(list.firstVisibleItemIndex).isEqualTo(3)
        assertThat(list.firstVisibleItemScrollOffset).isEqualTo(firstOffset + (300 * px).toInt())
        assertThat(top(3)).isWithin(1f).of(-(60 + 300) * px)
    }

    @Test
    fun `a row across the top edge that shrinks at rest leaves the rows below it where they were`() {
        show()
        straddle(3, hidden = 60)
        val below = (4..6).associateWith { top(it) }

        // Shrinks by more than is hidden: the row's top comes down into view and the row above fills in over it.
        heights[3] = ROW_DP - 100
        compose.waitForIdle()

        for ((i, was) in below) assertWithMessage("row $i").that(top(i)).isWithin(0.5f).of(was)
        assertThat(top(3)).isWithin(1f).of((-60 + 100) * px)
        assertThat(composed(2)).isTrue()
    }

    @Test
    fun `a row wholly on screen that grows moves the rows below it, as before`() {
        show()
        straddle(3, hidden = 60)
        val fifth = top(5)
        heights[4] = ROW_DP + 100
        compose.waitForIdle()
        assertThat(top(4)).isWithin(1f).of(top(3) + ROW_DP * px)
        assertThat(top(5)).isWithin(1f).of(fifth + 100 * px)
    }

    @Test
    fun `following, a row across the bottom-anchored list's top edge is left to the list`() {
        show(following = true)
        // Bottom-anchored: index 0 is the newest row at the bottom. Scroll up until row 3 crosses the top edge.
        runBlocking { list.scrollToItem(0, 0) }
        compose.waitForIdle()
        val visibleTop = (0 until ROWS).first { composed(it) && top(it) < 0f }
        val below = (0 until visibleTop).associateWith { top(it) }
        heights[visibleTop] = ROW_DP + 300
        compose.waitForIdle()
        // The list keeps its bottom-most row where it is on its own; what grew went up out of sight.
        for ((i, was) in below) assertWithMessage("row $i").that(top(i)).isWithin(0.5f).of(was)
    }

    @Test
    fun `a row that grows mid-fling costs the fling nothing - every frame moves the rows below by less than the one before`() {
        show()
        straddle(20, hidden = 20)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("list").performTouchInput { swipeDown(startY = top + 100f, endY = top + 700f, durationMillis = SWIPE_MS) }
        val moves = mutableListOf<Float>()
        var grew = false
        var before = tops()
        for (frame in 1..90) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val now = tops()
            // How far the rows on screen in both frames moved: the same for all of them, bar the one that grew. The
            // frames of the finger's own drag are not the fling's.
            val common = before.keys.intersect(now.keys).filter { before.getValue(it) >= 0f && now.getValue(it) + ROW_DP * px <= LIST_DP * px }
            if (common.isNotEmpty() && frame > SWIPE_MS / 16 + 2) moves += common.map { now.getValue(it) - before.getValue(it) }.sorted()[common.size / 2]
            before = now
            val first = list.layoutInfo.visibleItemsInfo.first()
            if (!grew && moves.size > 2 && first.offset < 0) {
                val index = first.index
                compose.runOnIdle { heights[index] = ROW_DP + 400 }
                grew = true
            }
            if (!list.isScrollInProgress && frame > 10) break
        }
        assertWithMessage("a row grew while the fling ran").that(grew).isTrue()
        assertWithMessage("the fling ran on").that(moves.size).isGreaterThan(8)
        // A fling only slows, and slowly: a frame moving the rows further than the one before, or much less, is a jump.
        moves.zipWithNext().forEachIndexed { i, (before, after) ->
            assertWithMessage("frame ${i + 2} moved $after px after $before px; moves: $moves").that(after).isAtMost(before + 2f)
            assertWithMessage("frame ${i + 2} moved $after px after $before px; moves: $moves").that(after).isAtLeast(before - 10f)
        }
    }

    private companion object {
        const val ROWS = 40
        const val ROW_DP = 180
        const val LIST_DP = 800
        const val SWIPE_MS = 80L
    }
}
