package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import kotlinx.coroutines.runBlocking
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
 * On the account's card (Extended mode, what a phone signed in runs) the row's tiles are this device's copies of what
 * the message carries, kept when the account's list names it in names and a count, and a delivery carries them into
 * the bubble; a message queued elsewhere shows the account's word as plain tiles.
 *
 * With `QUEUE_DEMO_DIR` set, every frame of [demo] and [accountDemo] is written there as a PNG (the demo videos' frames).
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
        scene.frames(32)
        assertThat(motion.flights).isEmpty()
        compose.onAllNodesWithContentDescription("Queued follow-up 1 of", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        compose.runOnUiThread { scene.messages += com.cursorforandroid.domain.UserMessage("u-2", "Run the migration first") }
        scene.frames(96)
        compose.onNodeWithText("Run the migration first", useUnmergedTree = true).assertExists()
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

    /** The account card's tiles, left to right, as laid out. */
    private fun accountTiles() = listOf("Attached image", "Attached video", "Attached file").flatMap { what ->
        compose.onAllNodes(hasContentDescription(what, substring = true) and hasAnyAncestor(hasTestTag("account-queue-row")), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.ContentDescription].single() to it.boundsInWindow }
    }.sortedBy { it.second.left }

    /** A send while the agent works in Extended mode, as a phone signed in makes it: onto the account's card, with two pictures and a spec. */
    private fun sendOnAccount(id: String = "a-1"): SendFlight {
        scene.onAccount = true
        scene.composerText = "Match the header to these, and follow the spec"
        scene.attach()
        scene.show(motion)
        return checkNotNull(scene.sendQueued(motion, id, onAccount = true))
    }

    @Test
    fun `on the account's card a send's pictures and file land on the row's own tiles, drawn from this device's copies`() {
        val flight = sendOnAccount()
        assertThat(flight.takeoff.attachments.map { it.look.ordinal }).containsExactly(0, 1, 2).inOrder()
        scene.frames(48)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        assertThat(flight.targetId).isEqualTo("a-1")
        assertThat(flight.landedOn).isEqualTo(SendLanding.Queue)
        // The row the account's card shows for it has a tile for each, in the prompt's order — no line of names to dissolve into.
        val tiles = accountTiles()
        assertThat(tiles.map { it.first }).containsExactly("Attached image", "Attached image", "Attached file Q3-header-spec.pdf").inOrder()
        val chips = flight.takeoff.attachments.map { it.rect }
        flight.takeoff.attachments.forEachIndexed { i, attachment ->
            val tile = checkNotNull(flight.targetOf(attachment))
            assertThat(tile).isEqualTo(tiles[i].second)
            assertThat(tile.width).isLessThan(chips[i].width)
        }
        // Whole on the way, as on the device's card: each lands on its tile rather than fading into words.
        scene.frames(SendMotion.FlightMillis / 2L)
        for (attachment in flight.takeoff.attachments) assertThat(flight.alphaOf(attachment)).isGreaterThan(0.5f)
        scene.frames(SendMotion.FlightMillis.toLong() + 64)
        assertThat(motion.flight).isNull()
        compose.onAllNodesWithTag(FlyingAttachmentTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `the account's row keeps this device's pictures when its list names the message in names and a count`() {
        sendOnAccount()
        scene.frames(SendMotion.FlightMillis.toLong() + 200)
        val before = accountTiles()
        scene.accountTakes("a-1")
        scene.frames(64)
        // The list's row, not this device's: the account's word on it is names and a count, the tiles this device's copies.
        val row = scene.accountRows.single()
        assertThat(scene.account.single().attachments).isEmpty()
        assertThat(row.attachments.map { File(it.path).isFile }).containsExactly(true, true, true)
        assertThat(accountTiles()).isEqualTo(before)
        compose.onAllNodesWithTag("queued-attachments", useUnmergedTree = true).fetchSemanticsNodes().single().let { names ->
            assertThat(names.config[SemanticsProperties.Text].single().text).isEqualTo("Q3-header-spec.pdf")
        }
    }

    @Test
    fun `a delivery from the account's card carries its tiles into the bubble's thumbnails, through the copies' move under the run`() {
        sendOnAccount()
        scene.frames(SendMotion.FlightMillis.toLong() + 200)
        scene.accountTakes("a-1")
        scene.awaitTilePreviews()
        val tiles = accountTiles().map { it.second }
        val row = scene.flights.anchor("a-1")
        val filed = scene.deliver("a-1", bubble = "u-2")
        val flight = checkNotNull(motion.flight)
        assertThat(flight.takeoff.anchor).isSameInstanceAs(row)
        // Lifted off the row's tiles, pictures and all: the row's copies, not placeholders.
        assertThat(flight.takeoff.attachments.map { it.look.ordinal }).containsExactly(0, 1, 2).inOrder()
        assertThat(flight.takeoff.attachments.map { it.rect }).isEqualTo(tiles)
        assertThat(flight.takeoff.attachments.take(2).map { it.look.thumbnail }).doesNotContain(null)
        // The copies moved under the run as the bubble was filed; the bubble names them there.
        assertThat(filed.map { File(it.path).parentFile?.name }).containsExactly("u-2", "u-2", "u-2")
        scene.frames(48)
        assertThat(flight.phase).isEqualTo(SendFlight.Phase.Flying)
        assertThat(flight.targetId).isEqualTo("u-2")
        flight.takeoff.attachments.forEachIndexed { i, attachment ->
            val landing = checkNotNull(flight.targetOf(attachment)) { "attachment $i has no place in the bubble" }
            assertThat(landing.width).isGreaterThan(tiles[i].width)
        }
        scene.frames(SendMotion.FlightMillis / 3L)
        for (attachment in flight.takeoff.attachments) assertThat(flight.alphaOf(attachment)).isGreaterThan(0.5f)
        scene.frames(SendMotion.FlightMillis.toLong() + 64)
        assertThat(motion.flight).isNull()
        assertThat(scene.messages.single { it.id == "u-2" }.attachments).isEqualTo(filed)
    }

    @Test
    fun `a message queued elsewhere shows the account's word as plain tiles, which fade where they stood if the bubble has nowhere for them`() {
        scene.onAccount = true
        scene.account += PendingFollowup(
            "a-1",
            "Match the header to these",
            files = listOf(com.cursorforandroid.domain.PendingAttachment("Q3-header-spec.pdf", "application/pdf")),
            imageCount = 2,
        )
        scene.show(motion)
        assertThat(accountTiles().map { it.first }).containsExactly("Attached image", "Attached image", "Attached file Q3-header-spec.pdf").inOrder()
        scene.deliver("a-1", bubble = "u-2")
        val flight = checkNotNull(motion.flight)
        assertThat(flight.takeoff.attachments).hasSize(3)
        scene.frames(48 + SendMotion.FlightMillis / 2L)
        assertThat(flight.targetId).isEqualTo("u-2")
        for (attachment in flight.takeoff.attachments) {
            assertThat(flight.targetOf(attachment)).isNull()
            assertThat(flight.alphaOf(attachment)).isLessThan(0.5f)
        }
    }

    @Test
    fun `a device row handed to the account's queue flies into the account's row, its pictures onto the row's tiles`() {
        scene.composerText = "Match the header to these, and follow the spec"
        scene.attach()
        scene.show(motion)
        scene.sendQueued(motion, "q-1")
        scene.frames(SendMotion.FlightMillis.toLong() + 200)
        assertThat(motion.flight).isNull()
        // Refused as busy, the message goes to the account's queue with what it carries, staged here again
        // (`ConversationRepository.expectDelivery`): the account's row in the frame the device's goes.
        val item = scene.queue.single()
        val set = runBlocking { scene.store.stage(item.images.map { it.image }, item.files.map { it.file }) }
        compose.runOnUiThread {
            scene.queue.clear()
            scene.waiting += scene.ownRow("a-1", item.text, set.attachments)
        }
        scene.frame()
        val flight = checkNotNull(motion.flight)
        assertThat(flight.takeoff.attachments).hasSize(3)
        scene.frames(48)
        assertThat(flight.targetId).isEqualTo("a-1")
        assertThat(flight.landedOn).isEqualTo(SendLanding.Queue)
        val tiles = accountTiles().map { it.second }
        assertThat(flight.takeoff.attachments.map { flight.targetOf(it) }).isEqualTo(tiles)
    }

    /**
     * The demo: two follow-ups sent while the agent works — the second with two pictures and a spec — each landing in
     * its row on the card, then the run taking them one after the other into the transcript.
     */
    private val demoDir = System.getenv("QUEUE_DEMO_DIR")?.let(::File)
    private var demoFrame = 0

    /** On by [millis], a frame at a time, each written to [demoDir] when it is set. */
    private fun film(millis: Long) {
        var left = millis
        while (left > 0) {
            demoDir?.let { scene.drawTo(File(it, "queue_%04d.png".format(demoFrame++))) }
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            left -= 16
        }
    }

    @Test
    fun demo() {
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

    /**
     * The demo of the path a phone signed in runs (Extended mode): two follow-ups sent while the agent works, onto the
     * account's card — the second with two pictures and a spec, which land on its row's tiles — the account's list
     * naming them, then the run taking them one after the other into the transcript, the pictures flying off the
     * tiles into the bubble's thumbnails.
     */
    @Test
    fun accountDemo() {
        scene.onAccount = true
        scene.composerText = "Run the migration first"
        scene.show(motion)
        film(500)
        assertThat(scene.sendQueued(motion, "a-1", onAccount = true)).isNotNull()
        film(400)
        scene.accountTakes("a-1")
        film(SendMotion.FlightMillis + 300L)
        compose.runOnUiThread {
            scene.composerText = "Then match the header to these, and follow the spec"
            scene.attach()
        }
        film(600)
        assertThat(scene.sendQueued(motion, "a-2", onAccount = true)).isNotNull()
        film(400)
        scene.accountTakes("a-2")
        film(SendMotion.FlightMillis + 500L)
        scene.awaitTilePreviews()
        film(300)
        assertThat(accountTiles()).hasSize(3)
        scene.deliver("a-1", bubble = "u-2")
        film(SendMotion.FlightMillis + 900L)
        val filed = scene.deliver("a-2", bubble = "u-3")
        film(SendMotion.FlightMillis + 1_200L)
        assertThat(motion.flights).isEmpty()
        assertThat(scene.messages.map { it.id }).containsExactly("u-1", "u-2", "u-3").inOrder()
        assertThat(scene.messages.last().attachments).isEqualTo(filed)
    }
}
