package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.ui.components.SendMotion
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer

/**
 * A queued row handed to the transcript: the card must not leave until the bubble is composed, in one frame — no
 * frame with neither, and no second transcript shift on the next frame (Bennett's double jump on #443).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class QueueHandoverSameFrameTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scene = QueueMotionScene(compose)
    private val motion = SendMotion(animatorsEnabled = { true })
    private val deliveredText = "Run the migration first"

    @Before
    fun setUp() {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copyPixelsToBuffer(ByteBuffer.allocate(4))
    }

    private fun queued(vararg texts: String) = texts.mapIndexed { i, text -> QueuedFollowUp("q-${i + 1}", text, queuedAtMillis = i.toLong()) }

    private fun stackHeight(): Float =
        compose.onAllNodes(hasTestTag(QueueStackTag), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.boundsInWindow?.height ?: 0f

    private fun transcriptTop(): Float =
        compose.onNodeWithText("Profile the cold start and tell me where the time goes.", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow.top

    private fun bubbleComposed(): Boolean =
        runCatching {
            compose.onNode(hasText(deliveredText) and !hasAnyAncestor(hasTestTag(QueueStackTag)), useUnmergedTree = true)
                .fetchSemanticsNode()
            true
        }.getOrDefault(false)

    /** The front row of a two-message deck (the row being delivered). */
    private fun leadingQueuedCard(): Boolean =
        compose.onAllNodesWithContentDescription("Queued follow-up 1 of 2", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun stepFrame(record: MutableList<HandoverFrame>, after: () -> Unit = {}) {
        val beforeTop = transcriptTop()
        val beforeStack = stackHeight()
        compose.runOnUiThread(after)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val top = transcriptTop()
        val stack = stackHeight()
        val bubble = bubbleComposed()
        val card = leadingQueuedCard()
        record += HandoverFrame(
            card = card,
            bubble = bubble,
            stackHeight = stack,
            transcriptTop = top,
            transcriptJump = top - beforeTop,
            stackJump = stack - beforeStack,
        )
    }

    private data class HandoverFrame(
        val card: Boolean,
        val bubble: Boolean,
        val stackHeight: Float,
        val transcriptTop: Float,
        val transcriptJump: Float,
        val stackJump: Float,
    )

    private fun assertHandover(frames: List<HandoverFrame>) {
        frames.forEachIndexed { i, f ->
            assertWithMessage("frame $i: card and bubble both gone — $f").that(f.card || f.bubble).isTrue()
        }
        val handover = frames.indexOfFirst { it.bubble }
        assertWithMessage("never handed over: $frames").that(handover).isAtLeast(0)
        assertWithMessage("stack shrank before the bubble landed: ${frames.take(handover + 1)}")
            .that(frames.take(handover).any { it.stackJump < -1f && !it.bubble }).isFalse()
        val after = frames.drop(handover)
        val doubleJump = after.take(3).count { kotlin.math.abs(it.transcriptJump) > 2f } >= 2
        assertWithMessage("transcript jumped twice after handover: $after").that(doubleJump).isFalse()
    }

    @Test
    fun `delivery with the repository ahead of the presenter stays one movement`() {
        scene.queue += queued(deliveredText, "Then reseed the fixtures")
        scene.show(motion)
        val frames = mutableListOf<HandoverFrame>()
        repeat(3) { stepFrame(frames) }
        stepFrame(frames) { scene.deliverQueueBeforeBubble("q-1", "u-delivered") }
        repeat(25) { stepFrame(frames) }
        assertHandover(frames)
    }

    @Test
    fun `delivery handover is stable across thirty runs`() {
        scene.queue += queued(deliveredText, "Then reseed the fixtures")
        scene.show(motion)
        var passes = 0
        repeat(30) { run ->
            scene.messages.removeAll { it.id.startsWith("u-delivered") }
            scene.queue.clear()
            scene.queue += queued(deliveredText, "Then reseed the fixtures")
            scene.generation++
            scene.frames(32)
            val frames = mutableListOf<HandoverFrame>()
            repeat(2) { stepFrame(frames) }
            stepFrame(frames) { scene.deliverQueueBeforeBubble("q-1", "u-delivered-$run") }
            repeat(20) { stepFrame(frames) }
            try {
                assertHandover(frames)
                passes++
            } catch (_: AssertionError) {
            }
        }
        println("QueueHandoverSameFrameTest: $passes/30 passed")
        assertThat(passes).isEqualTo(30)
    }
}
