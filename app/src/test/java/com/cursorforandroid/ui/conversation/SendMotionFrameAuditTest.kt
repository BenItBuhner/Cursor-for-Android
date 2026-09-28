package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.geometry.Rect as CRect
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.audit.ComposerKeystrokeAuditTest
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendLanding
import com.cursorforandroid.ui.components.SendMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Audit probe (prints `AUDIT frame …`, asserts nothing): the queue's send and delivery motion one 16 ms frame at a
 * time on a held clock — the ink in the composer's field, in the new/leaving row, in the bubble, the deck's height,
 * the flight's phase, and the scopes recomposed that frame.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class SendMotionFrameAuditTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scene = QueueMotionScene(compose)
    private val motion = SendMotion(animatorsEnabled = { true })
    private val rec = ComposerKeystrokeAuditTest.Recompositions()
    private var data: androidx.compose.runtime.tooling.CompositionData? = null

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val root = currentComposer.composition
            remember(root) { root.observe(rec) }
            val d = currentComposer.compositionData
            remember { data = d }
            scene.Content(motion)
        }
        scene.frames(400)
        rec.observeAll(data!!)
    }

    private fun draw(): Bitmap {
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        return bitmap
    }

    /** Mean distance from the canvas colour over [r], 0..255: how much is drawn there. */
    private fun ink(b: Bitmap, r: Rect?, canvas: Int): Int {
        r ?: return -1
        val rr = Rect(r).apply { intersect(0, 0, b.width, b.height) }
        if (rr.isEmpty) return -1
        var sum = 0L
        var n = 0
        val px = IntArray(rr.width())
        for (y in rr.top until rr.bottom step 2) {
            b.getPixels(px, 0, rr.width(), rr.left, y, rr.width(), 1)
            for (x in px.indices step 2) {
                val p = px[x]
                sum += abs(((p shr 16) and 255) - ((canvas shr 16) and 255)) + abs(((p shr 8) and 255) - ((canvas shr 8) and 255)) + abs((p and 255) - (canvas and 255))
                n++
            }
        }
        return (sum / (3L * n)).toInt()
    }

    private fun CRect.px() = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    /** The row's text on the card: not the composer's field, whose text may still say the same. */
    private fun boundsOfText(t: String): Rect? {
        val fieldTop = fieldBounds()?.top ?: Int.MAX_VALUE
        return compose.onAllNodes(hasText(t, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInWindow.px() }.lastOrNull { it.bottom <= fieldTop }
    }
    private fun stackHeight(): Int = compose.onAllNodes(hasTestTag(QueueStackTag), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.height?.toInt() ?: 0
    private fun fieldBounds(): Rect? = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.px()

    private fun step(label: String, i: Int, flight: SendFlight?, regions: List<Pair<String, Rect?>>) {
        rec.reset()
        compose.mainClock.advanceTimeBy(16)
        compose.waitForIdle()
        val b = draw()
        val canvas = b.getPixel(4, b.height / 3)
        val inks = regions.joinToString(" ") { (n, r) -> "$n=${ink(b, r, canvas)}" }
        println("AUDIT frame [$label] f=${"%02d".format(i)} t=${(i + 1) * 16}ms phase=${flight?.phase} p=${flight?.progress?.value?.let { "%.2f".format(it) }} stackH=${stackHeight()} $inks scopes=${rec.scopes}" +
            (if (rec.scopes > 0) " top=" + rec.byName.entries.sortedByDescending { it.value }.take(3).joinToString("|") { it.key.substringAfterLast('.').take(40) } else ""))
    }

    @Test
    fun `queued send - frame by frame`() {
        scene.queue += QueuedFollowUp("q-1", "Run the migration first", queuedAtMillis = 0L)
        val text = "Then reseed the fixtures and rerun the flaky suite"
        scene.composerText = text
        show()
        val field = fieldBounds()
        var flight: SendFlight? = null
        compose.runOnUiThread {
            flight = motion.depart(scene.anchor.takeoff(), text, excluded = setOf("q-1"), landing = SendLanding.Queue)
            scene.queue += QueuedFollowUp("q-2", text, queuedAtMillis = 0L)
            scene.composerText = ""
        }
        var row: Rect? = null
        for (i in 0 until 36) {
            val now = boundsOfText("Then reseed")
            if (row == null) row = now
            step("queued-send rowTop=${now?.top}", i, motion.flight, listOf("field" to field, "newRow" to row, "q1Row" to boundsOfText("Run the migration")))
        }
    }

    @Test
    fun `delivery - frame by frame`() {
        scene.queue += listOf(
            QueuedFollowUp("q-1", "Run the migration first", queuedAtMillis = 0L),
            QueuedFollowUp("q-2", "Then reseed the fixtures", queuedAtMillis = 0L),
        )
        show()
        val row = boundsOfText("Run the migration")
        println("AUDIT frame [delivery] before: stackH=${stackHeight()} q1Top=${row?.top} q2Top=${boundsOfText("Then reseed")?.top}")
        compose.runOnUiThread {
            scene.queue.removeAll { it.id == "q-1" }
            scene.messages += UserMessage("u-2", "Run the migration first")
        }
        var bubble: Rect? = null
        val out = java.io.File(System.getProperty("user.dir"), "../build/audit-frames").normalize().apply { mkdirs() }
        for (i in 0 until 40) {
            if (i <= 4) scene.drawTo(java.io.File(out, "delivery_before_f%02d.png".format(i)))
            if (bubble == null) bubble = compose.onAllNodes(hasText("Run the migration first"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull { (it.boundsInWindow.top) < (row?.top ?: 0) }?.boundsInWindow?.px()
            val q2 = boundsOfText("Then reseed")
            step("delivery q2Top=${q2?.top}", i, motion.flight, listOf("leavingRowArea" to row, "bubble" to bubble, "q2Row" to q2))
        }
    }

    @Test
    fun `third send forms deck - frame by frame`() {
        scene.queue += listOf(
            QueuedFollowUp("q-1", "Run the migration first", queuedAtMillis = 0L),
            QueuedFollowUp("q-2", "Then reseed the fixtures", queuedAtMillis = 0L),
        )
        val text = "Then rerun the flaky suite on the emulator matrix"
        scene.composerText = text
        show()
        var flight: SendFlight? = null
        compose.runOnUiThread {
            flight = motion.depart(scene.anchor.takeoff(), text, excluded = setOf("q-1", "q-2"), landing = SendLanding.Queue)
            scene.queue += QueuedFollowUp("q-3", text, queuedAtMillis = 0L)
            scene.composerText = ""
        }
        println("AUDIT frame [deck-forming] before: stackH=${stackHeight()}")
        for (i in 0 until 50) step("deck-forming", i, motion.flight, listOf("field" to fieldBounds()))
    }
}
