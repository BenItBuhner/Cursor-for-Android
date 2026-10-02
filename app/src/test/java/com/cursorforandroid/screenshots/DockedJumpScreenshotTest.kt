package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.DockedJumpButton
import com.cursorforandroid.ui.components.composerDockPadding
import com.cursorforandroid.ui.conversation.CatchUpIndicator
import com.cursorforandroid.ui.conversation.CatchUpPull
import com.cursorforandroid.ui.conversation.CatchUpStatus
import com.cursorforandroid.ui.conversation.GoalDock
import com.cursorforandroid.ui.conversation.GoalStrip
import com.cursorforandroid.ui.conversation.QueueStack
import com.cursorforandroid.ui.conversation.QueuedFollowUpCard
import com.cursorforandroid.ui.conversation.TimelineItemView
import com.cursorforandroid.ui.conversation.catchUpLift
import com.cursorforandroid.ui.conversation.catchUpPadding
import com.cursorforandroid.ui.conversation.catchUpPullFor
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.ui.test.junit4.createAndroidComposeRule

/**
 * The bottom of a chat scrolled off its newest message, the jump button docked at the end of the strips' row over the
 * composer: alone, where the lowest strip would stand; beside a queued follow-up; beside the queue under the goal — on
 * a phone and a tablet, dark and light (`1050`–`1061`). Then the button on its way: the strips making its room before
 * it is drawn (`1062`), and it fading and growing into the room they made (`1063`). And the pull to catch up answered,
 * its indicator caught fading out over the transcript as the gap closes, on a phone dark and light and a tablet
 * (`1064`–`1066`), and while the pull is out a row the answer brought in drawn under the indicator, past where the
 * list's edge used to cut it flat (`1067`).
 *
 * The clock is held, so the frames part of the way are the same frame on every run. Written to `screenshots/` and
 * compared pixel for pixel in CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_DARK)
class DockedJumpScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val now = 1_800_000_000_000L

    @Before
    fun pinClock() {
        AppClock.nowMillis = { now }
    }

    @After
    fun unpinClock() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private val queued = listOf(
        QueuedFollowUp("q-1", "Then add a test for the light theme", queuedAtMillis = 1_000L),
        QueuedFollowUp("q-2", "And open a draft PR once it is green", queuedAtMillis = 2_000L),
    )
    private val goal = Goal("Ship the docked jump button and its motion", GoalStatus.ACTIVE, accruingSinceMillis = now - 252_000L, continuationCount = 2, source = Goal.Source.Account)

    private val transcript = listOf<TimelineItem>(
        UserMessage("u1", "Scan the order book for markets that closed in the last hour and flag any fee mismatches."),
        AssistantMessage("a1", "Scanned 42 markets that closed in the last hour. Two fee mismatches: **market 187** charged 2.1% against a 2.0% table, and **market 193** charged the maker fee to the taker. Both are in `reports/fees.md`."),
        UserMessage("u2", "Fix both, and add a check to the nightly job so a mismatch fails the run."),
        AssistantMessage("a2", "Fixed the fee table for market 187 and the side for market 193, and the nightly job now compares every closed market against the table, failing the run on the first mismatch with the market's id and both fees in the message."),
    )
    private val elsewhere = UserMessage("u-elsewhere", "From the desktop: check the fee table for market 200 too, then report back.")

    /** Three times over: more than a tablet's screen of it, so the list scrolls. */
    private val history: List<TimelineItem> = (1..3).flatMap { n ->
        transcript.map { item ->
            when (item) {
                is UserMessage -> item.copy(id = "${item.id}-$n")
                is AssistantMessage -> item.copy(id = "${item.id}-$n")
                else -> item
            }
        }
    }

    private var jumpShown by mutableStateOf(false)

    /** The transcript as the chat lays it out: bottom-anchored, newest last, each row the composer's width at most. */
    @Composable
    private fun Transcript(items: List<TimelineItem>, state: LazyListState, modifier: Modifier, contentPadding: PaddingValues) {
        LazyColumn(state = state, reverseLayout = true, horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier, contentPadding = contentPadding) {
            items(items.asReversed(), key = { it.id }) { item ->
                Box(Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 10.dp)) { TimelineItemView(item) }
            }
        }
    }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    // --- the jump button docked -----------------------------------------------------------------------------------

    /** The chat's bottom as the screen lays it out: the transcript over the dock, the strips and the button over the box. */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun dock(mode: ThemeMode, cards: Int, withGoal: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // Off the newest message, as the chat is whenever the button shows.
                            Transcript(
                                history,
                                rememberLazyListState(initialFirstVisibleItemIndex = 1, initialFirstVisibleItemScrollOffset = 120),
                                Modifier.fillMaxSize(),
                                TranscriptPadding,
                            )
                        }
                        Column(Modifier.fillMaxWidth().composerDockPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                            GoalDock(
                                open = false,
                                goal = if (withGoal) {
                                    { GoalStrip(goal, expanded = false, onExpandedChange = {}, clock = { now }, modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth).padding(bottom = 4.dp)) }
                                } else {
                                    null
                                },
                                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
                                cards = {
                                    val keys = queued.take(cards).map { it.id }
                                    QueueStack(keys = keys, stacked = false, onStackedChange = {}, modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth), gapBelow = 4.dp) { index, face ->
                                        QueuedFollowUpCard(queued[index], index + 1, keys.size, emptyMap(), {}, {}, {}, flights = null, face = face, steers = true)
                                    }
                                },
                                aside = { DockedJumpButton(onClick = {}, enabled = jumpShown) },
                                asideShown = jumpShown,
                            ) {
                                ComposerBox(
                                    value = "",
                                    onValueChange = {},
                                    placeholder = if (cards > 0) "Follow up (sends when the turn ends)…" else "Follow up…",
                                    onSend = {},
                                    isRunning = cards > 0,
                                    onStop = {},
                                    modelLabel = "Claude Fable 5.1",
                                    onModel = {},
                                    modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
                                )
                            }
                        }
                    }
                }
            }
        }
        frames(30)
        jumpShown = true
    }

    private fun docked(name: String, mode: ThemeMode, cards: Int, withGoal: Boolean) {
        dock(mode, cards, withGoal)
        frames(60)
        capture(name)
    }

    @Test fun alonePhoneDark() = docked("1050_jump_docked_alone_phone_dark", ThemeMode.Dark, cards = 0, withGoal = false)

    @Test @Config(qualifiers = PHONE_LIGHT)
    fun alonePhoneLight() = docked("1051_jump_docked_alone_phone_light", ThemeMode.Light, cards = 0, withGoal = false)

    @Test @Config(qualifiers = TABLET_DARK)
    fun aloneTabletDark() = docked("1052_jump_docked_alone_tablet_dark", ThemeMode.Dark, cards = 0, withGoal = false)

    @Test @Config(qualifiers = TABLET_LIGHT)
    fun aloneTabletLight() = docked("1053_jump_docked_alone_tablet_light", ThemeMode.Light, cards = 0, withGoal = false)

    @Test fun queuePhoneDark() = docked("1054_jump_docked_queue_phone_dark", ThemeMode.Dark, cards = 1, withGoal = false)

    @Test @Config(qualifiers = PHONE_LIGHT)
    fun queuePhoneLight() = docked("1055_jump_docked_queue_phone_light", ThemeMode.Light, cards = 1, withGoal = false)

    @Test @Config(qualifiers = TABLET_DARK)
    fun queueTabletDark() = docked("1056_jump_docked_queue_tablet_dark", ThemeMode.Dark, cards = 1, withGoal = false)

    @Test @Config(qualifiers = TABLET_LIGHT)
    fun queueTabletLight() = docked("1057_jump_docked_queue_tablet_light", ThemeMode.Light, cards = 1, withGoal = false)

    @Test fun queueAndGoalPhoneDark() = docked("1058_jump_docked_queue_goal_phone_dark", ThemeMode.Dark, cards = 2, withGoal = true)

    @Test @Config(qualifiers = PHONE_LIGHT)
    fun queueAndGoalPhoneLight() = docked("1059_jump_docked_queue_goal_phone_light", ThemeMode.Light, cards = 2, withGoal = true)

    @Test @Config(qualifiers = TABLET_DARK)
    fun queueAndGoalTabletDark() = docked("1060_jump_docked_queue_goal_tablet_dark", ThemeMode.Dark, cards = 2, withGoal = true)

    @Test @Config(qualifiers = TABLET_LIGHT)
    fun queueAndGoalTabletLight() = docked("1061_jump_docked_queue_goal_tablet_light", ThemeMode.Light, cards = 2, withGoal = true)

    @Test
    fun stripsMakingRoom() {
        dock(ThemeMode.Dark, cards = 2, withGoal = true)
        frames(5)
        capture("1062_jump_docking_strips_making_room_phone_dark")
    }

    @Test
    fun growingIntoTheRoom() {
        dock(ThemeMode.Dark, cards = 2, withGoal = true)
        frames(11)
        capture("1063_jump_docking_growing_into_the_room_phone_dark")
    }

    // --- the pull to catch up, answered ---------------------------------------------------------------------------

    private val rows = mutableStateListOf<TimelineItem>()
    private val status = MutableStateFlow<CatchUpStatus>(CatchUpStatus.Idle)
    private lateinit var pull: CatchUpPull

    /** The screen's arrangement: a following lazy transcript lifted by the pull, with its reach, the indicator over its edge. */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun pulled(mode: ThemeMode) {
        rows.clear()
        rows += history
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    val density = LocalDensity.current
                    pull = remember { catchUpPullFor(density) }
                    Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Transcript(
                                rows,
                                rememberLazyListState(),
                                Modifier.fillMaxSize().catchUpLift(pull, reach = TranscriptPadding),
                                catchUpPadding(pull, TranscriptPadding),
                            )
                            CatchUpIndicator(pull, status, onSettled = {}, modifier = Modifier.align(Alignment.BottomCenter), edgeGap = 12.dp)
                        }
                        Column(Modifier.fillMaxWidth().composerDockPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                            ComposerBox(
                                value = "",
                                onValueChange = {},
                                placeholder = "Follow up…",
                                onSend = {},
                                modelLabel = "Claude Fable 5.1",
                                onModel = {},
                                modifier = Modifier.widthIn(max = CursorDimens.composerMaxWidth),
                            )
                        }
                    }
                }
            }
        }
        frames(30)
        pull.stretch(pull.thresholdPx * 1.6f)
        frames(1)
        assertThat(pull.release()).isTrue()
        status.value = CatchUpStatus.Checking
        frames(60)
    }

    private fun answerFading(name: String, mode: ThemeMode) {
        pulled(mode)
        rows += elsewhere
        status.value = CatchUpStatus.Done(newMessages = 1, changed = true)
        frames(5)
        assertThat(pull.out).isTrue()
        capture(name)
    }

    @Test fun answerFadingPhoneDark() = answerFading("1064_catch_up_answer_fading_phone_dark", ThemeMode.Dark)

    @Test @Config(qualifiers = PHONE_LIGHT)
    fun answerFadingPhoneLight() = answerFading("1065_catch_up_answer_fading_phone_light", ThemeMode.Light)

    @Test @Config(qualifiers = TABLET_DARK)
    fun answerFadingTabletDark() = answerFading("1066_catch_up_answer_fading_tablet_dark", ThemeMode.Dark)

    @Test
    fun rowUnderTheGap() {
        pulled(ThemeMode.Dark)
        rows += elsewhere
        frames(2)
        capture("1067_catch_up_row_under_the_gap_phone_dark")
    }

    private companion object {
        val TranscriptPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)
    }
}

private const val PHONE_DARK = "w411dp-h640dp-night-420dpi"
private const val PHONE_LIGHT = "w411dp-h640dp-notnight-420dpi"
private const val TABLET_DARK = "w1000dp-h640dp-night-320dpi"
private const val TABLET_LIGHT = "w1000dp-h640dp-notnight-320dpi"
