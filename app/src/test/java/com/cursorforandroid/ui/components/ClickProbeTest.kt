package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.TextFieldValue
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
import org.junit.Assume.assumeTrue
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

    /** Turns a Compose feature flag off for [block], where this Compose has it. */
    private fun without(owner: String, flag: String, block: () -> Unit) {
        val field = runCatching { Class.forName(owner).getDeclaredField(flag).apply { isAccessible = true } }.getOrNull()
        assumeTrue("$flag is not a flag of this Compose", field != null && field.type == Boolean::class.javaPrimitiveType)
        val was = field!!.getBoolean(null)
        field.setBoolean(null, false)
        try { block() } finally { field.setBoolean(null, was) }
    }

    private fun chipOverComposer() {
        show {
            AtTheFoot {
                SelectorRow { SelectorChip("chip", onClick = { clicks++ }) }
                Composer()
            }
        }
        compose.onNodeWithText("chip").performClick()
        clicked()
    }

    private fun over(below: @Composable () -> Unit, target: @Composable () -> Unit) {
        show {
            AtTheFoot {
                target()
                below()
            }
        }
        compose.onNodeWithTag("t").performClick()
        clicked()
    }

    private val shape get() = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)

    @Test
    fun `20 a plain clickable 28dp tall over the composer`() =
        over({ Composer() }) { Box(Modifier.padding(bottom = 6.dp).size(60.dp, 28.dp).testTag("t").clickable { clicks++ }) }

    @Test
    fun `21 a clipped clickable 60dp tall over the composer`() =
        over({ Composer() }) { Box(Modifier.padding(bottom = 4.dp).size(60.dp).testTag("t").clip(shape).clickable { clicks++ }) }

    @Test
    fun `22 a clickable with its own source and ripple over the composer`() =
        over({ Composer() }) {
            Box(Modifier.padding(bottom = 4.dp).size(60.dp).testTag("t").clickable(remember { MutableInteractionSource() }, ripple(), onClick = { clicks++ }))
        }

    @Test
    fun `23 a pressable 60dp tall over the composer`() =
        over({ Composer() }) { Box(Modifier.padding(bottom = 4.dp).size(60.dp).testTag("t").pressable({ clicks++ }, shape)) }

    @Test
    fun `24 a pressable 28dp tall over the composer`() =
        over({ Composer() }) { Box(Modifier.padding(bottom = 6.dp).size(60.dp, 28.dp).testTag("t").pressable({ clicks++ }, shape)) }

    @Test
    fun `25 a plain clickable 28dp tall, 120dp over the composer`() =
        over({ Composer() }) { Box(Modifier.padding(bottom = 120.dp).size(60.dp, 28.dp).testTag("t").clickable { clicks++ }) }

    @Test
    fun `26 a plain clickable 28dp tall over a bare text field of a state`() =
        over({ BasicTextField(rememberTextFieldState(), Modifier.fillMaxWidth().height(60.dp)) }) {
            Box(Modifier.padding(bottom = 6.dp).size(60.dp, 28.dp).testTag("t").clickable { clicks++ })
        }

    @Test
    fun `27 a plain clickable 28dp tall over a bare text field of a value`() =
        over({ BasicTextField(TextFieldValue(""), {}, Modifier.fillMaxWidth().height(60.dp)) }) {
            Box(Modifier.padding(bottom = 6.dp).size(60.dp, 28.dp).testTag("t").clickable { clicks++ })
        }

    @Test
    fun `28 a plain clickable 28dp tall over a plain box`() =
        over({ Box(Modifier.fillMaxWidth().height(60.dp).clickable { clicks += 100 }) }) {
            Box(Modifier.padding(bottom = 6.dp).size(60.dp, 28.dp).testTag("t").clickable { clicks++ })
        }

    @Test
    fun `30 the chip over the composer`() = chipOverComposer()

    @Test
    fun `31 the chip, without the new text field modifier`() =
        without(FOUNDATION, "isNewTextFieldModifierImplementationEnabled", ::chipOverComposer)

    @Test
    fun `32 the chip, without waiting for window focus`() =
        without(FOUNDATION, "isTextFieldWaitWindowFocusForInputSessionEnabled", ::chipOverComposer)

    @Test
    fun `33 the chip, without styled text`() = without(FOUNDATION, "isBasicTextFieldStyledTextEnabled", ::chipOverComposer)

    @Test
    fun `34 the chip, without the semantics hit test skip`() = without(UI, "isSkipNonImportantSemanticsNodesHitTestEnabled", ::chipOverComposer)

    @Test
    fun `35 the chip, without moves that have not moved`() = without(UI, "isTriggerMoveEventsWhenLocationHasNotChangedEnabled", ::chipOverComposer)

    @Test
    fun `36 the chip, without the size optimization`() = without(FOUNDATION, "isBasicTextFieldSizeOptimizationEnabled", ::chipOverComposer)

    private companion object {
        const val FOUNDATION = "androidx.compose.foundation.ComposeFoundationFlags"
        const val UI = "androidx.compose.ui.ComposeUiFlags"
    }
}
