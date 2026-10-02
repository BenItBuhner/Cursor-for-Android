package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.DOCKED_JUMP_BUTTON_TAG
import com.cursorforandroid.ui.components.DockGap
import com.cursorforandroid.ui.components.DockedJumpButton
import com.cursorforandroid.ui.components.DockedRowHeight
import com.cursorforandroid.ui.components.composerDockPadding
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The jump button docked at the end of the strips' row over the composer ([GoalDock]'s aside): a docked card of its
 * own, flush with the strips' end edge, as far over the composer as the lowest strip and as far from the strips as
 * they are from the composer; the strips give up their end edge to it as it comes and take it back as it goes, on the
 * button's own spring, with nothing jumping. With no strip beside it, it stands where the lowest one would, the dock no
 * taller for it, and still takes the tap. Under reduced motion all of it snaps.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class DockedJumpButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private var shown by mutableStateOf(false)
    private var clicks = 0
    private lateinit var density: Density

    private val queued = listOf(
        QueuedFollowUp("q-1", "Then add a test for the light theme", queuedAtMillis = 1_000L),
        QueuedFollowUp("q-2", "And open a draft PR once it is green", queuedAtMillis = 2_000L),
    )
    private val goal = Goal("Ship the docked jump button and its motion", GoalStatus.ACTIVE, accruingSinceMillis = 0L, continuationCount = 1, source = Goal.Source.Account)

    private fun dock(withGoal: Boolean, cards: Int, animate: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                density = androidx.compose.ui.platform.LocalDensity.current
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().testTag(TRANSCRIPT))
                    Column(Modifier.fillMaxWidth().composerDockPadding().testTag(DOCK), horizontalAlignment = Alignment.CenterHorizontally) {
                        GoalDock(
                            open = false,
                            goal = if (withGoal) {
                                { GoalStrip(goal, expanded = false, onExpandedChange = {}, clock = { 60_000L }, animate = { animate }, modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp)) }
                            } else {
                                null
                            },
                            modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
                            cards = {
                                val keys = queued.take(cards).map { it.id }
                                QueueStack(keys = keys, stacked = false, onStackedChange = {}, animate = { animate }, gapBelow = 4.dp) { index, face ->
                                    QueuedFollowUpCard(queued[index], index + 1, keys.size, emptyMap(), {}, {}, {}, flights = null, face = face)
                                }
                            },
                            aside = { DockedJumpButton(onClick = { clicks++ }, enabled = shown) },
                            asideShown = shown,
                            animate = { animate },
                        ) {
                            Box(Modifier.fillMaxWidth().height(96.dp).testTag(COMPOSER))
                        }
                    }
                }
            }
        }
        frames(30)
    }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun px(dp: Float): Float = with(density) { dp.dp.toPx() }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun button(): Rect? = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(DOCKED_JUMP_BUTTON_TAG), useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    private fun card(position: Int): Rect = compose.onNode(hasContentDescription("Queued follow-up $position of", substring = true)).fetchSemanticsNode().boundsInRoot

    /** The drawn end edges of whatever strips stand in the row: the queued cards and the goal. */
    private fun stripEnds(withGoal: Boolean, cards: Int): List<Float> =
        (1..cards).map { card(it).right } + if (withGoal) listOf(bounds("goal-strip").right) else emptyList()

    /** Where the strips' end edge stands with no button: the composer's end edge less the docked inset. */
    private val restingEnd: Float get() = bounds(COMPOSER).right - px(DockInset)

    private fun assertDocked(withGoal: Boolean, cards: Int) {
        val composer = bounds(COMPOSER)
        val jump = checkNotNull(button()) { "no jump button" }
        assertThat(jump.width).isWithin(1f).of(px(DockedRowHeight.value))
        assertThat(jump.height).isWithin(1f).of(px(DockedRowHeight.value))
        // Flush with the strips' resting end edge: the same inset from the composer's end.
        assertThat(jump.right).isWithin(1f).of(restingEnd)
        // As far over the composer as the lowest strip.
        assertThat(composer.top - jump.bottom).isWithin(1f).of(px(DockGap.value))
        stripEnds(withGoal, cards).forEach { end ->
            assertWithMessage("strip end against the button").that(jump.left - end).isWithin(1f).of(px(DockGap.value))
        }
        // Oldest first: the last card is the lowest, the one the button stands beside.
        if (cards > 0) assertThat(card(cards).bottom).isWithin(1f).of(jump.bottom)
    }

    /**
     * Whenever the button is drawn — grown past the share it fades in from, which it is only while it shows — the card
     * beside it has already made its room: the button is never drawn over a card, coming or going.
     */
    private fun assertNotOverCard() {
        val jump = button() ?: return
        if (jump.width <= px(DockedRowHeight.value) * AsideEnterScale + 0.5f) return
        assertWithMessage("the card's end edge against the button drawn beside it").that(card(1).right).isAtMost(jump.left + 0.5f)
    }

    @Test
    fun `with nothing docked, it stands where the lowest strip would, the dock no taller, and takes the tap`() {
        dock(withGoal = false, cards = 0)
        val dockBefore = bounds(DOCK)
        val transcriptBefore = bounds(TRANSCRIPT)
        assertThat(button()).isNull()
        shown = true
        frames(60)
        assertDocked(withGoal = false, cards = 0)
        assertThat(bounds(DOCK)).isEqualTo(dockBefore)
        assertThat(bounds(TRANSCRIPT)).isEqualTo(transcriptBefore)
        compose.onNode(hasContentDescription("Scroll to latest")).performClick()
        compose.waitForIdle()
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `beside a queued card, the card gives up its end edge to it, the gap the same as to the composer`() {
        dock(withGoal = false, cards = 1)
        assertThat(card(1).right).isWithin(1f).of(restingEnd)
        shown = true
        frames(60)
        assertDocked(withGoal = false, cards = 1)
        compose.onNode(hasContentDescription("Scroll to latest")).performClick()
        compose.waitForIdle()
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `beside the queue and the goal, every strip makes room, and all take their edge back as it goes`() {
        dock(withGoal = true, cards = 2)
        val before = stripEnds(withGoal = true, cards = 2)
        before.forEach { assertThat(it).isWithin(1f).of(restingEnd) }
        val dockBefore = bounds(DOCK)
        shown = true
        frames(60)
        assertDocked(withGoal = true, cards = 2)
        assertThat(bounds(DOCK).height).isWithin(1f).of(dockBefore.height)
        shown = false
        frames(60)
        assertThat(button()).isNull()
        stripEnds(withGoal = true, cards = 2).forEach { assertThat(it).isWithin(1f).of(restingEnd) }
    }

    @Test
    fun `the strips' edge glides on the button's spring, frame by frame, with no jump and no overshoot`() {
        dock(withGoal = true, cards = 1)
        shown = true
        val ends = mutableListOf(card(1).right)
        repeat(36) {
            frames(1)
            ends += card(1).right
            assertNotOverCard()
        }
        val travel = ends.first() - ends.last()
        assertThat(travel).isWithin(1.5f).of(px(DockedRowHeight.value + DockGap.value))
        // Always on its way in, never past where it ends: a critically damped spring.
        ends.zipWithNext { a, b -> assertThat(b).isAtMost(a + 0.5f) }
        assertThat(ends.min()).isAtLeast(ends.last() - 0.5f)
        // Spread over frames: no single frame takes more than a third of the way.
        ends.zipWithNext { a, b -> assertWithMessage("ends by frame: $ends").that(a - b).isLessThan(travel / 3f) }
        assertWithMessage("ends by frame: $ends").that(ends.count { it < ends.first() - 1f && it > ends.last() + 1f }).isAtLeast(6)
        // The goal strip goes with the card, frame for frame.
        assertThat(bounds("goal-strip").right).isWithin(1f).of(card(1).right)

        shown = false
        frames(1)
        // On its way out it takes no tap and is not heard, but is still drawn while it fades.
        assertThat(button()).isNotNull()
        compose.onNode(hasContentDescription("Scroll to latest")).assertDoesNotExist()
        repeat(60) {
            frames(1)
            assertNotOverCard()
        }
        assertThat(button()).isNull()
        assertThat(card(1).right).isWithin(1f).of(restingEnd)
    }

    @Test
    fun `under reduced motion the button and the strips' edge snap`() {
        dock(withGoal = true, cards = 2, animate = false)
        shown = true
        frames(2)
        assertDocked(withGoal = true, cards = 2)
        shown = false
        frames(2)
        assertThat(button()).isNull()
        stripEnds(withGoal = true, cards = 2).forEach { assertThat(it).isWithin(1f).of(restingEnd) }
    }

    @Test
    fun `beside the goal alone it stands on the goal's foot, over the composer`() {
        dock(withGoal = true, cards = 0)
        shown = true
        frames(60)
        assertDocked(withGoal = true, cards = 0)
        assertThat(bounds("goal-strip").bottom).isWithin(1f).of(checkNotNull(button()).bottom)
    }

    private companion object {
        const val TRANSCRIPT = "transcript"
        const val DOCK = "dock"
        const val COMPOSER = "composer"

        /** The composer's radius less a strip's corner: how far in from the composer's sides every docked strip stands. */
        const val DockInset = 12f

        /** The share of itself the button grows from as it fades in. */
        const val AsideEnterScale = 0.8f
    }
}
