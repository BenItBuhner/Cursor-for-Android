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

    @Test
    fun `1 the frames from a tap to its click, composition, effect, layout and animation`() {
        var frame = 0
        var clicked = -1
        var composed = -1
        var effect = -1
        var sized = -1
        var firstFrameAt = -1
        var firstFrameNanos = 0L
        val values = mutableListOf<String>()
        compose.setContent {
            var on by remember { mutableStateOf(false) }
            Column {
                Box(Modifier.size(100.dp).testTag("t").clickable { on = true; clicked = frame })
                if (on) {
                    SideEffect { if (composed < 0) composed = frame }
                    val grow = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        effect = frame
                        val t0 = withFrameNanos { it }
                        firstFrameAt = frame
                        firstFrameNanos = t0
                        grow.animateTo(100f, tween(160))
                    }
                    Box(Modifier.width(10.dp).height((10 + grow.value).dp).onSizeChanged { if (sized < 0) sized = frame })
                    SideEffect { values += "f$frame=${grow.value.toInt()}" }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val before = compose.mainClock.currentTime
        compose.onNodeWithTag("t").performClick()
        val afterClick = compose.mainClock.currentTime
        val atTap = "clicked=$clicked composed=$composed effect=$effect sized=$sized"
        repeat(14) {
            frame = it + 1
            compose.mainClock.advanceTimeByFrame()
        }
        compose.mainClock.autoAdvance = true
        assertWithMessage(
            "PROBE tap took ${afterClick - before}ms of clock; right after tap: $atTap; then clicked=$clicked composed=$composed effect=$effect " +
                "sized=$sized firstFrameAt=$firstFrameAt firstFrameMs=${firstFrameNanos / 1_000_000} clockAtTap=$afterClick values=$values",
        ).that(false).isTrue()
    }

    @Test
    fun `2 a field's focus after it is cleared by force`() {
        lateinit var focus: FocusManager
        var touchMode = false
        var viewFocused = false
        compose.setContent {
            focus = LocalFocusManager.current
            val view = LocalView.current
            SideEffect { touchMode = view.isInTouchMode; viewFocused = view.hasFocus() }
            Column {
                Box(Modifier.size(40.dp).clickable { })
                BasicTextField(rememberTextFieldState(), Modifier.testTag("f").height(40.dp))
            }
        }
        compose.onNodeWithTag("f").performClick()
        compose.onNodeWithTag("f").assertIsFocused()
        fun focused() = compose.onNodeWithTag("f").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { focus.clearFocus(force = true) }
        val at0 = focused()
        compose.mainClock.advanceTimeByFrame()
        val at1 = focused()
        compose.mainClock.advanceTimeByFrame()
        val at2 = focused()
        repeat(5) { compose.mainClock.advanceTimeByFrame() }
        val at7 = focused()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertWithMessage("PROBE focus after clearFocus(force): at0=$at0 at1=$at1 at2=$at2 at7=$at7 idle=${focused()} touchMode=$touchMode viewHadFocus=$viewFocused").that(false).isTrue()
    }
}
