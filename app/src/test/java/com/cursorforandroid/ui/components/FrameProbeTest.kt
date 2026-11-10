package com.cursorforandroid.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A probe, not a test to keep: with the clock held, on which frame after a tap each stage of the pipeline has run under
 * the Compose this branch moves to. Each case fails on purpose with what it measured. Removed once it has answered.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night-420dpi")
class FrameProbeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun tapAndCount(themed: Boolean, pressable: Boolean, idleAfterTap: Boolean, semanticsEachFrame: Boolean): String {
        var frame = 0
        var clicked = -1
        var composed = -1
        var effect = -1
        var sized = -1
        var firstFrameAt = -1
        val values = mutableListOf<String>()
        val content: @Composable () -> Unit = {
            var on by remember { mutableStateOf(false) }
            Column {
                val tap = { on = true; clicked = frame }
                Box(Modifier.size(100.dp).testTag("t").then(if (pressable) Modifier.pressable(tap, CursorTheme.shapes.base) else Modifier.clickable(onClick = tap)))
                if (on) {
                    SideEffect { if (composed < 0) composed = frame }
                    val grow = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        effect = frame
                        withFrameNanos { it }
                        firstFrameAt = frame
                        grow.animateTo(100f, tween(160))
                    }
                    Box(Modifier.width(10.dp).height((10 + grow.value).dp).onSizeChanged { if (sized < 0) sized = frame })
                    SideEffect { values += "f$frame=${grow.value.toInt()}" }
                }
            }
        }
        compose.setContent { if (themed) CursorTheme(mode = ThemeMode.Dark) { content() } else content() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("t").performClick()
        if (idleAfterTap) compose.waitForIdle()
        repeat(14) {
            frame = it + 1
            compose.mainClock.advanceTimeByFrame()
            if (semanticsEachFrame) compose.onNodeWithTag("t").fetchSemanticsNode()
        }
        compose.mainClock.autoAdvance = true
        return "clicked=$clicked composed=$composed effect=$effect sized=$sized firstFrameAt=$firstFrameAt values=$values"
    }

    private fun report(what: String) = assertWithMessage("PROBE $what").that(false).isTrue()

    @Test
    fun `10 a bare clickable`() = report(tapAndCount(themed = false, pressable = false, idleAfterTap = false, semanticsEachFrame = false))

    @Test
    fun `11 a bare clickable, idled after the tap`() = report(tapAndCount(themed = false, pressable = false, idleAfterTap = true, semanticsEachFrame = false))

    @Test
    fun `12 a bare clickable, its semantics read every frame`() = report(tapAndCount(themed = false, pressable = false, idleAfterTap = false, semanticsEachFrame = true))

    @Test
    fun `13 a themed pressable`() = report(tapAndCount(themed = true, pressable = true, idleAfterTap = false, semanticsEachFrame = false))

    @Test
    fun `14 a themed pressable, idled after the tap`() = report(tapAndCount(themed = true, pressable = true, idleAfterTap = true, semanticsEachFrame = false))

    @Test
    fun `15 a themed pressable, its semantics read every frame`() = report(tapAndCount(themed = true, pressable = true, idleAfterTap = false, semanticsEachFrame = true))

    @Test
    fun `16 a themed clickable`() = report(tapAndCount(themed = true, pressable = false, idleAfterTap = false, semanticsEachFrame = false))
}
