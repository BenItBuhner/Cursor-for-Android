package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.ui.voice.VoiceCallCard
import com.cursorforandroid.ui.voice.VoiceCallStrip
import com.cursorforandroid.ui.voice.VoiceChatPhase
import com.cursorforandroid.ui.voice.VoiceChatState
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
 * dictation's microphone beside it, then the call card that stands in the composer's box through each thing a call
 * can be doing, and the strip another chat shows while the call is on. The clock is held, so the waveform's motion is
 * caught at the same instant every run.
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

    private fun card(name: String, state: VoiceChatState, mode: ThemeMode = ThemeMode.Dark) = frame(name, mode) {
        VoiceCallCard(state, elapsed = "1:24", onTap = {}, onMute = {}, onEnd = {})
    }

    @Test
    fun startButton() = frame("1159_voice_chat_start_button") {
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
            onStartVoiceChat = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Test
    fun waiting() = card("1160_voice_chat_waiting", VoiceChatState(VoiceChatPhase.Listening(heard = false)))

    @Test
    fun hearing() = card("1161_voice_chat_hearing", VoiceChatState(VoiceChatPhase.Listening(heard = true), level = 0.8f))

    @Test
    fun transcribing() = card("1162_voice_chat_transcribing", VoiceChatState(VoiceChatPhase.Transcribing))

    @Test
    fun working() = card(
        "1163_voice_chat_working",
        VoiceChatState(VoiceChatPhase.Working("Run the tests again and tell me which ones fail"), lastSaid = "Run the tests again and tell me which ones fail"),
    )

    @Test
    fun speaking() = card(
        "1164_voice_chat_speaking",
        VoiceChatState(VoiceChatPhase.Speaking("All 412 tests pass. Two were flaky on the first run, so I ran them again in isolation and they held. The rest is in the chat.")),
    )

    @Test
    fun muted() = card("1165_voice_chat_on_hold", VoiceChatState(VoiceChatPhase.Muted))

    @Test
    fun failed() = card("1166_voice_chat_failed", VoiceChatState(VoiceChatPhase.Failed("Couldn't reach Cursor. Check the connection and try again.", resumes = true)))

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h220dp-notnight-420dpi")
    fun hearingLight() = card("1167_voice_chat_hearing_light", VoiceChatState(VoiceChatPhase.Listening(heard = true), level = 0.8f), mode = ThemeMode.Light)

    @Test
    fun stripInAnotherChat() = frame("1168_voice_chat_strip_elsewhere") {
        VoiceCallStrip(
            title = "Fix flaky login test",
            state = VoiceChatState(VoiceChatPhase.Working("Run the tests again")),
            elapsed = "3:07",
            onOpen = {},
            onEnd = {},
        )
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
