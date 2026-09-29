package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.QueueDeliveries
import com.cursorforandroid.ui.components.QueueFlights
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

/**
 * A queued message the run takes, handed from the card to the transcript one 16 ms frame at a time on a held clock:
 * the chat's foot as the conversation screen lays it out — the transcript's bottom-anchored list over the dock, the
 * device's queue card on the dock — with the screen's [QueueHandover] between the two publications the handover is
 * read from: the repository's queue, which drops the row once the server has the message, and the transcript's
 * presented frame, whose placement names the rows its bubbles were sent from ([QueuePlacement.filedQueueIds]). Each
 * publication is made at a frame the test picks, in either order, so the frames are the same on every run.
 *
 * In every frame exactly one of the two shows the message, and the frame the card is gone is the frame the bubble is
 * composed and laid out: the anchor bubble above them moves in that one frame and no other. Without the handover, the
 * same publications move it twice. With `QUEUE_HANDOVER_RUNS` set, each case runs that many times; with
 * `QUEUE_HANDOVER_FILM_DIR`, each frame of the first run is written there as a PNG.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueHandoverFrameTest(private val run: Int) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "run {0}")
        fun runs(): List<Array<Any>> = (1..(System.getenv("QUEUE_HANDOVER_RUNS")?.toIntOrNull() ?: 1)).map { arrayOf(it) }

        private const val Anchor = "Profile the cold start and tell me where the time goes."
        private const val Delivered = "Also check the release build, not just debug"
        private const val Behind = "Then write up what changed for the release notes"
        private const val Transcript = "handover-transcript"
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val motion = SendMotion(animatorsEnabled = { true })
    private val flights = QueueFlights()
    private val film = System.getenv("QUEUE_HANDOVER_FILM_DIR")?.takeIf { run == 1 }?.let(::File)

    /** The repository's queue as last published: the device's rows, the head's send out. */
    private var repoQueue by mutableStateOf(listOf(QueuedFollowUp("q-1", Delivered, queuedAtMillis = 0L, isSending = true), QueuedFollowUp("q-2", Behind, queuedAtMillis = 0L)))

    /** The transcript as the presenter last published it, with the placement it was decided with. */
    private var presented by mutableStateOf(
        Frame(
            // Enough turns to overflow the list, which then keeps its newest row on the dock, as a chat does.
            (0 until 12).map { UserMessage("u-e$it", "Earlier turn $it: set up a baseline trace on the Pixel 7 profile and note where the time goes.") } +
                UserMessage("u-1", Anchor),
            QueuePlacement.NONE,
        ),
    )

    /** The repository's placement now: ahead of [presented], as the chat's own state is ahead of its presentation. */
    private var filedNow: Set<String> = emptySet()

    private var gated = true

    private class Frame(val items: List<TimelineItem>, val placement: QueuePlacement)

    @Before
    fun setUp() {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                    SendMotionHost(motion) {
                        Column(Modifier.fillMaxWidth().height(720.dp).background(CursorTheme.colors.canvas)) {
                            val frame = presented
                            val handover = remember { QueueHandover() }
                            val device = repoQueue
                            // As ConversationScreen stands the device's cards; ungated, as it did before.
                            val queue = if (gated) remember(device, frame) { handover.standing(device, frame.placement.filedQueueIds) { filedNow } } else device
                            SideEffect { handover.composed(queue) }
                            val listState = rememberLazyListState()
                            // As the screen takes a following list back to its newest row, which the keyed anchoring leaves past the edge.
                            LaunchedEffect(frame.items.size, frame.items.lastOrNull()?.id) { listState.requestScrollToItem(0) }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                LazyColumn(
                                    state = listState,
                                    reverseLayout = true,
                                    userScrollEnabled = false,
                                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag(Transcript),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(frame.items.asReversed(), key = { it.id }) { TimelineItemView(it) }
                                }
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                QueueDeliveries(
                                    flights = flights,
                                    rows = LinkedHashMap<String, String>().apply { queue.forEach { put(it.id, it.previewText) } },
                                    transcript = remember(frame) { frame.items.mapTo(HashSet()) { it.id } },
                                    scrolledAway = { false },
                                )
                                if (queue.isNotEmpty()) {
                                    QueueStack(
                                        keys = queue.map { "device:${it.id}" },
                                        stacked = true,
                                        onStackedChange = {},
                                        modifier = Modifier.padding(bottom = 4.dp),
                                        animate = { true },
                                        delivered = { key -> flights.delivered(key.substringAfter(':')) },
                                    ) { index, face ->
                                        QueuedFollowUpCard(queue[index], index + 1, queue.size, emptyMap(), {}, {}, {}, flights, face)
                                    }
                                }
                                // The composer's box, standing still under the card.
                                Box(Modifier.fillMaxWidth().height(96.dp))
                            }
                        }
                    }
                }
            }
        }
        repeat(40) { step() }
    }

    private fun step() {
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
    }

    /** One frame as drawn: whether the card and the bubble show the delivered message, and where the anchor bubble stands. */
    private data class Shot(val card: Boolean, val bubble: Boolean, val anchorTop: Float, val stackHeight: Float)

    private fun bounds(text: String, under: String): Rect? =
        compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(under)), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow

    private fun shot(): Shot {
        val list = compose.onAllNodes(hasTestTag(Transcript), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInWindow
        val bubble = bounds(Delivered, Transcript)?.takeIf { it.height > 0f && it.top >= list.top && it.bottom <= list.bottom }
        val stack = compose.onAllNodes(hasTestTag(QueueStackTag), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.boundsInWindow
        return Shot(
            card = bounds(Delivered, QueueStackTag) != null,
            bubble = bubble != null,
            anchorTop = checkNotNull(bounds(Anchor, Transcript)) { "the anchor bubble is off the list" }.top,
            stackHeight = stack?.height ?: 0f,
        )
    }

    /**
     * Frames on from the settled chat, [changes] made on the UI thread at the frames they are keyed by: each frame's
     * shot, taken after its changes have been composed, laid out and drawn.
     */
    private fun filmHandover(frames: Int = 40, changes: Map<Int, () -> Unit>): List<Shot> {
        show()
        val out = mutableListOf(shot())
        film?.let { File(it, "f_%04d.png".format(0)).also(::draw) }
        for (f in 1..frames) {
            changes[f]?.let { compose.runOnUiThread(it) }
            step()
            out += shot()
            film?.let { File(it, "f_%04d.png".format(f)).also(::draw) }
        }
        println("QueueHandoverFrame run $run: " + out.withIndex().joinToString(" ") { (i, s) -> "$i:${if (s.card) "C" else "-"}${if (s.bubble) "B" else "-"}@${s.anchorTop.toInt()}/${s.stackHeight.toInt()}" })
        return out
    }

    private fun draw(file: File) {
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The repository drops the row (the server took the message); its placement now files it. */
    private val queueDrops: () -> Unit = {
        filedNow = setOf("q-1")
        repoQueue = repoQueue.filterNot { it.id == "q-1" }
    }

    /** The presenter publishes the frame that files the bubble, the row it was sent from named in its placement. */
    private val bubbleFiled: () -> Unit = {
        filedNow = setOf("q-1")
        presented = Frame(presented.items + UserMessage("local-1", Delivered), QueuePlacement(filedQueueIds = setOf("q-1")))
    }

    /** Exactly one of card and bubble in every frame; the card gone in the frame the bubble lands; the anchor moving in that frame alone. */
    private fun assertOneMovement(shots: List<Shot>) {
        val log = shots.withIndex().joinToString(" ") { (i, s) -> "$i:${if (s.card) "C" else "-"}${if (s.bubble) "B" else "-"}@${s.anchorTop.toInt()}" }
        shots.forEachIndexed { i, s ->
            assertWithMessage("frame $i shows the message ${if (s.card) "on the card and as the bubble" else "nowhere"}: $log").that(s.card).isNotEqualTo(s.bubble)
        }
        val cardGone = shots.indexOfFirst { !it.card }
        val bubbleIn = shots.indexOfFirst { it.bubble }
        assertWithMessage("the card never left: $log").that(cardGone).isGreaterThan(0)
        assertWithMessage("the frame the card goes is the frame the bubble lands: $log").that(cardGone).isEqualTo(bubbleIn)
        val moves = shots.zipWithNext().withIndex().filter { (_, p) -> kotlin.math.abs(p.second.anchorTop - p.first.anchorTop) > 0.5f }.map { it.index + 1 }
        assertWithMessage("the anchor moved in frames $moves: $log").that(moves).containsExactly(bubbleIn)
        val deck = shots.zipWithNext().withIndex().filter { (_, p) -> kotlin.math.abs(p.second.stackHeight - p.first.stackHeight) > 0.5f }.map { it.index + 1 }
        assertWithMessage("the card's height changed in frames $deck: $log").that(deck).containsExactly(bubbleIn)
    }

    @Test
    fun `the queue dropping the row first, the card stands until the frame that files the bubble`() {
        assertOneMovement(filmHandover(changes = mapOf(3 to queueDrops, 9 to bubbleFiled)))
    }

    @Test
    fun `the bubble filed first, the card goes in that frame and the queue's later drop moves nothing`() {
        assertOneMovement(filmHandover(changes = mapOf(3 to bubbleFiled, 9 to queueDrops)))
    }

    @Test
    fun `both in one frame, one movement`() {
        assertOneMovement(filmHandover(changes = mapOf(3 to { queueDrops(); bubbleFiled() })))
    }

    @Test
    fun `the bubble filed the frame after the queue's drop, still one movement`() {
        assertOneMovement(filmHandover(changes = mapOf(3 to queueDrops, 4 to bubbleFiled)))
    }

    @Test
    fun `without the handover the same publications move the transcript twice`() {
        gated = false
        val shots = filmHandover(changes = mapOf(3 to queueDrops, 9 to bubbleFiled))
        val moves = shots.zipWithNext().count { (a, b) -> kotlin.math.abs(b.anchorTop - a.anchorTop) > 0.5f }
        assertThat(moves).isEqualTo(2)
        assertThat(shots.any { !it.card && !it.bubble }).isTrue()
    }
}
