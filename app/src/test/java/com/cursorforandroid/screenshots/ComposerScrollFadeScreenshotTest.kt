package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.ComposerBox
import com.cursorforandroid.ui.components.ComposerMenuActions
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The composer's text fading at the edges it runs past: at the top of a long prompt, scrolled into its middle, and
 * expanded with still more than fits. Same device qualifiers as [AppScreenshotTest].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ComposerScrollFadeScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(mode: ThemeMode, lines: Int) {
        val prompt = (1..lines).joinToString("\n") { "$it. Step $it of making resume survive a dropped connection." }
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    val colors = CursorTheme.colors
                    Column(Modifier.fillMaxSize().background(colors.canvas).padding(16.dp)) {
                        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                            Text("Transcript", color = colors.textSecondary)
                        }
                        ComposerBox(
                            value = prompt,
                            onValueChange = {},
                            placeholder = "Follow up…",
                            onSend = {},
                            plusMenu = ComposerMenuActions(onPickMedia = {}),
                            modelLabel = "Claude Fable 5.1",
                            onModel = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun scrollIntoMiddle() {
        compose.onNode(hasSetTextAction()).performTouchInput { swipeUp(startY = centerY + 60f, endY = centerY - 60f) }
    }

    @Test
    fun atTheTop() {
        show(ThemeMode.Dark, lines = 18)
        capture("680_composer_scroll_fade_top")
    }

    @Test
    fun inTheMiddle() {
        show(ThemeMode.Dark, lines = 18)
        scrollIntoMiddle()
        capture("681_composer_scroll_fade_middle")
    }

    @Test
    fun expandedAtTheEnd() {
        show(ThemeMode.Dark, lines = 40)
        compose.onNode(hasTestTag("composer-expand"), useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(1_000)
        repeat(6) { compose.onNode(hasSetTextAction()).performTouchInput { swipeUp() } }
        capture("682_composer_scroll_fade_expanded_end")
    }

    @Test
    fun inTheMiddleLight() {
        show(ThemeMode.Light, lines = 18)
        scrollIntoMiddle()
        capture("683_composer_scroll_fade_middle_light")
    }
}
