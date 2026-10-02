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
import kotlinx.coroutines.CompletableDeferred
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
 * The demo recordings of the jump button docked over the composer and of the pull to catch up, drawn a frame at a
 * time (16 ms of the clock each) on the real conversation screen over the fakes, on a phone and a tablet, dark and
 * light. Every frame is written to `DOCK_DEMO_FRAMES/<scenario>/frame-NNN.png`, a tap marked on the frames it lasts;
 * without that variable set, nothing runs. It names nothing the screen did not have before the jump button docked,
 * so the same scenarios draw the screen before the change.
 *
 * - `pill-*`: a running chat with nothing docked, a queued follow-up, or the queue under the goal: scrolled up by a
 *   finger, held, the jump button tapped, the list gliding home.
 * - `pull-*`: a finished chat pulled up past its newest message and let go, the indicator spinning while the run list
 *   is held back, then a turn started elsewhere arriving with the answer; `pull-failed-*` the run list failing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_DARK)
class DockDemoFramesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer(replay = 512)
    private val now = Instant.parse("2026-10-02T12:00:00Z").toEpochMilli()
    private val agentId = "bc-dock-demo"
    private val live = "run-$TURNS"
    private lateinit var graph: AppGraph
    private val frameRoot = System.getenv("DOCK_DEMO_FRAMES")?.takeIf { it.isNotBlank() }?.let(::File)

    @Before
    fun setUp() {
        assumeTrue("DOCK_DEMO_FRAMES names no directory", frameRoot != null)
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
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = fake)
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
        val file = File.createTempFile("dock-demo-frame", ".png").apply { deleteOnExit() }
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

    // --- the jump button --------------------------------------------------------------------------------------------

    private fun pill(name: String, mode: ThemeMode, docked: Docked) {
        open(mode, running = true, withGoal = docked == Docked.QueueAndGoal)
        when (docked) {
            Docked.Nothing -> Unit
            Docked.Queue -> queue("Then add a test for the light theme")
            Docked.QueueAndGoal -> queue("Then add a test for the light theme", "And open a draft PR once it is green")
        }
        begin(name)
        frames(20)
        drag(start = 0.35f, dyDp = 360f, moveFrames = 30, holdFrames = 6)
        frames(40)
        tapJump()
        frames(70)
    }

    @Test fun pillPhoneDarkNothing() = pill("pill-phone-dark-nothing", ThemeMode.Dark, Docked.Nothing)
    @Test fun pillPhoneDarkQueue() = pill("pill-phone-dark-queue", ThemeMode.Dark, Docked.Queue)
    @Test fun pillPhoneDarkQueueGoal() = pill("pill-phone-dark-queue-goal", ThemeMode.Dark, Docked.QueueAndGoal)

    @Test @Config(qualifiers = PHONE_LIGHT) fun pillPhoneLightNothing() = pill("pill-phone-light-nothing", ThemeMode.Light, Docked.Nothing)
    @Test @Config(qualifiers = PHONE_LIGHT) fun pillPhoneLightQueue() = pill("pill-phone-light-queue", ThemeMode.Light, Docked.Queue)
    @Test @Config(qualifiers = PHONE_LIGHT) fun pillPhoneLightQueueGoal() = pill("pill-phone-light-queue-goal", ThemeMode.Light, Docked.QueueAndGoal)

    @Test @Config(qualifiers = TABLET_DARK) fun pillTabletDarkNothing() = pill("pill-tablet-dark-nothing", ThemeMode.Dark, Docked.Nothing)
    @Test @Config(qualifiers = TABLET_DARK) fun pillTabletDarkQueue() = pill("pill-tablet-dark-queue", ThemeMode.Dark, Docked.Queue)
    @Test @Config(qualifiers = TABLET_DARK) fun pillTabletDarkQueueGoal() = pill("pill-tablet-dark-queue-goal", ThemeMode.Dark, Docked.QueueAndGoal)

    @Test @Config(qualifiers = TABLET_LIGHT) fun pillTabletLightNothing() = pill("pill-tablet-light-nothing", ThemeMode.Light, Docked.Nothing)
    @Test @Config(qualifiers = TABLET_LIGHT) fun pillTabletLightQueue() = pill("pill-tablet-light-queue", ThemeMode.Light, Docked.Queue)
    @Test @Config(qualifiers = TABLET_LIGHT) fun pillTabletLightQueueGoal() = pill("pill-tablet-light-queue-goal", ThemeMode.Light, Docked.QueueAndGoal)

    // --- the pull to catch up ---------------------------------------------------------------------------------------

    /** A turn started on the desktop while this chat sat open: on the server, and in the stream, but not on screen. */
    private fun turnElsewhere() = runBlocking {
        val runId = "run-elsewhere"
        val iso = Instant.ofEpochMilli(now - 60_000L).toString()
        api.runs[runId] = RunDto(id = runId, agentId = agentId, status = "FINISHED", createdAt = iso, updatedAt = iso, durationMs = 42_000L, result = ELSEWHERE_REPLY)
        api.transcripts[agentId] = api.transcripts.getValue(agentId) + listOf(
            V0ConversationMessageDto("$runId-u", "user_message", ELSEWHERE_PROMPT),
            V0ConversationMessageDto("$runId-a", "assistant_message", ELSEWHERE_REPLY),
        )
        api.agents[agentId] = api.agents.getValue(agentId).copy(updatedAt = iso, latestRunId = runId)
        finishedTurn(runId, TURNS + 1, ELSEWHERE_REPLY)
    }

    private fun pull(name: String, mode: ThemeMode, failing: Boolean = false) {
        open(mode, running = false)
        val gate = CompletableDeferred<Unit>()
        if (failing) api.failListRuns = CursorApiException(503, "unavailable", "Cursor is unavailable right now.") else api.runsGate = gate
        begin(name)
        frames(16)
        drag(start = 0.8f, dyDp = -300f, moveFrames = 34, holdFrames = 8)
        if (!failing) {
            frames(44)
            turnElsewhere()
            gate.complete(Unit)
            // The catch-up reads off the main thread: drawn on once what it brought is on screen, as a phone would.
            compose.waitUntil(30_000) { presentation().items.any { it is UserMessage && it.text.startsWith("From the desktop") } }
        }
        frames(170)
    }

    @Test fun pullPhoneDark() = pull("pull-phone-dark", ThemeMode.Dark)
    @Test @Config(qualifiers = PHONE_LIGHT) fun pullPhoneLight() = pull("pull-phone-light", ThemeMode.Light)
    @Test @Config(qualifiers = TABLET_DARK) fun pullTabletDark() = pull("pull-tablet-dark", ThemeMode.Dark)
    @Test @Config(qualifiers = TABLET_LIGHT) fun pullTabletLight() = pull("pull-tablet-light", ThemeMode.Light)
    @Test fun pullFailedPhoneDark() = pull("pull-failed-phone-dark", ThemeMode.Dark, failing = true)

    private enum class Docked { Nothing, Queue, QueueAndGoal }

    private companion object {
        const val TURNS = 8
        const val FRAME_MS = 16
        const val GOAL = "Every closed market's fees checked against the table, nightly"
        const val EARLIER = "Done. The module builds and its suite passes; the fee lookups now go through one table, " +
            "and the two call sites you named only moved a constant between them."
        const val ELSEWHERE_PROMPT = "From the desktop: check the fee table for market 200 too, then report back."
        const val ELSEWHERE_REPLY = "Market 200 matches the table: 2.0% maker, 2.4% taker, both charged to the right side in all 318 fills of the last hour."
    }
}

private const val PHONE_DARK = "w411dp-h914dp-night-420dpi"
private const val PHONE_LIGHT = "w411dp-h914dp-notnight-420dpi"
private const val TABLET_DARK = "w1000dp-h720dp-night-320dpi"
private const val TABLET_LIGHT = "w1000dp-h720dp-notnight-320dpi"
