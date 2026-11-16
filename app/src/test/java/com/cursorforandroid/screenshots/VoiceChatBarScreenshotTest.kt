package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.ui.voice.VoiceChatBar
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
 * The voice chat's bar in each thing a call can be doing: waiting for a voice, hearing one, transcribing, waiting on
 * the agent, reading its reply, on hold and failed, with the listening bar in light as well. The clock is held, so
 * the orb's motion is caught at the same instant every run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h120dp-night-420dpi")
class VoiceChatBarScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun frame(name: String, state: VoiceChatState, mode: ThemeMode = ThemeMode.Dark) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(16.dp)) {
                        VoiceChatBar(state, onTap = {}, onHold = {}, onEnd = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(900)
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun waiting() = frame("1160_voice_chat_waiting", VoiceChatState(VoiceChatPhase.Listening(heard = false)))

    @Test
    fun hearing() = frame("1161_voice_chat_hearing", VoiceChatState(VoiceChatPhase.Listening(heard = true), level = 0.8f))

    @Test
    fun transcribing() = frame("1162_voice_chat_transcribing", VoiceChatState(VoiceChatPhase.Transcribing))

    @Test
    fun working() = frame(
        "1163_voice_chat_working",
        VoiceChatState(VoiceChatPhase.Working("Run the tests again and tell me which ones fail"), lastSaid = "Run the tests again and tell me which ones fail"),
    )

    @Test
    fun speaking() = frame(
        "1164_voice_chat_speaking",
        VoiceChatState(VoiceChatPhase.Speaking("All 412 tests pass. Two were flaky on the first run, so I ran them again in isolation and they held. The rest is in the chat.")),
    )

    @Test
    fun onHold() = frame("1165_voice_chat_on_hold", VoiceChatState(VoiceChatPhase.Muted))

    @Test
    fun failed() = frame("1166_voice_chat_failed", VoiceChatState(VoiceChatPhase.Failed("Couldn't reach Cursor. Check the connection and try again.", resumes = true)))

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h120dp-notnight-420dpi")
    fun hearingLight() = frame("1167_voice_chat_hearing_light", VoiceChatState(VoiceChatPhase.Listening(heard = true), level = 0.8f), mode = ThemeMode.Light)
}
