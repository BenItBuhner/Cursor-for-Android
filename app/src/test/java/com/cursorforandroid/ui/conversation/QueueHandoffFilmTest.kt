package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.SendMotion
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

/** Frame-by-frame capture around a queued delivery hand-off; set `QUEUE_HANDOFF_FILM_DIR` to export PNGs. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueHandoffFilmTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = System.getenv("QUEUE_HANDOFF_FILM_DIR")?.let(::File)
    private val scene = QueueMotionScene(compose)
    private val motion = SendMotion(animatorsEnabled = { true })

    @Before
    fun setUp() {
        assumeTrue(dir != null)
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
        dir?.mkdirs()
    }

    @Test
    fun filmQueuedDeliveryHandoff() {
        scene.queue += QueuedFollowUp("q-1", "Run the migration first", queuedAtMillis = 0L)
        scene.queue += QueuedFollowUp("q-2", "Then reseed the fixtures", queuedAtMillis = 1L)
        scene.show(motion)
        var frame = 0
        fun snap(label: String) {
            val file = File(dir, "${frame.toString().padStart(3, '0')}_$label.png")
            val root = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { root.draw(Canvas(bitmap)) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            frame++
        }
        repeat(4) { snap("before") }
        scene.deliverQueueBeforeBubble("q-1", "u-delivered")
        repeat(12) {
            snap("handoff")
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        repeat(8) { snap("after") }
    }
}
