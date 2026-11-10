package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.DeviceOption
import com.cursorforandroid.domain.DeviceSection
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.MachineWorker
import com.cursorforandroid.ui.home.MachineOffer
import com.cursorforandroid.ui.home.NewChatSelectors
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
 * The offer of one of the user's computers under the New Chat selectors: at rest in dark and in light, with a long
 * machine name, and half way through opening, so the motion has a still to be reviewed by. The clock is held, so the
 * presence dot's breath is caught at the same instant every run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h160dp-night-420dpi")
class MachineOfferScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()

    private fun machine(name: String) = DeviceOption(
        target = DeviceTarget.machine(name),
        subtitle = "Online",
        online = true,
        section = DeviceSection.Machines,
        worker = MachineWorker(workerId = "pw_demo", name = name),
    )

    private fun frame(name: String, mode: ThemeMode = ThemeMode.Dark, machineName: String = "Bennett\u2019s MacBook Pro", openForMillis: Long = SettledMillis) {
        compose.mainClock.autoAdvance = false
        var offer by mutableStateOf<DeviceOption?>(null)
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Column(Modifier.fillMaxSize().background(CursorTheme.colors.canvas).padding(16.dp)) {
                        NewChatSelectors("cursor-for-android", noRepo = false, branchLabel = "default", device = DeviceTarget.Cloud, onRepo = {}, onBranch = {}, onDevice = {})
                        MachineOffer(offer, onRun = {}, onDismiss = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnUiThread { offer = machine(machineName) }
        compose.mainClock.advanceTimeBy(openForMillis)
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun offered() = frame("1150_machine_offer_dark")

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h160dp-notnight-420dpi")
    fun offeredLight() = frame("1151_machine_offer_light", mode = ThemeMode.Light)

    @Test
    fun longName() = frame("1152_machine_offer_long_name", machineName = "bennetts-macbook-pro-16-inch-2025.local")

    @Test
    fun opening() = frame("1153_machine_offer_opening", openForMillis = 96)

    private companion object {
        const val SettledMillis = 1_200L
    }
}
