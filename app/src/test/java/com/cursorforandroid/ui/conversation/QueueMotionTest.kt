package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.FlyingAttachmentTag
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendLanding
import com.cursorforandroid.ui.components.SendMotion
import androidx.compose.ui.layout.boundsInWindow
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

/**
 * The send's flight with the queue card at either end, frame by frame on a held clock. A message sent while the agent
 * runs lifts off the composer — text, pictures and files — and lands in its row on the card, the row undrawn until the
 * hand-over; a row the run takes lifts off the card and lands in its bubble. Only the row that left flies (not one
 * moved, nor one the reader removed); a row taken while a send is still landing on it flies on from where it stood;
 * with the reader scrolled off the newest turn the copy fades where it stood; with animations off nothing flies.
 *
 * With `QUEUE_DEMO_DIR` set, every frame of [demo] is written there as a PNG (the demo video's frames).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scene = QueueMotionScene(compose)
    private val motion = SendMotion(animatorsEnabled = { true })

    @Before
    fun setUp() {
        // Native graphics look java.nio's buffers up once, on whichever thread first needs them: made here, first.
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    private fun queued(id: String, text: String) = QueuedFollowUp(id, text, queuedAtMillis = 0L)

    private fun rowBounds(position: Int, count: Int) =
        compose.onNodeWithContentDescription("Queued follow-up $position of $count", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow

    private fun landed(flight: SendFlight) = checkNotNull(flight.target?.surface).boundsInWindow()

    @Test
    fun `a message sent while the agent runs flies into its row on the card, undrawn until the hand-over`() {
        scene.queue += queued("q-1", "Run the migration first")
        scene.composerText = "Then reseed the fixtures"
        scene.show(motion)
        val flight = checkNotNull(scene.sendQueued(motion, "q-2"))
        assertThat(flight.landing).isEqualTo(SendLanding.Queue)

        scene.frames(48)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        assertThat(flight.targetId).isEqualTo("q-2")
        assertThat(flight.landedOn).isEqualTo(SendLanding.Queue)
        assertThat(landed(flight)).isEqualTo(rowBounds(2, 2))
        // The row is the flight's, surface and all, until the hand-over; the row already standing is drawn as ever.
        scene.frames(SendMotion.FlightMillis / 3L)
        assertThat(motion.hides("q-2", "Then reseed the fixtures", SendLanding.Queue)).isTrue()
        assertThat(motion.hides("q-1", "Run the migration first", SendLanding.Queue)).isFalse()
        // Nothing in the transcript is taken for it.
        assertThat(motion.hides("q-2", "Then reseed the fixtures")).isFalse()

        scene.frames(SendMotion.FlightMillis.toLong() + 64)
        assertThat(motion.flight).isNull()
        assertThat(motion.hides("q-2", "Then reseed the fixtures", SendLanding.Queue)).isFalse()
    }

    @Test
    fun `a queued send's pictures and file fly from the composer's row onto the row's tiles`() {
        scene.composerText = "Match the header to these"
        scene.attach()
        scene.show(motion)
        val flight = checkNotNull(scene.sendQueued(motion, "q-1"))
        assertThat(flight.takeoff.attachments.map { it.look.ordinal }).containsExactly(0, 1, 2).inOrder()
        scene.frames(48)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        val chips = flight.takeoff.attachments.map { it.rect }
        val tiles = flight.takeoff.attachments.map { checkNotNull(flight.targetOf(it)) }
        // Each lands on its own tile, in the prompt's order, which is far smaller than the chip it left.
        assertThat(tiles.map { it.left }).isInStrictOrder()
        for (i in tiles.indices) assertThat(tiles[i].width).isLessThan(chips[i].width)

        scene.frames(SendMotion.FlightMillis / 2L)
        val copies = compose.onAllNodesWithTag(FlyingAttachmentTag, useUnmergedTree = true)
        copies.assertCountEquals(3)
        for (i in tiles.indices) {
            val box = copies[i].fetchSemanticsNode().boundsInWindow
            assertThat(box.width).isIn(Range.open(tiles[i].width, chips[i].width))
            assertThat(box.height).isIn(Range.open(tiles[i].height, chips[i].height))
        }
        scene.frames(SendMotion.FlightMillis.toLong() + 64)
        assertThat(motion.flight).isNull()
        compose.onAllNodesWithTag(FlyingAttachmentTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `a row the run takes lifts off the card, card and all, and lands in its bubble`() {
        scene.queue += listOf(queued("q-1", "Run the migration first"), queued("q-2", "Then reseed the fixtures"))
        scene.show(motion)
        val card = rowBounds(1, 2)
        val row = scene.flights.anchor("q-1")
        scene.deliver("q-1", bubble = "u-2")
        assertThat(motion.flights).hasSize(1)
        val flight = checkNotNull(motion.flight)
        assertThat(flight.takeoff.anchor).isSameInstanceAs(row)
        assertThat(flight.takeoff.composer).isEqualTo(card)
        assertThat(flight.takeoff.card).isNotNull()
        assertThat(flight.holdMillis).isEqualTo(SendMotion.DeliveryHoldMillis)

        scene.frames(48)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        assertThat(flight.targetId).isEqualTo("u-2")
        assertThat(flight.landedOn).isEqualTo(SendLanding.Bubble)
        scene.frames(SendMotion.FlightMillis / 3L)
        assertThat(motion.hides("u-2", "Run the migration first")).isTrue()
        assertThat(motion.hides("u-1", "Profile the cold start and tell me where the time goes.")).isFalse()
        scene.frames(SendMotion.FlightMillis.toLong() + 64)
        assertThat(motion.flight).isNull()
    }

    @Test
    fun `a device row whose bubble the run files later is held over the card until it comes`() {
        scene.queue += queued("q-1", "Run the migration first")
        scene.show(motion)
        compose.runOnUiThread { scene.queue.clear() }
        scene.frame()
        val flight = checkNotNull(motion.flight)
        scene.frames(480)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Holding)
        compose.runOnUiThread { scene.messages += com.cursorforandroid.domain.UserMessage("u-2", "Run the migration first") }
        scene.frame()
        scene.frames(32)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        assertThat(flight.targetId).isEqualTo("u-2")
    }

    @Test
    fun `only a row that leaves flies - reordering the card lifts nothing, and the middle row goes alone`() {
        scene.queue += listOf(queued("q-1", "One"), queued("q-2", "Two"), queued("q-3", "Three"))
        scene.show(motion)
        compose.runOnUiThread { scene.queue.add(0, scene.queue.removeAt(2)) }
        scene.frames(32)
        assertThat(motion.flights).isEmpty()

        val middle = scene.flights.anchor("q-2")
        scene.deliver("q-2", bubble = "u-2")
        assertThat(motion.flights).hasSize(1)
        assertThat(motion.flight!!.takeoff.anchor).isSameInstanceAs(middle)
        assertThat(motion.flight!!.text).isEqualTo("Two")
    }

    @Test
    fun `a row the reader removed or took back to edit flies nowhere`() {
        scene.queue += listOf(queued("q-1", "One"), queued("q-2", "Two"))
        scene.show(motion)
        scene.flights.dismiss("q-1")
        compose.runOnUiThread { scene.queue.removeAll { it.id == "q-1" } }
        scene.frames(32)
        assertThat(motion.flights).isEmpty()
    }

    @Test
    fun `a send landing on a row that moves up the card follows it there`() {
        scene.queue += queued("q-1", "Run the migration first")
        scene.composerText = "Then reseed the fixtures"
        scene.show(motion)
        val flight = checkNotNull(scene.sendQueued(motion, "q-2"))
        scene.frames(48)
        compose.runOnUiThread { scene.queue.add(0, scene.queue.removeAt(1)) }
        scene.frames(32)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        assertThat(landed(flight)).isEqualTo(rowBounds(1, 2))
        scene.frames(SendMotion.FlightMillis.toLong() + 64)
        assertThat(motion.flight).isNull()
    }

    @Test
    fun `a row taken while a send is still landing on it flies on to its bubble from where it stood`() {
        scene.composerText = "Run the migration first"
        scene.show(motion)
        val landing = checkNotNull(scene.sendQueued(motion, "q-1"))
        scene.frames(48 + SendMotion.FlightMillis / 3L)
        val row = scene.flights.anchor("q-1")
        scene.deliver("q-1", bubble = "u-2")
        assertThat(motion.flights).doesNotContain(landing)
        val delivery = checkNotNull(motion.flight)
        assertThat(delivery.takeoff.anchor).isSameInstanceAs(row)
        scene.frames(48)
        assertThat(delivery.targetId).isEqualTo("u-2")
    }

    @Test
    fun `a delivery and a queued send fly at once, each to its own end`() {
        scene.queue += queued("q-1", "Run the migration first")
        scene.composerText = "Then reseed the fixtures"
        scene.show(motion)
        scene.deliver("q-1", bubble = "u-2")
        val send = checkNotNull(scene.sendQueued(motion, "q-2"))
        assertThat(motion.flights).hasSize(2)
        scene.frames(48)
        val delivery = motion.flights.first { it !== send }
        assertThat(delivery.targetId).isEqualTo("u-2")
        assertThat(send.targetId).isEqualTo("q-2")
        assertThat(motion.hides("u-2", "Run the migration first")).isTrue()
        assertThat(motion.hides("q-2", "Then reseed the fixtures", SendLanding.Queue)).isTrue()
    }

    @Test
    fun `scrolled off the newest turn, a delivered row fades where it stood rather than waiting for its bubble`() {
        scene.queue += queued("q-1", "Run the migration first")
        scene.scrolledAway = true
        scene.show(motion)
        compose.runOnUiThread { scene.queue.clear() }
        scene.frame()
        val flight = checkNotNull(motion.flight)
        assertThat(flight.holdMillis).isEqualTo(0L)
        scene.frames(32)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Fading)
        scene.frames(SendMotion.FadeMillis.toLong() + 48)
        assertThat(motion.flight).isNull()
        assertThat(flight.progress.value).isEqualTo(0f)
    }

    @Test
    fun `with animations off nothing flies either way, and every row is drawn`() {
        val off = SendMotion(animatorsEnabled = { false })
        scene.queue += queued("q-1", "Run the migration first")
        scene.composerText = "Then reseed the fixtures"
        scene.show(off)
        assertThat(scene.sendQueued(off, "q-2")).isNull()
        scene.deliver("q-1", bubble = "u-2")
        assertThat(off.flights).isEmpty()
        assertThat(off.hides("q-2", "Then reseed the fixtures", SendLanding.Queue)).isFalse()
        assertThat(off.hides("u-2", "Run the migration first")).isFalse()
    }

    @Test
    fun `on the account's card a send's files land on the row's line of names and dissolve into it`() {
        scene.composerText = "Match the header to these"
        scene.attach()
        scene.show(motion)
        val flight = checkNotNull(scene.sendQueued(motion, "a-1", onAccount = true))
        scene.frames(48)
        assertThat(flight.targetId).isEqualTo("a-1")
        val names = compose.onAllNodesWithTag("queued-attachments", useUnmergedTree = true)[0].fetchSemanticsNode().boundsInWindow
        for (attachment in flight.takeoff.attachments) assertThat(flight.targetOf(attachment)).isEqualTo(names)
        scene.frames(SendMotion.FlightMillis / 2L)
        for (attachment in flight.takeoff.attachments) assertThat(flight.alphaOf(attachment)).isLessThan(0.5f)
    }

    @Test
    fun `a device row handed to the account's queue flies into the account's row`() {
        scene.queue += queued("q-1", "Run the migration first")
        scene.show(motion)
        compose.runOnUiThread {
            scene.queue.clear()
            scene.account += PendingFollowup("a-1", "Run the migration first")
        }
        scene.frame()
        val flight = checkNotNull(motion.flight)
        scene.frames(48)
        assertThat(flight.targetId).isEqualTo("a-1")
        assertThat(flight.landedOn).isEqualTo(SendLanding.Queue)
    }

    /**
     * The demo: two follow-ups sent while the agent works — the second with two pictures and a spec — each landing in
     * its row on the card, then the run taking them one after the other into the transcript.
     */
    @Test
    fun demo() {
        val dir = System.getenv("QUEUE_DEMO_DIR")?.let(::File)
        var frame = 0
        fun film(millis: Long) {
            var left = millis
            while (left > 0) {
                dir?.let { scene.drawTo(File(it, "queue_%04d.png".format(frame++))) }
                compose.mainClock.advanceTimeBy(16)
                compose.waitForIdle()
                left -= 16
            }
        }
        scene.composerText = "Run the migration first"
        scene.show(motion)
        film(500)
        assertThat(scene.sendQueued(motion, "q-1")).isNotNull()
        film(SendMotion.FlightMillis + 700L)
        var sent = emptyList<com.cursorforandroid.domain.MessageAttachment>()
        compose.runOnUiThread {
            scene.composerText = "Then match the header to these, and follow the spec"
            sent = scene.attach()
        }
        film(600)
        assertThat(scene.sendQueued(motion, "q-2")).isNotNull()
        film(SendMotion.FlightMillis + 900L)
        scene.deliver("q-1", bubble = "u-2")
        film(SendMotion.FlightMillis + 900L)
        scene.deliver("q-2", bubble = "u-3", attachments = sent)
        film(SendMotion.FlightMillis + 900L)
        assertThat(motion.flights).isEmpty()
        assertThat(scene.messages.map { it.id }).containsExactly("u-1", "u-2", "u-3").inOrder()
    }
}
