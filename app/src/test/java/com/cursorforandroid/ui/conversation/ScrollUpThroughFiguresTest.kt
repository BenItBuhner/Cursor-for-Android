package com.cursorforandroid.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Bennett's recording of 2026-10-01: flung up through a conversation of figures, the transcript skips a screenful
 * at a time, more or less of one depending on the fling. A reply's figure is a 140 dp placeholder until it is
 * decoded, and a reply entering from the top is the row the list holds by its top edge; the figure landing while the
 * reply straddles that edge made the reply taller under the reader, and everything below it moved by the difference.
 *
 * On the real screen over the fakes, with every figure held on the wire until the test lets it land: a fling up
 * through [TURNS] replies, each led by a figure of its own size, with a figure landing in the row crossing the top
 * edge, slows down frame by frame and never jumps; and a second pass, the figures decoded once, lays each out at its
 * own size before its pixels arrive.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ScrollUpThroughFiguresTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val agentId = "bc-figures"
    private val server = MockWebServer()
    private lateinit var graph: AppGraph

    /** Each figure's PNG, by turn; (width, height) in source pixels, one per dp when laid out, so each row grows differently. */
    private val figures = (1..TURNS).associateWith { n -> png(FIGURE_SIZES[(n - 1) % FIGURE_SIZES.size], n) }
    private val gates = ConcurrentHashMap<Int, CountDownLatch>()
    private val served: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var holding = true

    @After
    fun tearDown() {
        holding = false
        gates.values.forEach { it.countDown() }
        server.shutdown()
    }

    private fun png(size: Pair<Int, Int>, n: Int): ByteArray {
        val (w, h) = size
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(AndroidColor.rgb(0x1E + n * 9, 0x2A, 0x3A))
        val paint = Paint().apply { isAntiAlias = true; color = AndroidColor.rgb(0xF5, 0x8A, 0x3E) }
        canvas.drawCircle(w / 3f, h / 2f, minOf(w, h) / 4f, paint)
        return Buffer().also { buffer -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer.outputStream()) }.readByteArray()
    }

    private fun gate(n: Int) = gates.getOrPut(n) { CountDownLatch(1) }

    /** Figure [n] is let through onto the wire. */
    private fun release(n: Int) = gate(n).countDown()

    /**
     * Figure [n], let through, is fetched and decoded off the main thread in wall-clock time, which the frame clock
     * knows nothing of: waits for the bytes to have gone over the wire and the decoded answer to be queued for the
     * main thread, so the next frame delivers it.
     */
    private fun awaitDecoded(n: Int) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (n !in served && System.nanoTime() < deadline) Thread.sleep(5)
        val looper = shadowOf(Looper.getMainLooper())
        while (looper.isIdle && System.nanoTime() < deadline) Thread.sleep(5)
        Thread.sleep(50)
    }

    private fun figureUrl(n: Int) = server.url("/fig/$n.png").toString()

    private fun reply(n: Int) = buildString {
        append("![Figure $n](${figureUrl(n)})\n\n")
        repeat(PARAGRAPHS) { p -> append("Reply $n paragraph ${p + 1}: what the figure shows, read against the module it came from, and what was changed because of it across the screens that share the row.\n\n") }
    }

    private fun seed() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = request.path.orEmpty().removePrefix("/fig/").removeSuffix(".png").toIntOrNull() ?: return MockResponse().setResponseCode(404)
                val bytes = figures[n] ?: return MockResponse().setResponseCode(404)
                if (holding) gate(n).await(60, TimeUnit.SECONDS)
                served += n
                return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))
            }
        }
        server.start()
        val prompts = Array(TURNS) { Triple("run-${it + 1}", "Prompt ${it + 1}: show me the screen and say what changed.", reply(it + 1)) }
        api.addFinishedAgent(agentId, "Figures", *prompts)
        // Each finished run's trace, whole, so the screen has nothing left to fetch once it is up.
        for ((runId, _, reply) in prompts) {
            streamer.emit(runId, RunStreamEvent.Status(runId, RunStatus.RUNNING))
            streamer.emit(runId, RunStreamEvent.Assistant(reply))
            streamer.emit(runId, RunStreamEvent.Result(runId, RunStatus.FINISHED, reply, 60_000L, null))
            streamer.emit(runId, RunStreamEvent.Done)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun open() {
        seed()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fake = CursorBackend(api, streamer, isDemo = true)
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = fake)
        runBlocking { graph.session.enterDemo() }
        runBlocking { graph.agents.refresh() }
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    ConversationScreen(graph, agentId, onBack = {})
                }
            }
        }
        compose.waitUntil(60_000) { graph.conversations.state(agentId).value.let { !it.isLoading && it.traceStatus.pending == 0 } }
        compose.waitUntil(20_000) { texts().any { it.text().startsWith("Reply $TURNS paragraph $PARAGRAPHS") } }
        compose.waitForIdle()
    }

    // --- what is on screen ------------------------------------------------------------------------------------------

    private val transcript: SemanticsMatcher get() = hasScrollToIndexAction()

    private fun listBounds(): Rect = compose.onNode(transcript).fetchSemanticsNode().boundsInRoot

    private fun SemanticsNode.text(): String = config[SemanticsProperties.Text].joinToString(" ")

    private fun texts(): List<SemanticsNode> =
        compose.onAllNodes(hasAnyAncestor(transcript) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()

    private fun described(description: String, substring: Boolean = false): List<SemanticsNode> =
        compose.onAllNodes(hasAnyAncestor(transcript) and hasContentDescription(description, substring = substring), useUnmergedTree = true).fetchSemanticsNodes()

    /** The transcript's text nodes wholly inside the viewport, by node id with their tops. */
    private fun textTops(): Map<Int, Float> {
        val list = listBounds()
        return texts().filter { it.boundsInRoot.top >= list.top && it.boundsInRoot.bottom <= list.bottom }.associate { it.id to it.positionInRoot.y }
    }

    private fun following(): Boolean = compose.onAllNodes(hasContentDescription("Scroll to latest")).fetchSemanticsNodes().isEmpty()

    /** One frame: what landed on the main queue delivered, the frame clock stepped, the harness idle. */
    private fun frame() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** Back to the newest row by the jump button — a finger flung into the bottom edge would pull to catch up, and the pull's spring home lifts the list for frames. */
    private fun jumpToNewest() {
        compose.mainClock.autoAdvance = true
        // decodeEveryFigure walks the list and can already be on the newest row; the jump chip is then gone.
        if (!following()) {
            compose.onNode(hasContentDescription("Scroll to latest")).performClick()
            compose.waitForIdle()
            compose.waitUntil(10_000) { following() }
            compose.waitForIdle()
        }
        // Every figure on screen decoded and the rows at rest before the next fling.
        compose.mainClock.autoAdvance = false
        var still = 0
        var before = textTops()
        for (n in 1..FRAMES) {
            frame()
            val now = textTops()
            still = if (now == before && described("Loading image").isEmpty()) still + 1 else 0
            before = now
            if (still >= 5) break
        }
        assertWithMessage("the transcript came to rest at the bottom").that(still).isAtLeast(5)
    }

    /**
     * Walks the transcript so every reply's figure is composed long enough to decode. Lazy rows cancel an in-flight
     * fetch when they leave the viewport, and [jumpToNewest] only waits for the bottom of the list: a gentle fling
     * then meets never-seen 140 dp placeholders further up. Those round to 368 px at 420 dpi, while
     * `(140 * density).toInt()` is 367, so the second pass treated them as known sizes and failed the match.
     */
    private fun decodeEveryFigure(): Map<Int, Int> {
        compose.mainClock.autoAdvance = true
        val heights = mutableMapOf<Int, Int>()
        fun capture() {
            described("Figure ", substring = true).forEach { node ->
                val n = node.config[SemanticsProperties.ContentDescription].single().removePrefix("Figure ").toInt()
                heights[n] = node.size.height
            }
        }
        for (index in 0 until ITEM_WALK) {
            capture()
            if ((1..TURNS).all { it in heights }) break
            runCatching { compose.onNode(transcript).performScrollToIndex(index) }
            compose.waitForIdle()
            // Hold the row on screen until its figure lands; scrolling on would cancel the fetch.
            compose.waitUntil(15_000) {
                shadowOf(Looper.getMainLooper()).idle()
                capture()
                described("Loading image").isEmpty()
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        assertWithMessage("every figure decoded once before the second pass; heights $heights served $served")
            .that(heights.keys)
            .containsAtLeastElementsIn((1..TURNS).toList())
        return heights
    }

    private class Pass(val moves: List<Float>, val landedAcrossTop: Int, val placeholderHeights: Set<Int>, val figureHeights: Map<Int, Int>)

    /**
     * A finger drawn down the list in [durationMillis] (the faster, the harder the fling), then the fling drawn frame
     * by frame until it is over. Each frame's move is how far the text wholly on screen in both frames travelled;
     * [releasing], the figure of the row crossing the top edge is let onto the wire when that row is well across it,
     * and the frame it lands in is counted when the row is still the one across the top.
     */
    private fun flingUp(durationMillis: Long, releasing: Boolean): Pass {
        compose.mainClock.autoAdvance = false
        compose.onNode(transcript).performTouchInput { swipe(Offset(left + 80f, top + 100f), Offset(left + 80f, top + 100f + SWIPE_PX), durationMillis = durationMillis) }
        val moves = mutableListOf<Float>()
        var before = textTops()
        var released: Int? = null
        val acrossTop = mutableSetOf<Int>()
        val placeholderHeights = mutableSetOf<Int>()
        val figureHeights = mutableMapOf<Int, Int>()
        var seenFigures = emptySet<Int>()
        for (n in 1..FRAMES) {
            frame()
            val list = listBounds()
            val now = textTops()
            val common = before.keys.intersect(now.keys)
            // The frames of the finger's own drag are not the fling's.
            if (common.isNotEmpty() && n > durationMillis / 16 + 2) moves += common.map { now.getValue(it) - before.getValue(it) }.sorted()[common.size / 2]
            before = now

            described("Loading image").forEach { placeholderHeights += it.size.height }
            val figures = described("Figure ", substring = true).associateBy { it.config[SemanticsProperties.ContentDescription].single().removePrefix("Figure ").toInt() }
            for ((figure, node) in figures) {
                figureHeights[figure] = node.size.height
                // Decode finishes a frame or more after the placeholder was let through; counting only the first
                // frame the node exists missed a gentle fling whose figure was already on screen that frame and
                // crossed the top on the next. positionInRoot, not the clipped bounds: those never sit above the list.
                if (node.positionInRoot.y < list.top) acrossTop += figure
            }
            seenFigures = figures.keys

            if (releasing && released == null && moves.size > 1) {
                // The placeholder leads its reply: above the top edge with the reply's text still on screen, the
                // reply is the row the list holds by its top.
                val all = texts()
                for (across in described("Loading image").filter { it.positionInRoot.y < list.top - RELEASE_ABOVE_PX }) {
                    val figure = all.filter { it.positionInRoot.y > across.positionInRoot.y }.minByOrNull { it.positionInRoot.y }
                        ?.text()?.removePrefix("Reply ")?.substringBefore(' ')?.toIntOrNull() ?: continue
                    if (all.none { it.text().startsWith("Reply $figure paragraph") && it.boundsInRoot.bottom > list.top }) continue
                    release(figure)
                    released = figure
                    awaitDecoded(figure)
                    break
                }
            }
            // Over once the fling has stopped and the figure let through has landed (at rest, it must land without a move either).
            if (moves.size > 10 && moves.takeLast(2).all { it == 0f } && (released == null || released in seenFigures)) break
        }
        if (releasing) assertWithMessage("a figure was let onto the wire while its reply crossed the top edge").that(released).isNotNull()
        return Pass(moves, acrossTop.size, placeholderHeights, figureHeights)
    }

    /** A fling only slows, and slowly: a frame moving the text further than the frame before, or much less (a jump back up), is a jump. */
    private fun assertNoJump(pass: Pass, what: String) {
        assertWithMessage("$what: the fling ran on; moves ${pass.moves}").that(pass.moves.size).isGreaterThan(8)
        pass.moves.zipWithNext().forEachIndexed { i, (before, after) ->
            assertWithMessage("$what: frame ${i + 2} moved $after px after $before px; moves: ${pass.moves}").that(after).isAtMost(before + 2f)
            assertWithMessage("$what: frame ${i + 2} moved $after px after $before px; moves: ${pass.moves}").that(after).isAtLeast(before - 10f)
        }
    }

    private fun flingUpAndAssert(durationMillis: Long) {
        open()
        assertThat(following()).isTrue()

        val first = flingUp(durationMillis, releasing = true)
        assertThat(following()).isFalse()
        // The placeholder was across the top when the figure was let through (see flingUp). Decode can land in a
        // later frame that already sits fully in the viewport, so the decoded node's y is not required to start
        // above the list; the jump check is what that landing must not do.
        assertNoJump(first, "first pass, a figure landing across the top edge")

        // Every figure let through and decoded once; back at the bottom, then up again with nothing held: each
        // reply's placeholder stands at its figure's own height, so the figures landing change no row's height.
        holding = false
        gates.values.forEach { it.countDown() }
        val decoded = decodeEveryFigure()
        jumpToNewest()
        val second = flingUp(durationMillis, releasing = false)
        assertNoJump(second, "second pass, the figures' sizes known")
        val unknown = (140f * compose.density.density).roundToInt()
        val known = decoded + second.figureHeights + first.figureHeights
        assertWithMessage("placeholders of the second pass stand at their figures' heights, not the 140 dp of a figure never seen; figures $known")
            .that(second.placeholderHeights).isNotEmpty()
        for (height in second.placeholderHeights) {
            assertWithMessage("a placeholder of $height px among figures $known, unknown=$unknown").that(height).isNotEqualTo(unknown)
            assertWithMessage("a placeholder of $height px among figures $known")
                .that(known.values.any { abs(it - height) <= 2 })
                .isTrue()
        }
    }

    @Test
    fun `a hard fling up through the figures never jumps, with a figure landing in the row across the top edge`() = flingUpAndAssert(durationMillis = 80)

    @Test
    fun `a gentle fling up through the figures never jumps, with a figure landing in the row across the top edge`() = flingUpAndAssert(durationMillis = 260)

    private companion object {
        const val TURNS = 12
        const val PARAGRAPHS = 7
        const val FRAMES = 240
        const val SWIPE_PX = 600f
        /** Prompt + reply per turn, plus the list's edge rows: enough indices to walk the whole transcript. */
        const val ITEM_WALK = 40
        /** How far above the top edge a reply's placeholder is, at least, when its figure is let through: the reply across the edge for frames yet. */
        const val RELEASE_ABOVE_PX = 150f
        /** Source sizes, one px per dp: 180 dp tall, 411 dp (the screen's cap), 95 dp (width-limited), 300 dp, 250 dp — none the 140 dp of a placeholder. */
        val FIGURE_SIZES = listOf(320 to 180, 240 to 420, 640 to 160, 300 to 300, 500 to 250)
    }
}
