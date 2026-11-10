package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Goal
import com.cursorforandroid.domain.GoalStatus
import com.cursorforandroid.ui.conversation.GoalStrip
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A probe, not a test to keep: which taps reach their target under the Compose this branch moves to. Each case is one
 * arrangement the failing tests share a part of; the ones that fail here name the part. Removed once it has answered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-420dpi")
class ClickProbeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var clicks = 0
    private val goal = Goal.fromAccount("Ship the release", GoalStatus.PAUSED, activeDurationMs = 5_100_000L, lastAccruedAtMs = null, continuationCount = 3, goalId = "8a1c")

    private fun show(ripple: Boolean = true, content: @Composable () -> Unit) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                if (ripple) content() else CompositionLocalProvider(LocalRippleConfiguration provides null) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun clicked() {
        compose.waitForIdle()
        assertThat(clicks).isEqualTo(1)
    }

    @Composable
    private fun Composer() = ComposerBox(
        value = "",
        onValueChange = {},
        placeholder = "Follow up…",
        onSend = {},
        isRunning = false,
        onStop = {},
        plusMenu = ComposerMenuActions(onPickMedia = {}),
        modelLabel = "Model",
        onModel = { clicks += 100 },
        modifier = Modifier.fillMaxWidth(),
    )

    @Composable
    private fun AtTheFoot(content: @Composable () -> Unit) =
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Bottom) { content() }

    @Test
    fun `01 a plain clickable`() {
        show { Box(Modifier.size(100.dp).testTag("t").clickable { clicks++ }) }
        compose.onNodeWithTag("t").performClick()
        clicked()
    }

    @Test
    fun `02 a pressable`() {
        show { Box(Modifier.size(100.dp).testTag("t").pressable({ clicks++ }, CursorTheme.shapes.base)) }
        compose.onNodeWithTag("t").performClick()
        clicked()
    }

    @Test
    fun `03 a pressable by touch input`() {
        show { Box(Modifier.size(100.dp).testTag("t").pressable({ clicks++ }, CursorTheme.shapes.base)) }
        compose.onNodeWithTag("t").performTouchInput { click() }
        clicked()
    }

    @Test
    fun `04 a pressable over a scroll of its own`() {
        show { Box(Modifier.size(100.dp).pressable({ clicks++ }, CursorTheme.shapes.base).testTag("t").verticalScroll(rememberScrollState())) }
        compose.onNodeWithTag("t").performClick()
        clicked()
    }

    @Test
    fun `05 a selector chip alone`() {
        show { SelectorChip("chip", onClick = { clicks++ }) }
        compose.onNodeWithText("chip").performClick()
        clicked()
    }

    @Test
    fun `06 a selector chip at the foot`() {
        show { AtTheFoot { SelectorRow { SelectorChip("chip", onClick = { clicks++ }) } } }
        compose.onNodeWithText("chip").performClick()
        clicked()
    }

    @Test
    fun `07 a selector chip over the composer`() {
        show {
            AtTheFoot {
                SelectorRow { SelectorChip("chip", onClick = { clicks++ }) }
                Composer()
            }
        }
        compose.onNodeWithText("chip").performClick()
        clicked()
    }

    @Test
    fun `08 a selector chip over the composer, no ripple`() {
        show(ripple = false) {
            AtTheFoot {
                SelectorRow { SelectorChip("chip", onClick = { clicks++ }) }
                Composer()
            }
        }
        compose.onNodeWithText("chip").performClick()
        clicked()
    }

    @Test
    fun `09 the composer's own model chip`() {
        show { AtTheFoot { Composer() } }
        compose.onNodeWithText("Model").performClick()
        compose.waitForIdle()
        assertThat(clicks).isEqualTo(100)
    }

    @Test
    fun `10 the goal strip alone`() {
        show { AtTheFoot { GoalStrip(goal = goal, expanded = false, onExpandedChange = { clicks++ }, modifier = Modifier.fillMaxWidth()) } }
        compose.onNodeWithTag("goal-strip").performClick()
        clicked()
    }

    @Test
    fun `11 the goal strip over the composer`() {
        show {
            AtTheFoot {
                GoalStrip(goal = goal, expanded = false, onExpandedChange = { clicks++ }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                Composer()
            }
        }
        compose.onNodeWithTag("goal-strip").performClick()
        clicked()
    }

    @Test
    fun `12 a plain clickable over the composer`() {
        show {
            AtTheFoot {
                Box(Modifier.fillMaxWidth().padding(bottom = 4.dp).size(60.dp).testTag("t").clickable { clicks++ })
                Composer()
            }
        }
        compose.onNodeWithTag("t").performClick()
        clicked()
    }

    @Test
    fun `13 a plain clickable at the top with the composer at the foot`() {
        show {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(60.dp).testTag("t").clickable { clicks++ })
                Composer()
            }
        }
        compose.onNodeWithTag("t").performClick()
        clicked()
    }
}
