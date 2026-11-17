package com.cursorforandroid.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.CursorApiException
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import android.Manifest
import android.app.Application
import com.cursorforandroid.data.api.Transcription
import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.RecordedClip
import com.cursorforandroid.data.media.Speaker
import com.cursorforandroid.ui.voice.VoiceCalls
import com.cursorforandroid.ui.voice.VoiceChat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos

/**
 * Recordings of the alpha's new surfaces on the real conversation screen over the fakes, drawn a frame at a time
 * (16 ms of the clock each), as DockDemoFramesTest draws its own: the call tray over the composer,
 * and a voice call from the waveform button to hang-up with a scripted microphone, transcription and voice. Every
 * frame goes to `REFRESH_DEMO_FRAMES/<scenario>/frame-NNN.png`; without that variable set, nothing runs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_DARK)
class RefreshDemoFramesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer(replay = 512)
    private val now = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()
    private val agentId = "bc-refresh-demo"
    private val live = "run-$TURNS"
    private lateinit var graph: AppGraph
    private val frameRoot = System.getenv("REFRESH_DEMO_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)

    @Before
    fun setUp() {
        assumeTrue("REFRESH_DEMO_FRAMES names no directory", frameRoot != null)
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        AppClock.nowMillis = System::currentTimeMillis
    }

    // --- the chat ---------------------------------------------------------------------------------------------------

    private fun read(turn: Int, n: Int) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "run-$turn-c$n",
            name = "read_file",
            status = "completed",
            args = buildJsonObject { put("path", JsonPrimitive("app/src/Turn${turn}File$n.kt")) },
            result = buildJsonObject { put("success", buildJsonObject { put("content", JsonPrimitive("val x = $n")); put("path", JsonPrimitive("app/src/Turn${turn}File$n.kt")) }) },
        ),
    )

    private fun setGoal(runId: String) = RunStreamEvent.ToolCall(
        SseToolCallDto(
            callId = "$runId-goal",
            name = "createGoal",
            status = "completed",
            args = buildJsonObject { put("objective", JsonPrimitive(GOAL)) },
            result = buildJsonObject { put("success", buildJsonObject { }) },
        ),
    )

    private fun prompt(turn: Int) = "Prompt $turn: tighten the ${ordinal(turn)} module and say what changed."

    private fun ordinal(n: Int) = when (n) { 1 -> "first"; 2 -> "second"; 3 -> "third"; else -> "${n}th" }

    private suspend fun finishedTurn(runId: String, turn: Int, reply: String) {
        streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
        streamer.emit(runId, RunStreamEvent.Thinking("Turn $turn: reading the module before changing it."))
        repeat(3) { n -> streamer.emit(runId, read(turn, n + 1)) }
        streamer.emit(runId, RunStreamEvent.Assistant(reply))
        streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, reply, turn * 60_000L, null))
        streamer.emit(runId, RunStreamEvent.Done)
    }

    /** [TURNS] turns; with [running] the newest still under way, having set the goal when [withGoal]. */
    private fun seed(running: Boolean, withGoal: Boolean) = runBlocking {
        val prompts = Array(TURNS) { Triple("run-${it + 1}", prompt(it + 1), "Reply ${it + 1}: $EARLIER") }
        api.addFinishedAgent(agentId, "Fee table checks", *prompts, firstRunAt = Instant.ofEpochMilli(now - TURNS * 3_600_000L).toString())
        val last = if (running) TURNS - 1 else TURNS
        for (turn in 1..last) finishedTurn("run-$turn", turn, "Reply $turn: $EARLIER")
        if (!running) return@runBlocking
        api.runs[live] = api.runs.getValue(live).copy(status = "RUNNING", result = null, durationMs = null)
        api.agents[agentId] = api.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = live, updatedAt = api.runs.getValue(live).createdAt)
        api.transcripts[agentId] = api.transcripts.getValue(agentId).dropLast(1)
        streamer.emit(live, RunStreamEvent.Status(live, RunStatus.RUNNING))
        if (withGoal) streamer.emit(live, setGoal(live))
        streamer.emit(live, RunStreamEvent.Thinking("Turn $TURNS: reading the module before answering."))
        repeat(4) { n -> streamer.emit(live, read(TURNS, n + 1)) }
    }

    private fun presentation() = ViewModelProvider(compose.activity)["conversation-$agentId", ConversationViewModel::class.java].presented.value

    private fun viewModel() = ViewModelProvider(compose.activity)["conversation-$agentId", ConversationViewModel::class.java]

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open(mode: ThemeMode, running: Boolean, withGoal: Boolean = false) {
        seed(running, withGoal)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fake = CursorBackend(api, streamer, isDemo = true)
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = fake, transcriptionApi = scriptedTranscription, voiceCallsOverride = scriptedCalls(), voiceInputOverride = true)
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        compose.waitUntil(120_000) { presentation().state.let { !it.isLoading && it.isStreaming == running && it.traceStatus.pending == 0 } }
        compose.waitUntil(60_000) { onScreen("Reply ${if (running) TURNS - 1 else TURNS}:") }
        if (withGoal) compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag("goal-strip"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun queue(vararg texts: String) {
        for (text in texts) {
            compose.runOnUiThread {
                viewModel().setDraft(text)
                viewModel().submit()
            }
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() || onScreen(text) }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    // --- frames -----------------------------------------------------------------------------------------------------

    private var scenario = ""
    private var framesKept = 0
    private var tap: Offset? = null
    private var tapFrames = 0

    private fun screen(): Bitmap {
        val file = File.createTempFile("refresh-demo-frame", ".png").apply { deleteOnExit() }
        captureScreenRoboImage(file.path, RoborazziOptions(taskType = RoborazziTaskType.Record))
        return checkNotNull(BitmapFactory.decodeFile(file.path)) { "no frame captured" }.also { file.delete() }
    }

    /** One frame: the compose clock and the main looper's (the view model's delays) on by 16 ms, then drawn and kept. */
    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS.toLong()))
        compose.waitForIdle()
        val dir = File(checkNotNull(frameRoot), scenario).apply { mkdirs() }
        val bitmap = screen().copy(Bitmap.Config.ARGB_8888, true)
        tap?.takeIf { tapFrames > 0 }?.let { at ->
            tapFrames--
            Canvas(bitmap).drawCircle(at.x, at.y, 58f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF })
        }
        File(dir, "frame-%03d.png".format(framesKept++)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun frames(count: Int) = repeat(count) { frame() }

    private fun begin(name: String) {
        scenario = name
        framesKept = 0
        File(checkNotNull(frameRoot), name).deleteRecursively()
        compose.mainClock.autoAdvance = false
    }

    private val transcript get() = compose.onNode(hasScrollToIndexAction())

    /**
     * A finger down at [start] (a fraction of the list's height from its top), moved [dyDp] down the screen (up when
     * negative) over [moveFrames] frames, eased in and out the way a hand moves, a frame drawn per move; held still
     * for [holdFrames] frames so nothing flings, then lifted.
     */
    private fun drag(start: Float, dyDp: Float, moveFrames: Int, holdFrames: Int, lift: Boolean = true) {
        val density = compose.activity.resources.displayMetrics.density
        val total = dyDp * density
        val sign = if (total < 0) -1f else 1f
        transcript.performTouchInput { down(Offset(centerX, top + height * start)); moveBy(Offset(0f, sign * (viewConfiguration.touchSlop + 1f))) }
        frame()
        var moved = 0f
        for (i in 1..moveFrames) {
            val to = total * (1f - cos(PI.toFloat() * i / moveFrames)) / 2f
            val step = to - moved
            moved = to
            transcript.performTouchInput { moveBy(Offset(0f, step)) }
            frame()
        }
        repeat(holdFrames) {
            transcript.performTouchInput { moveBy(Offset(0f, 0f)) }
            frame()
        }
        if (lift) transcript.performTouchInput { advanceEventTime(FRAME_MS.toLong()); up() }
    }

    private fun tapJump() {
        val node = compose.onNode(hasContentDescription("Scroll to latest"))
        tap = node.fetchSemanticsNode().boundsInRoot.center
        tapFrames = 10
        node.performClick()
    }

    // --- the scripted call ------------------------------------------------------------------------------------------

    /** What the call "hears", tick by tick: a beat of quiet, an utterance, then quiet for the pause that sends it. */
    private val micScript = ArrayDeque(List(8) { 0.05f } + List(22) { i -> 0.45f + 0.4f * ((i * 37) % 10) / 10f } + List(400) { 0.04f })

    private object Mic : AudioCapture {
        var script: ArrayDeque<Float> = ArrayDeque()
        override fun start() = Unit
        override fun level() = script.removeFirstOrNull() ?: 0.04f
        override fun stop(): RecordedClip = RecordedClip(ByteArray(64), "audio/webm", 2_400)
        override fun cancel() = Unit
    }

    private val scriptedTranscription = TranscriptionApi { _, _, _ -> Transcription(SPOKEN, 320) }

    /** The device's voice, reading for as long as the words would take, on the call's own clock. */
    private class Voice(private val scope: CoroutineScope) : Speaker {
        override fun speak(text: String, onDone: (Boolean) -> Unit) {
            scope.launch {
                delay(3_200)
                onDone(true)
            }
        }
        override fun stop() = Unit
        override fun release() = Unit
    }

    private fun scriptedCalls(): VoiceCalls {
        Mic.script = micScript
        return VoiceCalls(newChat = { scope, send ->
            VoiceChat(scope, scriptedTranscription, { Mic }, Voice(scope), send, clock = { compose.mainClock.currentTime }, errorMessage = { "Couldn't reach Cursor." })
        })
    }

    private fun tapOn(description: String, frames: Int = 10) {
        val node = compose.onNode(hasContentDescription(description), useUnmergedTree = true)
        tap = node.fetchSemanticsNode().boundsInRoot.center
        tapFrames = frames
        node.performClick()
    }

    // --- the voice call ---------------------------------------------------------------------------------------------

    /**
     * Cursor's waveform button in the empty composer, tapped: the call tray docks over the composer, hears an
     * utterance, sends it, waits on the agent's turn, reads the reply, listens again, is muted, unmuted and hung up.
     */
    private fun call(name: String, mode: ThemeMode) {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        open(mode, running = false)
        begin(name)
        frames(24)
        tapOn("Start voice chat")
        // Listening, the utterance, the pause that ends it, its transcription and the send.
        frames(170)
        // The agent's turn, streamed as any follow-up's would be.
        runBlocking {
            val runId = "run-followup-1"
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Thinking("Running the suite again."))
        }
        frames(60)
        runBlocking {
            val runId = "run-followup-1"
            streamer.emit(runId, read(TURNS + 1, 1))
            streamer.emit(runId, RunStreamEvent.Assistant(REPLY))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, REPLY, 40_000L, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
        // Read aloud, then listening again.
        frames(240)
        tapOn("Mute")
        frames(50)
        tapOn("Unmute")
        frames(40)
        tapOn("Hang up")
        frames(50)
    }

    @Test fun callPhoneDark() = call("call-phone-dark", ThemeMode.Dark)
    @Test @Config(qualifiers = PHONE_LIGHT) fun callPhoneLight() = call("call-phone-light", ThemeMode.Light)

    private companion object {
        const val TURNS = 8
        const val FRAME_MS = 16
        const val GOAL = "Every closed market's fees checked against the table, nightly"
        const val EARLIER = "Done. The module builds and its suite passes; the fee lookups now go through one table, " +
            "and the two call sites you named only moved a constant between them."
        const val SPOKEN = "Run the fee tests again and tell me which ones fail"
        const val REPLY = "All 412 fee tests pass. Two were flaky on the first run, so I ran them again on their own and they held."
    }
}

private const val PHONE_DARK = "w411dp-h914dp-night-420dpi"
private const val PHONE_LIGHT = "w411dp-h914dp-notnight-420dpi"
