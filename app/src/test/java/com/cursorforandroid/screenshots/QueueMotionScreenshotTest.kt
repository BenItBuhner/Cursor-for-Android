package com.cursorforandroid.screenshots

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.conversation.QueueMotionScene
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

/**
 * The send's flight with the queue card at either end, caught mid-motion on a stopped clock: a message sent while the
 * agent works on its way from the composer into its row, with pictures and a spec shrinking onto the row's tiles; and
 * a queued row the run took lifting off the card, then on its way into its bubble. Same device qualifiers as
 * [AppScreenshotTest]; written to `screenshots/`, which CI compares pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueMotionScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val scene = QueueMotionScene(compose)
    private val motion = SendMotion(animatorsEnabled = { true })

    @Before
    fun setUp() {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    private fun capture(name: String) = compose.onNodeWithTag(QueueMotionScene.Frame).captureRoboImage(File(outDir, "$name.png").path, RoborazziOptions())

    /**
     * On to the flight's takeoff, then held exactly [into] its way: the frames its tween lands on depend on what ran in
     * the JVM before it, so the tween is stopped and the flight pinned there rather than caught on the nearest frame.
     */
    private fun catchAt(flight: SendFlight, into: Float) {
        while (flight.phase != SendFlight.Phase.Flying) scene.frames(16)
        MainScope().launch { flight.progress.snapTo(into) }
        scene.frame()
        assertThat(flight.progress.value).isEqualTo(into)
    }

    /** The tap while the agent works, and the flight caught [into] its way to the row. */
    private fun sendAndCatch(into: Float): SendFlight {
        val flight = checkNotNull(scene.sendQueued(motion, "q-2"))
        catchAt(flight, into)
        assertThat(flight.targetId).isEqualTo("q-2")
        return flight
    }

    @Test
    fun queuedSendMidFlight() {
        scene.queue += QueuedFollowUp("q-1", "Run the migration first", queuedAtMillis = 0L)
        scene.composerText = "Then reseed the fixtures and rerun the flaky suite"
        scene.show(motion)
        sendAndCatch(into = 0.15f)
        capture("690_queue_send_mid_flight")
    }

    @Test
    fun queuedSendAttachmentsMidFlight() {
        scene.queue += QueuedFollowUp("q-1", "Run the migration first", queuedAtMillis = 0L)
        scene.composerText = "Match the header to these, and follow the spec"
        scene.attach()
        scene.show(motion)
        sendAndCatch(into = 0.25f)
        capture("691_queue_send_attachments_mid_flight")
    }

    /** A queued row the run took, [into] its flight to the bubble filed with it. */
    private fun deliverAndCatch(into: Float) {
        scene.queue += listOf(
            QueuedFollowUp("q-1", "Run the migration first", queuedAtMillis = 0L),
            QueuedFollowUp("q-2", "Then reseed the fixtures", queuedAtMillis = 0L),
        )
        scene.show(motion)
        scene.deliver("q-1", bubble = "u-2")
        val flight = checkNotNull(motion.flight)
        catchAt(flight, into)
        assertThat(flight.targetId).isEqualTo("u-2")
    }

    @Test
    fun deliveryLiftingOff() {
        deliverAndCatch(into = 0.05f)
        capture("692_queue_delivery_lifting_off")
    }

    @Test
    fun deliveryMidFlight() {
        deliverAndCatch(into = 0.25f)
        capture("693_queue_delivery_mid_flight")
    }

    @Test
    fun deliveryWithAttachmentsMidFlight() {
        scene.composerText = "Match the header to these, and follow the spec"
        val sent = scene.attach()
        scene.show(motion)
        scene.sendQueued(motion, "q-1")
        scene.frames(SendMotion.FlightMillis + 200L)
        assertThat(motion.flight).isNull()
        scene.deliver("q-1", bubble = "u-2", attachments = sent)
        catchAt(checkNotNull(motion.flight), 0.25f)
        capture("694_queue_delivery_attachments_mid_flight")
    }
}
