package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PendingAttachment
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendLanding
import com.cursorforandroid.ui.components.SendMotion
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * A long queue as a deck over the composer, frame by frame on a held clock: three or more stack — the next message in
 * front, whole, on the box; the next two peeking out above it, narrower, their faces and glyphs hidden — and the line
 * over the deck opens it into the list and stacks it back, each card on its own spring. A delivery lifts off the
 * front card and the next comes forward; a send joins the back, its copy sinking into the deck; a touch on a peeking
 * card opens the deck rather than reaching what it hides. Nothing replays when the queue is composed again, and with
 * animations off every card simply stands where it belongs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueStackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scene = QueueMotionScene(compose)
    private val motion = SendMotion(animatorsEnabled = { true })

    @Before
    fun setUp() {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    private val dp get() = compose.activity.resources.displayMetrics.density

    private fun queued(vararg texts: String) = texts.mapIndexed { i, text -> QueuedFollowUp("q-${i + 1}", text, queuedAtMillis = 0L) }

    private fun LayoutCoordinates.drawn() = Rect(localToWindow(Offset.Zero), localToWindow(Offset(size.width.toFloat(), size.height.toFloat())))

    /** Card [id]'s surface where it is drawn now, scale and all. */
    private fun box(id: String): Rect = checkNotNull(scene.flights.anchor(id).surface) { "no card for $id" }.drawn()

    private fun composer(): Rect = checkNotNull(scene.anchor.surface).drawn()

    private fun handle(description: String) = compose.onNodeWithContentDescription(description, useUnmergedTree = true)

    private fun stackState() = compose.onNodeWithTag(QueueStackTag).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }

    /** A tap at [at] in the window. */
    private fun tap(at: Offset) {
        val frame = compose.onNodeWithTag(QueueMotionScene.Frame).fetchSemanticsNode().boundsInWindow
        compose.onNodeWithTag(QueueMotionScene.Frame).performTouchInput { click(at - frame.topLeft) }
        scene.frame()
    }

    private fun tapHandle() {
        val node = compose.onNodeWithTag(QueueStackHandleTag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        tap(node.center)
    }

    private fun between(a: Float, b: Float) = Range.open(minOf(a, b), maxOf(a, b))

    private fun assertNear(actual: Float, expected: Float, slack: Float = 1.5f) =
        assertThat(actual).isIn(Range.closed(expected - slack, expected + slack))

    /** [cover] stands from [top] down over the [front] card: the slots of the cards in front, the dock's inset beside them. */
    private fun assertCovers(cover: Rect?, top: Float, front: Rect) {
        checkNotNull(cover) { "nothing covers the copy" }
        assertNear(cover.top, top)
        assertThat(cover.bottom).isEqualTo(front.bottom)
        assertThat(cover.left).isAtMost(front.left)
        assertThat(cover.right).isAtLeast(front.right)
    }

    /** The deck at rest: [ids] in order, the front on the composer, the next two each a step higher and narrower. */
    private fun assertDeck(vararg ids: String) {
        val front = box(ids[0])
        assertThat(composer().top - front.bottom).isIn(Range.closed(0f, 8 * dp))
        var above = front
        for (id in ids.drop(1).take(2)) {
            val card = box(id)
            assertNear(above.top - card.top, 6 * dp)
            assertThat(card.width).isLessThan(above.width)
            assertNear(card.center.x, front.center.x)
            above = card
        }
    }

    /** The list at rest: [ids] oldest first, a dock's gap between each, every card its full width. */
    private fun assertList(vararg ids: String) {
        for (i in 0 until ids.size - 1) {
            val upper = box(ids[i])
            val lower = box(ids[i + 1])
            assertNear(lower.top - upper.bottom, 4 * dp)
            assertNear(upper.width, lower.width)
        }
    }

    @Test
    fun `three queued stack into a deck, the next in front and two peeking behind it with their faces hidden`() {
        scene.queue += queued("Run the migration first", "Then reseed the fixtures", "Then rerun the flaky suite")
        scene.show(motion)
        assertDeck("q-1", "q-2", "q-3")
        assertThat(stackState()).isEqualTo("Stacked")
        handle("Show all 3 queued follow-ups").assertExists()
        // Only the front card is read out, glyphs and all; the ones behind are an edge, nothing more.
        compose.onNodeWithContentDescription("Queued follow-up 1 of 3", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithContentDescription("Queued follow-up 2 of 3").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Remove queued follow-up").assertCountEquals(1)
    }

    @Test
    fun `two queued stay a list, with no line over them`() {
        scene.queue += queued("Run the migration first", "Then reseed the fixtures")
        scene.show(motion)
        assertList("q-1", "q-2")
        compose.onAllNodesWithTag(QueueStackHandleTag, useUnmergedTree = true).assertCountEquals(0)
        assertThat(stackState()).isNull()
    }

    @Test
    fun `the line over the deck opens it into the list on a spring, and stacks it back`() {
        scene.queue += queued("Run the migration first", "Then reseed the fixtures", "Then rerun the flaky suite")
        scene.show(motion)
        val stacked = box("q-3")
        tapHandle()
        assertThat(scene.stacked).isFalse()
        scene.frames(48)
        val moving = box("q-3")
        scene.frames(900)
        val open = box("q-3")
        // Part of the way up, and no wider than the deck left it: a spring from where it stood, not a jump.
        assertThat(moving.top).isIn(between(open.top, stacked.top))
        assertList("q-1", "q-2", "q-3")
        assertThat(box("q-1").bottom).isAtMost(box("q-3").top)
        assertThat(stackState()).isEqualTo("Expanded")
        handle("Stack queued follow-ups").assertExists()
        compose.onAllNodesWithContentDescription("Remove queued follow-up").assertCountEquals(3)

        tapHandle()
        assertThat(scene.stacked).isTrue()
        scene.frames(48)
        assertThat(box("q-3").top).isIn(between(open.top, stacked.top))
        scene.frames(900)
        assertDeck("q-1", "q-2", "q-3")
        assertNear(box("q-3").top, stacked.top)
    }

    @Test
    fun `a tap on the front card's body opens the deck, and its own glyphs work where they are`() {
        scene.queue += queued("One", "Two", "Three", "Four")
        scene.show(motion)
        compose.onNodeWithContentDescription("Remove queued follow-up").fetchSemanticsNode().boundsInWindow.let { tap(it.center) }
        assertThat(scene.queue.map { it.id }).containsExactly("q-2", "q-3", "q-4").inOrder()
        assertThat(scene.stacked).isTrue()
        scene.frames(900)
        assertDeck("q-2", "q-3", "q-4")

        tap(Offset(box("q-2").left + 24 * dp, box("q-2").center.y))
        assertThat(scene.stacked).isFalse()
        scene.frames(900)
        assertList("q-2", "q-3", "q-4")
    }

    @Test
    fun `a tap on a peeking card opens the deck and never reaches the glyphs it hides`() {
        scene.queue += queued("One", "Two", "Three")
        scene.show(motion)
        val trash = compose.onNodeWithContentDescription("Remove queued follow-up").fetchSemanticsNode().boundsInWindow
        val back = box("q-3")
        tap(Offset(trash.center.x, back.top + 2 * dp))
        assertThat(scene.queue).hasSize(3)
        assertThat(scene.stacked).isFalse()
        scene.frames(900)
        assertList("q-1", "q-2", "q-3")
    }

    @Test
    fun `a delivery lifts off the front card and the next comes forward on its spring`() {
        scene.queue += queued("One", "Two", "Three", "Four")
        scene.show(motion)
        val front = box("q-1")
        val second = box("q-2")
        val row = scene.flights.anchor("q-1")
        scene.deliver("q-1", bubble = "u-2")
        val flight = checkNotNull(motion.flight)
        assertThat(flight.takeoff.anchor).isSameInstanceAs(row)
        assertThat(flight.takeoff.composer).isEqualTo(front)
        scene.frames(48)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        assertThat(flight.targetId).isEqualTo("u-2")
        // On its way down to the front, and widening to it.
        val coming = box("q-2")
        assertThat(coming.top).isGreaterThan(second.top)
        assertThat(coming.width).isIn(Range.open(second.width, front.width))
        scene.frames(SendMotion.FlightMillis.toLong() + 900)
        assertThat(motion.flight).isNull()
        assertDeck("q-2", "q-3", "q-4")
        assertNear(box("q-2").bottom, front.bottom)
        assertNear(box("q-2").width, front.width)
        handle("Show all 3 queued follow-ups").assertExists()
    }

    @Test
    fun `a send into the deck joins its back, the copy sinking into a card whose face is hidden`() {
        scene.queue += queued("One", "Two", "Three")
        scene.composerText = "Four"
        scene.show(motion)
        val flight = checkNotNull(scene.sendQueued(motion, "q-4"))
        scene.frames(48)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        assertThat(flight.targetId).isEqualTo("q-4")
        assertThat(flight.landedOn).isEqualTo(SendLanding.Queue)
        assertThat(checkNotNull(flight.target).look.fade).isEqualTo(0f)
        // Behind the last card that peeks, where it waits unseen: the front and the peeks stand where they stood.
        assertThat(checkNotNull(flight.target?.surface).drawn()).isEqualTo(box("q-3"))
        // It goes behind the cards in front of it, as into a deck, rather than over the next message's words.
        assertCovers(checkNotNull(flight.target).look.cover?.invoke(), box("q-3").top, box("q-1"))
        assertDeck("q-1", "q-2", "q-3")
        handle("Show all 4 queued follow-ups").assertExists()
        scene.frames(SendMotion.FlightMillis.toLong() + 200)
        assertThat(motion.flight).isNull()
    }

    @Test
    fun `the third send stacks the list, the copy landing on the card that peeks at the back`() {
        scene.queue += queued("One", "Two")
        scene.composerText = "Three"
        scene.show(motion)
        val flight = checkNotNull(scene.sendQueued(motion, "q-3"))
        assertThat(stackState()).isEqualTo("Stacked")
        scene.frames(48)
        assertThat(flight.targetId).isEqualTo("q-3")
        assertThat(checkNotNull(flight.target).look.fade).isEqualTo(0f)
        scene.frames(900)
        assertCovers(checkNotNull(flight.target).look.cover?.invoke(), box("q-2").top, box("q-1"))
        scene.frames(SendMotion.FlightMillis.toLong())
        assertThat(motion.flight).isNull()
        assertDeck("q-1", "q-2", "q-3")
    }

    @Test
    fun `on the account's deck, a delivery carries the front card's tiles into the bubble`() {
        scene.onAccount = true
        val spec = listOf(PendingAttachment("Q3-header-spec.pdf", "application/pdf"))
        scene.account += PendingFollowup("a-1", "Match the header to these", files = spec, imageCount = 2)
        scene.account += PendingFollowup("a-2", "Then rerun the flaky suite")
        scene.account += PendingFollowup("a-3", "Then write it up")
        scene.show(motion)
        assertDeck("a-1", "a-2", "a-3")
        compose.onNodeWithContentDescription("Queued on your account, 1 of 3", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithContentDescription("Queued on your account, 2 of 3").assertCountEquals(0)
        val front = box("a-1")
        scene.deliver("a-1", bubble = "u-2")
        val flight = checkNotNull(motion.flight)
        assertThat(flight.takeoff.composer).isEqualTo(front)
        assertThat(flight.takeoff.attachments).hasSize(3)
        for (tile in flight.takeoff.attachments) assertThat(front.contains(tile.rect.center)).isTrue()
        scene.frames(SendMotion.FlightMillis.toLong() + 900)
        assertThat(motion.flight).isNull()
        assertList("a-2", "a-3")
    }

    @Test
    fun `the device's and the account's rows stack as one deck, the device's first`() {
        scene.queue += queued("On this device")
        scene.account += PendingFollowup("a-1", "On the account")
        scene.account += PendingFollowup("a-2", "On the account, too")
        scene.show(motion)
        assertDeck("q-1", "a-1", "a-2")
        handle("Show all 3 queued follow-ups").assertExists()
    }

    /**
     * The up arrow drawn on the front card [id]: the steer. The peeking cards behind it keep theirs, scaled down and
     * hidden under it, so the front's is the one drawn at full size.
     */
    private fun steerOn(id: String): Rect {
        val card = box(id)
        val arrows = compose.onAllNodesWithContentDescription(QueueGlyphs.STEER, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInWindow }.filter { card.contains(it.center) }
        assertThat(arrows).isNotEmpty()
        val front = arrows.maxBy { it.width }
        assertThat(arrows.count { it.width > front.width - 1f }).isEqualTo(1)
        return front
    }

    @Test
    fun `the deck's front card has no target - its up arrow steers into the running turn and nothing is stopped`() {
        scene.queue += queued("One", "Two", "Three")
        scene.show(motion)
        assertDeck("q-1", "q-2", "q-3")
        compose.onAllNodesWithContentDescription("Steer now", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(QueueGlyphs.SEND, useUnmergedTree = true).assertCountEquals(0)
        tap(steerOn("q-1").center)
        assertThat(scene.steered).containsExactly("q-1")
        // A steer only asks: the deck stays stacked and the queue stands until the account files the message.
        assertThat(scene.stacked).isTrue()
        assertThat(scene.queue.map { it.id }).containsExactly("q-1", "q-2", "q-3").inOrder()
    }

    @Test
    fun `on the account's deck too, the front card's up arrow is the steer and there is no target`() {
        scene.onAccount = true
        scene.account += PendingFollowup("a-1", "Match the header")
        scene.account += PendingFollowup("a-2", "Then rerun the flaky suite")
        scene.account += PendingFollowup("a-3", "Then write it up")
        scene.show(motion)
        assertDeck("a-1", "a-2", "a-3")
        compose.onAllNodesWithContentDescription("Steer now", useUnmergedTree = true).assertCountEquals(0)
        tap(steerOn("a-1").center)
        assertThat(scene.steered).containsExactly("a-1")
        assertThat(scene.stacked).isTrue()
    }

    @Test
    fun `with animations off the deck opens and stacks at once`() {
        scene.stackAnimates = false
        scene.queue += queued("One", "Two", "Three")
        scene.show(motion)
        tapHandle()
        assertList("q-1", "q-2", "q-3")
        tapHandle()
        assertDeck("q-1", "q-2", "q-3")
    }

    @Test
    fun `nothing replays when the queue is composed again or the transcript grows`() {
        scene.queue += queued("One", "Two", "Three")
        scene.show(motion)
        val deck = listOf("q-1", "q-2", "q-3").map(::box)
        compose.runOnUiThread { scene.messages += UserMessage("u-2", "Another turn") }
        scene.frame()
        assertThat(listOf("q-1", "q-2", "q-3").map(::box).map { it.size }).isEqualTo(deck.map { it.size })
        compose.runOnUiThread { scene.generation++ }
        scene.frame()
        val again = listOf("q-1", "q-2", "q-3").map(::box)
        assertThat(again.map { it.size }).isEqualTo(deck.map { it.size })
        scene.frames(400)
        assertThat(listOf("q-1", "q-2", "q-3").map(::box)).isEqualTo(again)

        tapHandle()
        scene.frames(900)
        val list = listOf("q-1", "q-2", "q-3").map(::box)
        compose.runOnUiThread { scene.generation++ }
        scene.frame()
        assertThat(listOf("q-1", "q-2", "q-3").map(::box)).isEqualTo(list)
        assertThat(abs(list[0].width - list[2].width)).isLessThan(1f)
    }

    private val demoDir = System.getenv("QUEUE_DEMO_DIR")?.let(::File)
    private var demoFrame = 0

    /** On by [millis], a frame at a time, each written to [demoDir] when it is set. */
    private fun film(millis: Long) {
        var left = millis
        while (left > 0) {
            demoDir?.let { scene.drawTo(File(it, "stack_%04d.png".format(demoFrame++))) }
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            left -= 16
        }
    }

    private fun sendAndFilm(id: String, text: String) {
        compose.runOnUiThread { scene.composerText = text }
        film(500)
        checkNotNull(scene.sendQueued(motion, id))
        film(SendMotion.FlightMillis + 500L)
    }

    /**
     * The demo: four follow-ups sent while the agent works — the first with two pictures and a spec — the third
     * stacking the list into a deck and the fourth sinking into its back; the deck opened into the list and stacked
     * again; then the run taking the front card, pictures and all, into the transcript as the next comes forward.
     */
    @Test
    fun stackDemo() {
        scene.show(motion)
        film(400)
        var sent = emptyList<com.cursorforandroid.domain.MessageAttachment>()
        compose.runOnUiThread { sent = scene.attach() }
        sendAndFilm("q-1", "Match the header to these, and follow the spec")
        sendAndFilm("q-2", "Then reseed the fixtures")
        sendAndFilm("q-3", "Then rerun the flaky suite on the emulator matrix")
        assertThat(scene.stacked).isTrue()
        sendAndFilm("q-4", "Then write up what changed for the release notes")
        film(400)
        tapHandle()
        film(1_400)
        assertThat(scene.stacked).isFalse()
        tapHandle()
        film(1_200)
        scene.deliver("q-1", bubble = "u-2", attachments = sent)
        film(SendMotion.FlightMillis + 1_000L)
        scene.deliver("q-2", bubble = "u-3")
        film(SendMotion.FlightMillis + 1_200L)
        assertThat(motion.flights).isEmpty()
        assertThat(scene.messages.map { it.id }).containsExactly("u-1", "u-2", "u-3").inOrder()
        assertList("q-3", "q-4")
    }

    private val steerDir = System.getenv("STEER_DEMO_DIR")?.let(::File)

    private fun filmSteer(millis: Long) {
        var left = millis
        while (left > 0) {
            steerDir?.let { scene.drawTo(File(it, "steer_%04d.png".format(demoFrame++))) }
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            left -= 16
        }
    }

    /**
     * The steer demo: three messages queued on the account while the agent works, stacked; the front card's up arrow
     * tapped, and the account filing it into the running turn — the card lifting off into the transcript and the next
     * coming forward — while the composer still offers Stop, the run going on.
     */
    @Test
    fun steerDemo() {
        scene.onAccount = true
        scene.account += PendingFollowup("a-1", "Also check the release build, not just debug")
        scene.account += PendingFollowup("a-2", "Then rerun the flaky suite")
        scene.account += PendingFollowup("a-3", "Then write up what changed")
        scene.show(motion)
        filmSteer(900)
        tap(steerOn("a-1").center)
        assertThat(scene.steered).containsExactly("a-1")
        filmSteer(400)
        scene.deliver("a-1", bubble = "u-2")
        filmSteer(SendMotion.FlightMillis + 1_400L)
        assertThat(motion.flights).isEmpty()
        assertThat(scene.messages.map { it.id }).containsExactly("u-1", "u-2").inOrder()
        compose.onNodeWithContentDescription("Stop", useUnmergedTree = true).assertExists()
        assertList("a-2", "a-3")
    }
}
