package com.cursorforandroid.promo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.CursorApp
import com.cursorforandroid.DeferredStartup
import com.cursorforandroid.MainActivity
import com.cursorforandroid.data.demo.DemoStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.ReviewRepository
import com.cursorforandroid.data.repo.SessionManager
import com.cursorforandroid.data.repo.SessionState
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.ui.conversation.QueueGlyphs
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** [Screen.Phone], [Screen.Foldable] and [Screen.Tablet] as `@Config` needs them, as constants; [stage] checks they still agree. */
private const val PHONE = "w411dp-h923dp-port-night-420dpi"
private const val FOLDABLE = "w791dp-h820dp-port-night-420dpi"
private const val TABLET = "w1280dp-h800dp-land-night-320dpi"

/**
 * The launch video's footage: the app itself, signed in to the capture's account with its scripted backend behind
 * it, driven a frame at a time by a [Director] and filmed to `promo/capture/out`. Each device's take is one
 * continuous take of the same run (see [take]), each step at the same moment of the capture's clock, so the video
 * can cut between the devices and set them side by side at any moment of it.
 *
 * Run through `promo/capture/run.sh <test>`: the harness is not part of the app's build.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class LaunchVideoCapture {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: CursorApp = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null
    private var sink: FrameSink? = null
    private var director: Director? = null
    private lateinit var graph: AppGraph
    private lateinit var streamer: PromoRunStreamer

    private val clock = AppClock.nowMillis
    private val settle = DeferredStartup.settleMs
    private val zone = TimeZone.getDefault()
    private val locale = Locale.getDefault()

    @Before
    fun stage() {
        check(Screen.Phone.qualifiers == PHONE && Screen.Foldable.qualifiers == FOLDABLE && Screen.Tablet.qualifiers == TABLET) {
            "The @Config qualifiers no longer match the screens"
        }
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        VirtualTime.reset()
        PacingLog.reset()
        AppClock.nowMillis = VirtualTime::wallMillis
        // The notification channels, catalog refresh and background sync it starts are nothing the takes show.
        DeferredStartup.settleMs = Long.MAX_VALUE
    }

    @After
    fun strike() {
        director?.printTimings()
        runCatching { director?.endSegment() }
        runCatching { sink?.close() }
        runCatching { controller?.pause()?.stop()?.destroy() }
        shadowOf(Looper.getMainLooper()).idle()
        AppClock.nowMillis = clock
        DeferredStartup.settleMs = settle
        TimeZone.setDefault(zone)
        Locale.setDefault(locale)
    }

    /**
     * The app launched on [screen] as someone who has been using it for a while: their week of chats on the Cesium
     * repository, two of them pinned, and the repository and model chosen in the composer; nothing new to read, the
     * notification question answered. The account is the demo's, which the app runs as it runs any other, under the
     * capture's name for its owner rather than the demo's.
     */
    private fun launch(screen: Screen, name: String): Director {
        val store = DemoStore(PromoSeeds.seeds)
        val script = PromoScript.load()
        val steering = PromoSteering(store)
        val review = PromoReview(script)
        streamer = PromoRunStreamer(store, script, steering, review)
        val backend = CursorBackend(PromoCursorApi(store), streamer, isDemo = true)
        graph = AppGraph(app, SecureKeyStore(app) { app.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, demo = backend)
        CursorApp::class.java.getDeclaredField("graph").apply { isAccessible = true }.set(app, graph)
        ReviewRepository::class.java.getDeclaredField("demo").apply { isAccessible = true }.set(graph.reviews, review)
        PromoSeeds.install(graph)
        steering.install(graph.followUps)
        steering.queueRead = { agentId -> graph.conversations.noteAccountQueue(agentId, emptyList()) }
        steering.placed = { agentId -> graph.followUps.state(agentId).value.queue.isEmpty() }
        runBlocking {
            graph.prefs.setComposerDefaults(repoUrl = CESIUM_REPO, ref = "main", modelId = "composer-2.5", params = mapOf("fast" to "true"))
            graph.prefs.setWhatsNewReadVersion(graph.appVersion)
            graph.prefs.setNotificationPermissionAsked()
            // Before the demo is entered, which pins its own showcase chats only while nothing is pinned.
            graph.prefs.pinIfNonePinned(PromoSeeds.pinned)
            graph.session.enterDemo()
            // The same demo backend under it, which is what the app asks about; only the account's owner is someone else.
            @Suppress("UNCHECKED_CAST")
            val session = SessionManager::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(graph.session) as MutableStateFlow<SessionState>
            session.value = SessionState.SignedIn(USER, isDemo = false)
            graph.onboarding.load()
        }
        val launched = Robolectric.buildActivity(MainActivity::class.java).setup().also { controller = it }
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(HOME_PLACEHOLDER, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val frames = FrameSink(File(Promo.out, name), Promo.preview).also { sink = it }
        return Director(compose, launched, frames, screen).also { director = it }
    }

    private fun finish(name: String) {
        director?.endSegment()
        PacingLog.write(File(Promo.out, "$name/pacing.json"))
        PacingLog.check()
    }

    /** The phone's take at half size, with the screen's semantics and a still at each step, going on past a step that finds nothing to do. */
    @Test
    fun probe() {
        val d = launch(Screen.Phone, "probe")
        d.segment("probe", 0.5f)
        Take(d, "probe", probing = true).play()
        finish("probe")
    }

    /** Pixel 9: the video's spine. */
    @Test
    fun phone() {
        val d = launch(Screen.Phone, "phone")
        d.segment("phone", 1f)
        Take(d, "phone").play()
        finish("phone")
    }

    /** Pixel 9 Pro Fold, open: the chat with its details pinned beside it. */
    @Test
    @Config(qualifiers = FOLDABLE)
    fun foldable() {
        val d = launch(Screen.Foldable, "foldable")
        d.segment("foldable", 0.75f)
        Take(d, "foldable").play()
        finish("foldable")
    }

    /** Pixel Tablet on its side: the sidebar, the chat and its details. */
    @Test
    @Config(qualifiers = TABLET)
    fun tablet() {
        val d = launch(Screen.Tablet, "tablet")
        d.segment("tablet", 0.75f)
        Take(d, "tablet").play()
        finish("tablet")
    }

    /**
     * One take of the run on [d]'s screen, each step at the same moment after the send on every device: the task typed
     * into the home screen's composer and sent; the stretch of edits opened as they land, and the first one's diff; a
     * follow-up typed while the agent works, queued behind the turn and steered into it, the transcript caught up to
     * see it land; and once the run has opened its pull request and answered, the pull request's section of the
     * details. A wide window pins the details beside the chat as soon as it opens, where the edits come in as they
     * are made; a phone opens them at the end. With [probing], every step is looked at (semantics, a still and the
     * moment it came, in the log), and one that finds nothing to do is noted and passed over.
     */
    private inner class Take(private val d: Director, private val name: String, private val probing: Boolean = false) {
        private var sent = 0L
        private val wide = d.screen.widthDp >= WIDE_DP

        fun play() {
            try {
                d.hold(1.0)
                look("home")
                val field = hasSetTextAction()
                tap("composer", field)
                d.hold(0.3)
                d.type(d.node(field), HERO_PROMPT)
                d.hold(0.45)
                look("typed")
                tap("send", hasTestTag("composer-main"))
                sent = VirtualTime.nowMs
                until("the chat", 10.0) { d.exists(hasTestTag("chat-header")) }
                if (wide) {
                    at(PANEL_AT)
                    tap("panel", hasContentDescription("Open panel"))
                }
                at(THINKING_AT)
                look("thinking")

                at(EDITS_AT)
                until("the edits", 3.0) { d.exists(WORKING) }
                look("working")
                tap("edits", WORKING)
                d.hold(0.25)
                tap("diff", editLine("theme.css"), unmerged = true)
                d.hold(0.7)
                look("diff")

                at(FOLLOW_UP_AT)
                val followUp = hasSetTextAction() and hasAnyAncestor(hasTestTag("follow-up-composer"))
                tap("follow-up", followUp)
                d.hold(0.25)
                d.type(d.node(followUp), STEER_PROMPT)
                d.hold(0.25)
                tap("queue", hasTestTag("composer-main"))
                at(STEER_AT)
                look("queued")
                steer()
                until("the steer", 3.0) { d.exists(hasText(STEERED, substring = true)) }
                look("steered")
                at(LATEST_AT)
                if (d.exists(LATEST)) tap("latest", LATEST) else if (probing) println("promo: the transcript was following at +${elapsed()}ms")
                until("the steer's message", 6.0) { d.exists(said(STEER_PROMPT)) && graph.followUps.state(heroAgent()).value.queue.isEmpty() }
                look("filed")
                until("the change of plan", 6.0) { d.exists(said(ADAPTED)) }
                look("adapted")

                until("the answer", 20.0) { d.exists(said(OPENED)) }
                look("answer")
                at(SHIP_AT)
                if (!wide) {
                    tap("details", hasContentDescription("Open panel"))
                    at(SHIP_AT + 700)
                    look("details")
                }
                at(PULL_REQUEST_AT)
                tap("pull request", hasText("Pull request") and hasAnyAncestor(hasTestTag("conversation-panel")))
                at(PULL_REQUEST_AT + 900)
                look("pull-request")
                at(END_AT)
                look("end")
            } catch (_: EnoughFrames) {
                d.still("$name-last", 0.5f)
            }
        }

        private fun elapsed() = VirtualTime.nowMs - sent

        private fun heroAgent(): String = checkNotNull(streamer.heroAgent) { "The run has not started" }

        private fun look(step: String) {
            if (!probing) return
            println("promo: $step at +${elapsed()}ms")
            d.dump("$name-$step")
            d.still("$name-$step", 0.5f)
        }

        /** Films until [ms] after the send; a probe that is already past it says by how much. */
        private fun at(ms: Long) {
            if (probing && elapsed() > ms) {
                println("promo: already ${elapsed() - ms}ms past +${ms}ms")
                return
            }
            d.at(sent + ms)
        }

        private fun until(what: String, seconds: Double, condition: () -> Boolean) {
            try {
                d.until(what, seconds, condition)
                d.mark(what)
                if (probing) println("promo: $what at +${elapsed()}ms")
            } catch (e: IllegalStateException) {
                if (!probing) throw e
                println("promo: never saw $what")
            }
        }

        private fun tap(what: String, matcher: SemanticsMatcher, unmerged: Boolean = false) {
            if (!d.exists(matcher, unmerged)) {
                check(probing) { "Nothing to tap for $what" }
                println("promo: nothing to tap for $what at +${elapsed()}ms")
                d.dump("$name-missing-${what.replace(' ', '-')}")
                return
            }
            d.tap(d.node(matcher, unmerged), label = what)
        }

        /**
         * The queued card's arrow pressed, and the steer it asks for asked for: the demo has no account to steer
         * through, so the app does not offer one itself (see [PromoSteering]). Off the test's thread, which films the
         * frames the steer's steps wait on.
         */
        private fun steer() {
            val arrow = hasContentDescription(QueueGlyphs.STEER)
            if (!d.exists(arrow)) {
                check(probing) { "No queued card to steer" }
                println("promo: no queued card to steer at +${elapsed()}ms")
                d.dump("$name-missing-steer")
                return
            }
            d.touch(d.node(arrow), label = "steer")
            val agentId = heroAgent()
            val followUps = graph.followUps
            val queued = followUps.state(agentId).value.queue.first()
            CoroutineScope(Dispatchers.IO).launch { followUps.steerNow(agentId, queued.id) }
        }
    }

    /**
     * The compositor against Robolectric's own PixelCopy of the window, which renders it the same way at full size,
     * and its byte order through to a PNG: a red fill has to come back red.
     */
    @Test
    fun renderCheck() {
        val d = launch(Screen.Phone, "render-check")
        d.segment("render-check", 0.5f)
        d.hold(0.2)
        val decor = d.activity.window.decorView
        val copy = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        var result = -1
        PixelCopy.request(d.activity.window, copy, { result = it }, Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        check(result == PixelCopy.SUCCESS) { "PixelCopy failed ($result)" }
        val expected = ByteArray(copy.byteCount).also { copy.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
        val actual = ByteArray(decor.width * decor.height * 4)
        Compositor(decor.width, decor.height).use { c ->
            c.light(d.activity, 1f)
            c.render(actual) { it.drawRenderNode(Compositor.displayList(decor)) }
        }
        val off = (expected.indices step 4).count { i -> (0 until 4).any { abs((expected[i + it].toInt() and 255) - (actual[i + it].toInt() and 255)) > 2 } }
        println("promo: compositor vs PixelCopy: $off of ${expected.size / 4} pixels differ")
        check(off * 1000 < expected.size / 4) { "The compositor drew the window unlike PixelCopy: $off pixels differ" }
        val red = ByteArray(8 * 8 * 4)
        Compositor(8, 8).use { c -> c.render(red) { it.drawColor(Color.RED) } }
        val png = File(Promo.out, "render-check/red.png")
        FrameSink.png(red, 8, 8, png)
        val decoded = BitmapFactory.decodeFile(png.path).getPixel(4, 4)
        check(decoded == Color.RED) { "A red fill came back as #%08x".format(decoded) }
        finish("render-check")
    }

    /** [text] said in the chat itself, not in the panel or the sidebar beside it. */
    private fun said(text: String) = hasText(text, substring = true) and hasAnyAncestor(hasTestTag("transcript"))

    /** An edit's line in an open stretch, by its verb: the file's name on it opens the file rather than the diff. */
    private fun editLine(file: String) = hasText("Edited") and hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(file)))

    private companion object {
        const val HOME_PLACEHOLDER = "Ask Cursor to build, fix bugs, explore"
        const val HERO_PROMPT = "Add dark mode to the dashboard"
        const val STEER_PROMPT = "Follow the system theme too"
        const val STEERED = "Steered"
        const val ADAPTED = "Got it."
        const val OPENED = "Opened"

        /** Where the shell lays a window out wide, with the sidebar and the details beside the chat (ShellWindow). */
        const val WIDE_DP = 600

        val USER = CursorUser(apiKeyName = "Android", email = "alex@example.com", firstName = "Alex", lastName = "Rivera", userId = null)

        /** The stretch the run is working in, rather than the home list's or the panel's "Working". */
        val WORKING = hasText("Working") and hasAnyAncestor(hasTestTag("stretch"))
        val LATEST = hasContentDescription("Scroll to latest")

        // Each step's moment, in ms after the send: the run is scripted to the millisecond from there, so these are the
        // same on every device, and every take shows the same thing at the same moment.
        const val PANEL_AT = 1_000L
        const val THINKING_AT = 1_800L
        const val EDITS_AT = 4_900L
        const val FOLLOW_UP_AT = 6_900L
        const val STEER_AT = 9_500L
        const val LATEST_AT = 10_500L
        const val SHIP_AT = 23_400L
        const val PULL_REQUEST_AT = 24_300L
        const val END_AT = 27_000L
    }
}
