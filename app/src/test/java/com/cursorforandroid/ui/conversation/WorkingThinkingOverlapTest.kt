package com.cursorforandroid.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * After a follow-up, the working caption under the transcript must finish fading out before the live stretch's
 * "Thinking" line is composed in that slot — never both partly visible on top of each other.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class WorkingThinkingOverlapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val agentId = "bc-working-thinking-overlap"
    private val run2 = "run-2"
    private val now = Instant.parse("2026-09-29T12:00:00Z").toEpochMilli()
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun seedRunningNoThought() = runBlocking {
        api.addFinishedAgent(agentId, "Overlap", Triple("run-1", "First question", "First answer"))
        val created = Instant.ofEpochMilli(now).toString()
        api.runs[run2] = RunDto(id = run2, agentId = agentId, status = "RUNNING", createdAt = created, updatedAt = created)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = run2, updatedAt = created)
        api.transcripts[agentId] = api.transcripts.getValue(agentId) + V0ConversationMessageDto("$run2-u", "user_message", "Follow up")
        streamer.emit(run2, RunStreamEvent.Status(run2, RunStatus.RUNNING))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open() {
        seedRunningNoThought()
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(
            context,
            SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) },
            demo = CursorBackend(api, streamer, isDemo = true),
        )
        runBlocking {
            graph.session.enterDemo()
            graph.agents.refresh()
        }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        compose.waitUntil(30_000) {
            graph.conversations.state(agentId).value.runStatus?.isActive == true &&
                runCatching { compose.onNodeWithTag(WORKING_CAPTION_TAG, useUnmergedTree = true) }.isSuccess
        }
        compose.waitForIdle()
    }

    private fun transcriptBounds(): Rect =
        compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot

    private fun captionBounds(): Rect? = runCatching {
        compose.onNode(hasTestTag(WORKING_CAPTION_TAG), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    }.getOrNull()

    private fun thinkingBounds(): Rect? = runCatching {
        compose.onNode(hasTestTag("thinking-progress"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    }.getOrNull() ?: compose.onAllNodes(hasAnyAncestor(hasScrollToIndexAction()) and hasText("Thinking"), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .lastOrNull()
        ?.boundsInRoot

    private val frameDir = System.getenv("WORKING_THINKING_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)
    private var framesKept = 0

    @OptIn(ExperimentalRoborazziApi::class)
    private fun keep(bitmap: Bitmap, label: String) {
        val dir = frameDir ?: return
        dir.mkdirs()
        val list = transcriptBounds()
        val crop = Bitmap.createBitmap(bitmap, 0, list.top.toInt(), bitmap.width, (list.bottom - list.top).toInt().coerceAtLeast(1))
        val n = framesKept++
        File(dir, "frame-%03d.png".format(n)).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "labels.txt").appendText("%03d %s\n".format(n, label))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun screen(): Bitmap {
        val file = File.createTempFile("working-thinking-frame", ".png").apply { deleteOnExit() }
        captureScreenRoboImage(file.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        return checkNotNull(BitmapFactory.decodeFile(file.path)).also { file.delete() }
    }

    private fun ink(bitmap: Bitmap, area: Rect, lit: Int = LIT): Int {
        val left = area.left.toInt().coerceIn(0, bitmap.width)
        val right = area.right.toInt().coerceIn(left, bitmap.width)
        val top = area.top.toInt().coerceIn(0, bitmap.height)
        val bottom = area.bottom.toInt().coerceIn(top, bitmap.height)
        if (right <= left || bottom <= top) return 0
        val pixels = IntArray((right - left) * (bottom - top))
        bitmap.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
        return pixels.count { p -> maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) > lit }
    }

    private fun intersects(a: Rect, b: Rect): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    private fun intersection(a: Rect, b: Rect): Rect {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else Rect.Zero
    }

    @Test
    fun `the working caption and the thinking line never overlap while both are partly visible`() {
        open()
        compose.mainClock.autoAdvance = false
        compose.waitForIdle()

        runBlocking {
            streamer.emit(run2, RunStreamEvent.Thinking("Reading the module before changing anything.\n"))
        }

        val overlaps = mutableListOf<Int>()
        repeat(FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            val bitmap = screen()
            keep(bitmap, "frame-$it")
            val caption = captionBounds()
            val thinking = thinkingBounds()
            if (caption != null && thinking != null && intersects(caption, thinking)) {
                val capInk = ink(bitmap, caption)
                val thinkInk = ink(bitmap, thinking)
                val cross = ink(bitmap, intersection(caption, thinking))
                if (capInk >= MIN_INK && thinkInk >= MIN_INK && cross >= MIN_CROSS_INK) {
                    overlaps += it + 1
                }
            }
        }
        if (System.getenv("WORKING_THINKING_CAPTURE_ONLY") != "1") {
            assertWithMessage("frames with caption and thinking ink overlapping: $overlaps").that(overlaps).isEmpty()
        }

        compose.mainClock.advanceTimeBy(CaptionFadeMillis.toLong() + 64)
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNode(hasAnyAncestor(hasScrollToIndexAction()) and hasText("Thinking"), useUnmergedTree = true)
            }.isSuccess
        }
    }

    private companion object {
        const val FRAMES = 48
        const val LIT = 90
        const val MIN_INK = 80
        const val MIN_CROSS_INK = 40
    }
}
