package com.cursorforandroid.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.domain.SubagentRows
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.domain.ToolPayload
import com.cursorforandroid.domain.TranscriptRows
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A stretch's line follows live only the subagents whose step it can draw (SCALE-7): the rest are counted off their
 * list rows. The line reads as it did when every one of them was streamed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class StretchSubagentFollowTest {

    @get:Rule
    val compose = createComposeRule()

    private val ids = (1..6).map { "bc-t$it" }

    /** Six background cloud tasks started in one stretch of a turn that has since ended. */
    private val turn: List<TimelineItem> = listOf(
        UserMessage("u1", "Split the chart work six ways."),
        ActivityGroup(
            "g1",
            ids.mapIndexed { i, id ->
                ToolCall("t$i", "task", ToolKind.Task, ToolCall.STATUS_COMPLETED, "Task ${i + 1}", payload = ToolPayload.Subagent("Task ${i + 1}", agentId = id, isBackground = true))
            },
        ),
        RunFooter("f1", "run-1", RunStatus.FINISHED, 38_000, emptyList()),
    )

    private val live = ids.associateWith { id -> MutableStateFlow<SubagentChild?>(SubagentChild(SubagentChild.Status.Running, action = "Editing ${id.removePrefix("bc-")}.kt")) }
    private val listed = ids.associateWith { MutableStateFlow<SubagentChild?>(SubagentChild(SubagentChild.Status.Running)) }
    private val followed = mutableListOf<String>()

    private fun show(coordinatorMode: Boolean) {
        val controls = TranscriptControls(
            coordinatorMode = coordinatorMode,
            subagentActivity = { id -> followed += id; live.getValue(id) },
            subagentListed = { id -> listed.getValue(id) },
        )
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalTranscriptControls provides controls) {
                    Column { TranscriptRows.of(turn, coordinatorMode = coordinatorMode).forEach { TranscriptRowView(it) } }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun finish(id: String) {
        live.getValue(id).value = SubagentChild(SubagentChild.Status.Succeeded)
        listed.getValue(id).value = SubagentChild(SubagentChild.Status.Succeeded)
    }

    @Test
    fun `in a Project's chat a closed stretch follows its two newest working subagents and reads the newest`() {
        show(coordinatorMode = true)
        compose.onNodeWithText("6 Working").assertIsDisplayed()
        compose.onNodeWithText("Editing t6.kt").assertIsDisplayed()
        assertThat(followed.toSet()).containsExactly("bc-t5", "bc-t6")

        // The newest ends on its stream before its row says so: the one before it, already followed, has the line at once.
        live.getValue("bc-t6").value = SubagentChild(SubagentChild.Status.Succeeded)
        compose.waitForIdle()
        compose.onNodeWithText("5 Working").assertIsDisplayed()
        compose.onNodeWithText("Editing t5.kt").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText(SubagentRows.PLANNING).fetchSemanticsNodes()).isEmpty()

        // Its row agrees: the next newest joins, and the one that ended is let go.
        listed.getValue("bc-t6").value = SubagentChild(SubagentChild.Status.Succeeded)
        compose.waitForIdle()
        assertThat(followed.toSet()).containsExactly("bc-t4", "bc-t5", "bc-t6")
        finish("bc-t5")
        compose.waitForIdle()
        compose.onNodeWithText("4 Working").assertIsDisplayed()
        compose.onNodeWithText("Editing t4.kt").assertIsDisplayed()
    }

    @Test
    fun `in an agent's chat a closed stretch counts its working subagents off their rows and streams none`() {
        show(coordinatorMode = false)
        compose.onNodeWithText("6 working").assertIsDisplayed()
        assertThat(followed).isEmpty()
        finish("bc-t1")
        compose.waitForIdle()
        compose.onNodeWithText("5 working").assertIsDisplayed()
        assertThat(followed).isEmpty()
    }

    @Test
    fun `opened where the stretch draws its own steps, every row is followed and reads its action`() {
        show(coordinatorMode = false)
        compose.onNodeWithText("6 working").performClick()
        compose.waitForIdle()
        ids.forEach { id ->
            val action = "Editing ${id.removePrefix("bc-")}.kt"
            compose.waitUntil(5_000) { compose.onAllNodesWithText(action).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(action).assertIsDisplayed()
        }
        assertThat(followed.toSet()).containsExactlyElementsIn(ids)
    }
}
