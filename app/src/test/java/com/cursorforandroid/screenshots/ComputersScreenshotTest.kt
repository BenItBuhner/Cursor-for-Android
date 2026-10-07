package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.repo.ComputerRepository
import com.cursorforandroid.domain.Computer
import com.cursorforandroid.domain.ComputerPairing
import com.cursorforandroid.domain.ComputerPresence
import com.cursorforandroid.domain.ControllerTrust
import com.cursorforandroid.domain.LocalAgentLine
import com.cursorforandroid.domain.LocalAgentLineKind
import com.cursorforandroid.domain.LocalAgentSession
import com.cursorforandroid.domain.LocalAgentStatus
import com.cursorforandroid.domain.LocalWorkspace
import com.cursorforandroid.domain.PairingChallenge
import com.cursorforandroid.ui.computers.ComputerDetail
import com.cursorforandroid.ui.computers.ComputersCopy
import com.cursorforandroid.ui.computers.ComputersList
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Oct 6 Computers: SDK-only needs Extended, the account computer list, desktop pairing, and the local-agent inbox.
 * Written to `screenshots/`; CI compares them pixel for pixel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComputersScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun scene(mode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun needsExtended() {
        scene {
            ComputersList(
                state = ComputerRepository.ListState(allowed = false, error = ComputerRepository.NEEDS_EXTENDED_MODE),
                onOpenComputer = {},
            )
        }
        compose.onNodeWithText(ComputersCopy.NEEDS_MODE_TITLE).assertIsDisplayed()
        capture("1200_computers_needs_extended")
    }

    @Test
    fun computerList() {
        scene {
            ComputersList(
                state = ComputerRepository.ListState(
                    allowed = true,
                    computers = listOf(
                        Computer("desk-1", "Bennett's Mac", presence = ComputerPresence.ONLINE, pairing = ComputerPairing.UNPAIRED),
                        Computer("desk-2", "Studio", presence = ComputerPresence.OFFLINE, pairing = ComputerPairing.PAIRED),
                    ),
                ),
                onOpenComputer = {},
            )
        }
        compose.onNodeWithText("Bennett's Mac").assertIsDisplayed()
        capture("1201_computers_list")
    }

    @Test
    fun pairingPending() {
        scene {
            ComputerDetail(
                state = ComputerRepository.DetailState(
                    allowed = true,
                    computer = Computer("desk-1", "Bennett's Mac", presence = ComputerPresence.ONLINE, pairing = ComputerPairing.PENDING),
                    challenge = PairingChallenge(ControllerTrust.PENDING, "thumb", "1234-5678", "ch-1"),
                ),
                onPair = {},
                onReply = { _, _ -> },
                onStart = { _, _ -> },
            )
        }
        compose.onNodeWithText("1234-5678").assertIsDisplayed()
        capture("1202_computer_pairing")
    }

    @Test
    fun localInbox() {
        scene {
            ComputerDetail(
                state = ComputerRepository.DetailState(
                    allowed = true,
                    computer = Computer("desk-1", "Bennett's Mac", presence = ComputerPresence.ONLINE, pairing = ComputerPairing.PAIRED),
                    challenge = PairingChallenge(ControllerTrust.TRUSTED, "thumb", "1234-5678"),
                    sessions = listOf(
                        LocalAgentSession("sess-1", "Fix the nav", status = LocalAgentStatus.RUNNING, workspace = LocalWorkspace("/workspace")),
                        LocalAgentSession("sess-2", "Idle agent", status = LocalAgentStatus.IDLE),
                    ),
                    workspaces = listOf(LocalWorkspace("/workspace", listOf("/workspace"))),
                ),
                onPair = {},
                onReply = { _, _ -> },
                onStart = { _, _ -> },
            )
        }
        compose.onNodeWithText("Fix the nav").assertIsDisplayed()
        capture("1203_computer_inbox")
    }

    @Test
    fun localTranscript() {
        scene {
            ComputerDetail(
                state = ComputerRepository.DetailState(
                    allowed = true,
                    computer = Computer("desk-1", "Bennett's Mac", presence = ComputerPresence.ONLINE, pairing = ComputerPairing.PAIRED),
                    challenge = PairingChallenge(ControllerTrust.TRUSTED, "thumb", "1234-5678"),
                    sessions = listOf(
                        LocalAgentSession("sess-1", "Fix the nav", status = LocalAgentStatus.RUNNING, workspace = LocalWorkspace("/workspace")),
                    ),
                    workspaces = listOf(LocalWorkspace("/workspace", listOf("/workspace"))),
                    attachedSessionId = "sess-1",
                    transcript = listOf(
                        LocalAgentLine(LocalAgentLineKind.USER, "Fix the nav"),
                        LocalAgentLine(LocalAgentLineKind.ASSISTANT, "Looking at NavStack.kt"),
                        LocalAgentLine(LocalAgentLineKind.STATUS, "Working…"),
                    ),
                ),
                onPair = {},
                onReply = { _, _ -> },
                onStart = { _, _ -> },
                selectedSessionId = "sess-1",
            )
        }
        compose.onNodeWithText("Looking at NavStack.kt").assertIsDisplayed()
        capture("1204_computer_transcript")
    }
}
