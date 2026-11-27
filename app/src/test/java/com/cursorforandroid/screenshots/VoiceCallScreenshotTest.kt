package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.Transcription
import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.RecordedClip
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.components.VoiceInput
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.ui.voice.VoiceCallChip
import com.cursorforandroid.ui.voice.VoiceCallLabel
import com.cursorforandroid.ui.voice.VoiceCallReturnTray
import com.cursorforandroid.ui.voice.VoiceCallTranscript
import com.cursorforandroid.ui.voice.VoiceCallTray
import com.cursorforandroid.ui.voice.VoiceChatPhase
import com.cursorforandroid.ui.voice.VoiceChatState
import com.cursorforandroid.ui.voice.VoiceTurn
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The voice chat as Cursor lays it out: the waveform "Start voice chat" button in an empty composer's Send slot with
 * dictation's microphone beside it, then Cursor's call tray docked over the composer through each thing a call can be
 * doing, its settings menu and transcript, and the return tray another chat shows while the call is on. The clock is
 * held, so the line's motion is caught at the same instant every run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h220dp-night-420dpi")
class VoiceCallScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun frame(name: String, mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Column(
                        Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(12.dp),
                        verticalArrangement = Arrangement.Bottom,
                    ) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(900)
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Composable
    private fun Composer(onStartVoiceChat: (() -> Unit)? = null) {
        val scope = rememberCoroutineScope()
        val voice = remember { VoiceInput(scope, NoTranscription, { SilentCapture }) }
        ComposerBox(
            value = "",
            onValueChange = {},
            placeholder = "Follow up…",
            onSend = {},
            plusMenu = ComposerMenuActions(onPickMedia = {}),
            modelLabel = "Claude Fable 5.1",
            onModel = {},
            voice = voice,
            onStartVoiceChat = onStartVoiceChat,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /** The tray where Cursor docks it, over the composer, which stays (without the start button: a call is on). */
    private fun tray(name: String, state: VoiceChatState, mode: ThemeMode = ThemeMode.Dark, settingsOpen: Boolean = false) = frame(name, mode) {
        VoiceCallTray(state, elapsed = "1:24", onMute = {}, onHangUp = {}, onTranscript = {}, settingsOpen = settingsOpen, modifier = Modifier.padding(bottom = 4.dp))
        Composer()
    }

    @Test
    fun startButton() = frame("1159_voice_chat_start_button") { Composer(onStartVoiceChat = {}) }

    @Test
    fun listening() = tray("1160_voice_chat_listening", VoiceChatState(VoiceChatPhase.Listening(heard = false), level = 0.05f))

    @Test
    fun hearing() = tray("1161_voice_chat_hearing", VoiceChatState(VoiceChatPhase.Listening(heard = true), level = 0.8f))

    @Test
    fun thinking() = tray("1162_voice_chat_thinking", VoiceChatState(VoiceChatPhase.Thinking))

    @Test
    fun working() = tray("1163_voice_chat_working", VoiceChatState(VoiceChatPhase.Working("Run the tests again and tell me which ones fail")))

    @Test
    fun speaking() = tray("1164_voice_chat_speaking", VoiceChatState(VoiceChatPhase.Speaking("All 412 tests pass.")))

    @Test
    fun muted() = tray("1165_voice_chat_muted", VoiceChatState(VoiceChatPhase.Working("Run the tests again"), muted = true))

    @Test
    fun failed() = tray("1166_voice_chat_failed", VoiceChatState(VoiceChatPhase.Failed("Couldn't reach Cursor. Check the connection and try again.")))

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h220dp-notnight-420dpi")
    fun hearingLight() = tray("1167_voice_chat_hearing_light", VoiceChatState(VoiceChatPhase.Listening(heard = true), level = 0.8f), mode = ThemeMode.Light)

    @Test
    fun returnInAnotherChat() = frame("1168_voice_chat_return_elsewhere") {
        VoiceCallReturnTray(
            title = "Fix flaky login test",
            state = VoiceChatState(VoiceChatPhase.Working("Run the tests again")),
            elapsed = "3:07",
            onMute = {},
            onHangUp = {},
            onReturn = {},
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Composer()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h420dp-night-420dpi")
    fun settingsMenu() = tray("1169_voice_chat_settings", VoiceChatState(VoiceChatPhase.Listening()), settingsOpen = true)

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h420dp-night-420dpi")
    fun transcript() = frame("1171_voice_chat_transcript") {
        VoiceCallTranscript(
            turns = listOf(
                VoiceTurn(fromUser = true, text = "Run the tests again and tell me which ones fail"),
                VoiceTurn(fromUser = false, text = "All 412 tests pass. Two were flaky on the first run, so I ran them again in isolation and they held."),
                VoiceTurn(fromUser = true, text = "Great, open a pull request for it"),
            ),
            started = "3:42 PM",
            status = "Ongoing · 1:24",
            modifier = Modifier.background(CursorTheme.colors.elevated),
        )
    }

    @Test
    fun pillAndLabel() = frame("1172_voice_chat_pill_and_label") {
        // The sidebar's pill on the call's chat, and the label over a message that was said on the call.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Fix flaky login test", style = CursorTheme.typography.row, color = CursorTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            VoiceCallChip(startedAtMs = 0L, elapsed = "3:07")
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            VoiceCallLabel(Modifier.padding(end = 4.dp, bottom = 4.dp))
            Text(
                "Run the tests again and tell me which ones fail",
                style = CursorTheme.typography.message,
                color = CursorTheme.colors.textPrimary,
                modifier = Modifier.cursorSurface(CursorTheme.colors.fillFaint, CursorTheme.colors.stroke, CursorTheme.shapes.xl).padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }

    private object SilentCapture : AudioCapture {
        override fun start() = Unit
        override fun level() = 0f
        override fun stop(): RecordedClip? = null
        override fun cancel() = Unit
    }

    private object NoTranscription : TranscriptionApi {
        override suspend fun transcribe(audio: ByteArray, mimeType: String, language: String?) = Transcription("", 0)
    }
}
