package com.cursorforandroid.ui.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cursorforandroid.domain.DraftFile
import com.cursorforandroid.domain.DraftImage
import com.cursorforandroid.domain.MessageAttachment
import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.PromptFile
import com.cursorforandroid.domain.PromptImage
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.ComposerAnchor
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.PendingAttachment
import com.cursorforandroid.ui.components.PendingFile
import com.cursorforandroid.ui.components.QueueDeliveries
import com.cursorforandroid.ui.components.QueueFlights
import com.cursorforandroid.ui.components.SendFlight
import com.cursorforandroid.ui.components.SendLanding
import com.cursorforandroid.ui.components.SendMotion
import com.cursorforandroid.ui.components.SendMotionHost
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A chat's foot while a run is under way, as the conversation screen lays it out: the transcript's last bubbles, the
 * device's queue card (and the account's, in Extended mode) over the composer, each row an end of the send's flight.
 * The tap and the run's taking a queued message are made the way the screen makes them ([sendQueued], [deliver]), on
 * a clock the test holds.
 */
class QueueMotionScene(private val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>) {
    val anchor = ComposerAnchor()
    val flights = QueueFlights()
    var composerText by mutableStateOf("")
    val images = mutableStateListOf<PendingAttachment>()
    val files = mutableStateListOf<PendingFile>()
    val messages = mutableStateListOf(
        UserMessage("u-1", "Profile the cold start and tell me where the time goes."),
    )
    val queue = mutableStateListOf<QueuedFollowUp>()
    val account = mutableStateListOf<PendingFollowup>()
    private val thumbnails = mutableStateMapOf<String, ImageBitmap>()
    var scrolledAway = false

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Content(motion: SendMotion) {
        CursorTheme(mode = ThemeMode.Dark) {
            CompositionLocalProvider(LocalRippleConfiguration provides null, LocalTranscriptControls provides TranscriptControls()) {
                Box(Modifier.testTag(Frame).fillMaxWidth().height(640.dp).background(CursorTheme.colors.canvas)) {
                    SendMotionHost(motion) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                for (message in messages) key(message.id) { TimelineItemView(message) }
                            }
                            Spacer(Modifier.weight(1f))
                            QueueDeliveries(
                                flights = flights,
                                rows = LinkedHashMap<String, String>().apply {
                                    queue.forEach { put(it.id, it.previewText) }
                                    account.forEach { put(it.id, it.previewText) }
                                },
                                transcript = messages.mapTo(HashSet()) { it.id },
                                scrolledAway = { scrolledAway },
                            )
                            if (queue.isNotEmpty()) {
                                QueuedFollowUps(queue.toList(), thumbnails.toMap(), {}, {}, {}, Modifier.padding(bottom = 4.dp), flights)
                            }
                            if (account.isNotEmpty()) {
                                AccountQueueRows(account.toList(), emptySet(), {}, {}, { _, _ -> }, { _, _ -> }, Modifier.padding(bottom = 4.dp), flights = flights)
                            }
                            ComposerBox(
                                value = composerText,
                                onValueChange = {},
                                placeholder = "Follow up (sends when the turn ends)…",
                                onSend = {},
                                canSend = composerText.isNotBlank() || images.isNotEmpty() || files.isNotEmpty(),
                                isRunning = true,
                                onStop = {},
                                plusMenu = ComposerMenuActions(onPickMedia = {}, onPickFiles = {}),
                                attachments = images.toList(),
                                onRemoveAttachment = {},
                                files = files.toList(),
                                onRemoveFile = {},
                                modelLabel = "Claude Fable 5.1",
                                onModel = {},
                                modifier = Modifier.fillMaxWidth(),
                                anchor = anchor,
                            )
                        }
                    }
                }
            }
        }
    }

    fun show(motion: SendMotion) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Content(motion) }
        frames(64)
    }

    /**
     * On until a change made on the UI thread is composed, applied and laid out: the first frame only hears of the
     * write (the held clock sends the apply notification with it), the second composes it.
     */
    fun frame() {
        repeat(2) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    fun frames(millis: Long) {
        var left = millis
        while (left > 0) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            left -= 16
        }
    }

    /**
     * Two pictures (an orange landscape, a blue portrait) and a PDF in the composer, as the pickers leave them; returned
     * as the bubble the message is filed under will list them, their copies written where the transcript keeps them.
     */
    fun attach(): List<MessageAttachment> {
        val dir = File(compose.activity.cacheDir, "sent").apply { mkdirs() }
        val sent = listOf(Triple(160, 120, android.graphics.Color.rgb(214, 108, 52)), Triple(120, 200, android.graphics.Color.rgb(52, 120, 246))).mapIndexed { index, (w, h, color) ->
            val shot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            images += PendingAttachment("img-$index", PromptImage(png(shot), "image/png"), shot.asImageBitmap())
            MessageAttachment(File(dir, "img-$index.png").apply { writeBytes(png(shot)) }.path, w, h)
        }
        val spec = PromptFile(ByteArray(2_400 * 1024), "Q3-header-spec.pdf", "application/pdf")
        files += PendingFile("f-1", spec)
        val copy = File(dir, spec.name).apply { writeBytes(ByteArray(16)) }
        return sent + MessageAttachment(copy.path, 0, 0, name = spec.name, mimeType = spec.mimeType, sizeBytes = spec.sizeBytes.toLong())
    }

    /**
     * The tap while the agent runs, as the screen makes it: the composer's text and chips lifted off for the queue card,
     * the composer emptied, and the message on the card as row [id] in the same frame (enqueueing is synchronous).
     */
    fun sendQueued(motion: SendMotion, id: String, onAccount: Boolean = false): SendFlight? {
        val text = composerText.trim()
        var flight: SendFlight? = null
        compose.runOnUiThread {
            val standing = queue.mapTo(HashSet()) { it.id } + account.map { it.id }
            flight = motion.depart(anchor.takeoff(), text, excluded = standing, landing = SendLanding.Queue)
            val drafts = images.map { DraftImage(it.id, it.image) }
            images.forEach { image -> image.thumbnail?.let { thumbnails[image.id] = it } }
            val attached = files.map { DraftFile(it.id, it.file) }
            if (onAccount) {
                account += PendingFollowup(id, text, files = attached.map { com.cursorforandroid.domain.PendingAttachment(it.file.name, it.file.mimeType) }, imageCount = drafts.size)
            } else {
                queue += QueuedFollowUp(id, text, images = drafts, files = attached, queuedAtMillis = 0L)
            }
            composerText = ""
            images.clear()
            files.clear()
        }
        frame()
        return flight
    }

    /**
     * The run taking queued row [id]: the row leaves the card, and its bubble [bubble] is filed in the same frame
     * (the account's queue) or, with [filedAfter], that many milliseconds on (the device's, whose bubble waits for the run).
     */
    fun deliver(id: String, bubble: String, filedAfter: Long = 0L, attachments: List<MessageAttachment> = emptyList()) {
        val text = queue.firstOrNull { it.id == id }?.previewText ?: account.first { it.id == id }.previewText
        compose.runOnUiThread {
            queue.removeAll { it.id == id }
            account.removeAll { it.id == id }
            if (filedAfter == 0L) messages += UserMessage(bubble, text, attachments = attachments)
        }
        frame()
        if (filedAfter > 0L) {
            frames(filedAfter)
            compose.runOnUiThread { messages += UserMessage(bubble, text, attachments = attachments) }
            frame()
        }
    }

    /** The window as drawn now, into [file]: drawn here rather than through captureToImage, which waits on the held clock. */
    fun drawTo(file: File) {
        val root = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bitmap)) }
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    companion object {
        const val Frame = "queue_motion_frame"
    }
}
