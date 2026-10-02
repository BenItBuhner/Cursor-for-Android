package com.cursorforandroid.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.HttpException
import retrofit2.Response

/**
 * Bennett's running chat, the real screen over the fakes: its reply streaming at [TOKENS_PER_SECOND], the run list's
 * minute spent — refused as the phone refuses a held endpoint, a bare `429` naming the wait left ("Try again in
 * 51 s.") — and the reader pulls up past the newest message to catch up. The pull is answered, the reply streams on,
 * and nothing anywhere on screen says "Rate limited" (v0.4.31 slid a snackbar up over the composer).
 *
 * With `RATE_LIMIT_DEMO_FRAMES` naming a directory, every frame drawn is written there (`frame-NNN.png`, the finger
 * marked while it is down), which is what the before/after recording is made from.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PullRateLimitScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer(replay = 256)
    private val now = Instant.parse("2026-10-02T00:30:00Z").toEpochMilli()
    private val agentId = "bc-persian-war"
    private val live = "run-2"
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open() {
        runBlocking {
            api.addFinishedAgent(
                agentId,
                "Alexander Persian war video",
                Triple("run-1", "Outline a two-minute video on Alexander's war with Persia.", EARLIER),
                Triple(live, "Now write the narration, scene by scene.", ""),
                firstRunAt = Instant.ofEpochMilli(now - 3_600_000L).toString(),
            )
            api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
            api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
            api.transcripts[agentId] = api.transcripts.getValue(agentId).dropLast(1)
            streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
            streamer.emit("run-1", RunStreamEvent.Assistant(EARLIER))
            streamer.emit("run-1", RunStreamEvent.Result("run-1", RunStatus.FINISHED, EARLIER, 60_000L, null))
            streamer.emit("run-1", RunStreamEvent.Done)
            streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = CursorBackend(api, streamer, isDemo = true))
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        compose.waitUntil(120_000) { presentation().state.let { !it.isLoading && it.isStreaming && it.traceStatus.pending == 0 } }
        compose.waitForIdle()
    }

    private fun presentation() = ViewModelProvider(compose.activity)["conversation-$agentId", ConversationViewModel::class.java].presented.value

    private fun presentedReply(): String = presentation().items.filterIsInstance<AssistantMessage>().lastOrNull()?.markdown.orEmpty()

    // --- the stream ---------------------------------------------------------------------------------------------------

    private val tokens = REPLY.chunked(4)
    private var sent = 0
    private var streamedMs = 0

    private fun streamFrame() {
        streamedMs += FRAME_MS
        val next = (streamedMs * TOKENS_PER_SECOND / 1_000).coerceAtMost(tokens.size)
        if (next == sent) return
        runBlocking { streamer.emit(live, RunStreamEvent.Assistant(tokens.subList(sent, next).joinToString(""))) }
        sent = next
        val text = tokens.take(sent).joinToString("")
        compose.waitUntil(20_000) { presentedReply().endsWith(text.takeLast(24)) }
    }

    // --- frames -------------------------------------------------------------------------------------------------------

    private val frameDir = System.getenv("RATE_LIMIT_DEMO_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)?.also { it.mkdirs() }
    private var framesKept = 0
    private var finger: Offset? = null
    private var cardFrames = 0

    private fun rateLimitShown(): Boolean = compose.onAllNodesWithText("Rate limited", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun frame() {
        streamFrame()
        compose.mainClock.advanceTimeByFrame()
        if (rateLimitShown()) cardFrames++
        val dir = frameDir ?: return
        val file = File.createTempFile("rate-limit-frame", ".png").apply { deleteOnExit() }
        captureScreenRoboImage(file.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)).copy(Bitmap.Config.ARGB_8888, true).also { file.delete() }
        finger?.let { at -> Canvas(bitmap).drawCircle(at.x, at.y, 58f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }) }
        File(dir, "frame-%03d.png".format(framesKept++)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The reader's pull up past the newest message, a frame at a time: [PULL_FRAMES] of travel well past the threshold, then let go. */
    private fun pullUp() {
        val list = compose.onNode(hasScrollToIndexAction())
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val start = Offset(bounds.width / 2f, bounds.height * 0.75f)
        val step = PULL_TRAVEL_PX / PULL_FRAMES
        list.performTouchInput { down(start) }
        finger = bounds.topLeft + start
        repeat(PULL_FRAMES) {
            list.performTouchInput { moveBy(Offset(0f, -step)) }
            finger = finger!! + Offset(0f, -step)
            frame()
        }
        list.performTouchInput { up() }
        finger = null
    }

    @Test
    fun `a pull on a streaming chat whose run list is refused says nothing of a rate limit, and the reply streams on`() {
        open()
        compose.mainClock.autoAdvance = false
        repeat(HOLD_FRAMES) { frame() }
        // The run list's minute spent by the chats beside this one: refused on the phone, the wait left named.
        api.failListRuns = phoneRefusal(waitSeconds = 51)
        val pullsBefore = api.listRunsCalls
        pullUp()
        val sentAtRelease = sent
        repeat(AFTER_FRAMES) { frame() }
        assertWithMessage("the pull read the run list").that(api.listRunsCalls).isGreaterThan(pullsBefore)
        assertWithMessage("frames that said \"Rate limited\"").that(cardFrames).isEqualTo(0)
        assertThat(sent).isGreaterThan(sentAtRelease)
        assertThat(presentation().state.isStreaming).isTrue()
        assertThat(presentation().state.transcriptError).isNull()
    }

    /** What `RetryInterceptor` answers a read of an endpoint still inside its pause: a bare `429` naming the wait left. */
    private fun phoneRefusal(waitSeconds: Int): HttpException {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://api.cursor.com/v1/agents/$agentId/runs").build())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .header("Retry-After", waitSeconds.toString())
            .build()
        return HttpException(Response.error<Any>("".toResponseBody(null), raw))
    }

    private companion object {
        const val FRAME_MS = 16
        const val TOKENS_PER_SECOND = 100
        const val HOLD_FRAMES = 45
        const val PULL_FRAMES = 30
        /** 340 dp at 420 dpi: well past the pull's 200 dp threshold under its rubber band. */
        const val PULL_TRAVEL_PX = 900f
        /** Long enough for a snackbar's four seconds to come and go. */
        const val AFTER_FRAMES = 300
        const val EARLIER = "Here is a two-minute outline in five scenes: the crossing of the Hellespont, the Granicus, Issus, " +
            "Gaugamela, and Persepolis burning. Each scene gets about twenty seconds, with a map beat between them."
        val REPLY = """
            Scene one, the Hellespont, 334 BC. Open on the strait at dawn. Alexander leaps from the first ship and drives a spear into the Asian shore: this land, he says, is won by the spear.

            Scene two, the Granicus. The Persian satraps hold the far bank. Alexander leads the Companion cavalry straight into the river and up the slope; a Persian blade splits his helmet before Cleitus cuts the attacker down.

            Scene three, Issus. Darius has cut behind the Macedonian army. On the narrow plain between the mountains and the sea, the phalanx holds while Alexander's charge breaks the Persian left and Darius flees the field, leaving his family behind.

            Scene four, Gaugamela. Darius chooses flat ground for his scythed chariots and his numbers. Alexander drifts right, draws the Persian line apart, and drives a wedge into the gap. Darius flees again.
        """.trimIndent()
    }
}
