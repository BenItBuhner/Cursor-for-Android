package com.cursorforandroid.ui.computers

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComputersScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `SDK-only is honest about needing Extended and does not invent a pairing list`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComputersList(
                    state = ComputerRepository.ListState(allowed = false, error = ComputerRepository.NEEDS_EXTENDED_MODE),
                    onOpenComputer = {},
                )
            }
        }
        compose.onNodeWithText(ComputersCopy.NEEDS_MODE_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ComputerRepository.NEEDS_EXTENDED_MODE).assertIsDisplayed()
        compose.onNodeWithText(ComputersCopy.MACHINES_HINT).assertIsDisplayed()
        compose.onAllNodesWithText("Bennett's Mac").let { assertThat(it.fetchSemanticsNodes()).isEmpty() }
    }

    @Test
    fun `the computer list opens a row`() {
        var opened: String? = null
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComputersList(
                    state = ComputerRepository.ListState(
                        allowed = true,
                        computers = listOf(
                            Computer("desk-1", "Bennett's Mac", presence = ComputerPresence.ONLINE, pairing = ComputerPairing.UNPAIRED),
                            Computer("desk-2", "Studio", presence = ComputerPresence.OFFLINE, pairing = ComputerPairing.PAIRED),
                        ),
                    ),
                    onOpenComputer = { opened = it },
                )
            }
        }
        compose.onNodeWithText("Bennett's Mac").assertIsDisplayed()
        compose.onNodeWithText("Online · Not paired").assertIsDisplayed()
        compose.onNodeWithText("Studio").assertIsDisplayed()
        compose.onNodeWithTag(ComputersTags.computerRow("desk-1")).performClick()
        assertThat(opened).isEqualTo("desk-1")
    }

    @Test
    fun `pending pairing shows the verification code and asks for desktop approve`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComputerDetail(
                    state = ComputerRepository.DetailState(
                        allowed = true,
                        computer = Computer("desk-1", "Bennett's Mac", pairing = ComputerPairing.PENDING),
                        challenge = PairingChallenge(ControllerTrust.PENDING, "thumb", "1234-5678", "ch-1"),
                    ),
                    onPair = {},
                    onReply = { _, _ -> },
                    onStart = { _, _ -> },
                )
            }
        }
        compose.onNodeWithTag(ComputersTags.CODE).assertIsDisplayed()
        compose.onNodeWithText("1234-5678").assertIsDisplayed()
        compose.onNodeWithText(ComputersCopy.APPROVE).assertIsDisplayed()
        compose.onAllNodesWithText(ComputersCopy.PAIR).let { assertThat(it.fetchSemanticsNodes()).isEmpty() }
    }

    @Test
    fun `a paired online computer can reply to a local agent and start a task`() {
        var reply: Pair<String, String>? = null
        var start: Pair<String, List<String>>? = null
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComputerDetail(
                    state = ComputerRepository.DetailState(
                        allowed = true,
                        computer = Computer("desk-1", "Bennett's Mac", presence = ComputerPresence.ONLINE, pairing = ComputerPairing.PAIRED),
                        challenge = PairingChallenge(ControllerTrust.TRUSTED, "thumb", "1234-5678"),
                        sessions = listOf(LocalAgentSession("sess-1", "Fix the nav", status = LocalAgentStatus.RUNNING)),
                        workspaces = listOf(LocalWorkspace("/workspace", listOf("/workspace"))),
                    ),
                    onPair = {},
                    onReply = { id, text -> reply = id to text },
                    onStart = { text, paths -> start = text to paths },
                )
            }
        }
        compose.onNodeWithText("Fix the nav").assertIsDisplayed()
        compose.onNodeWithText(ComputersCopy.START_HINT).assertIsDisplayed()
        compose.onNodeWithTag(ComputersTags.REPLY_SEND).assertIsNotEnabled()
        compose.onNodeWithTag(ComputersTags.REPLY_FIELD).performTextInput("keep going")
        compose.onNodeWithTag(ComputersTags.REPLY_SEND).performClick()
        assertThat(reply).isEqualTo("sess-1" to "keep going")
        compose.onNodeWithTag(ComputersTags.START_FIELD).performTextInput("new task")
        compose.onNodeWithTag(ComputersTags.START_SEND).performClick()
        assertThat(start).isEqualTo("new task" to listOf("/workspace"))
    }

    @Test
    fun `an unpaired computer offers Request pairing, not a fake inbox`() {
        var paired = 0
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComputerDetail(
                    state = ComputerRepository.DetailState(
                        allowed = true,
                        computer = Computer("desk-1", "Bennett's Mac", pairing = ComputerPairing.UNPAIRED),
                        challenge = PairingChallenge(ControllerTrust.NONE, "thumb", "0000-0000"),
                    ),
                    onPair = { paired++ },
                    onReply = { _, _ -> },
                    onStart = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText(ComputersCopy.UNPAIRED).assertIsDisplayed()
        compose.onNodeWithTag(ComputersTags.PAIR_BUTTON).performClick()
        assertThat(paired).isEqualTo(1)
        compose.onAllNodesWithText(ComputersCopy.INBOX).let { assertThat(it.fetchSemanticsNodes()).isEmpty() }
    }

    @Test
    fun `a selected local session shows the computer transcript, never a cloud chat id`() {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ComputerDetail(
                    state = ComputerRepository.DetailState(
                        allowed = true,
                        computer = Computer("desk-1", "Bennett's Mac", presence = ComputerPresence.ONLINE, pairing = ComputerPairing.PAIRED),
                        challenge = PairingChallenge(ControllerTrust.TRUSTED, "thumb", "1234-5678"),
                        sessions = listOf(LocalAgentSession("sess-1", "Fix the nav", status = LocalAgentStatus.RUNNING)),
                        attachedSessionId = "sess-1",
                        transcript = listOf(
                            LocalAgentLine(LocalAgentLineKind.USER, "Fix the nav"),
                            LocalAgentLine(LocalAgentLineKind.ASSISTANT, "Looking at NavStack.kt"),
                        ),
                    ),
                    onPair = {},
                    onReply = { _, _ -> },
                    onStart = { _, _ -> },
                    selectedSessionId = "sess-1",
                )
            }
        }
        compose.onNodeWithTag(ComputersTags.TRANSCRIPT).assertIsDisplayed()
        compose.onNodeWithText("Looking at NavStack.kt").assertIsDisplayed()
        compose.onAllNodesWithText("bc-", substring = true).let { assertThat(it.fetchSemanticsNodes()).isEmpty() }
    }
}
