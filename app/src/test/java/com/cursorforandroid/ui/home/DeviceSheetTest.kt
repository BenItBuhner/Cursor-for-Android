package com.cursorforandroid.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.DeviceOption
import com.cursorforandroid.domain.DeviceSection
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.KnownDevices
import com.cursorforandroid.ui.components.PickerTags
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
class DeviceSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val bennett = DeviceOption(
        target = DeviceTarget.machine("bennett"),
        subtitle = "Busy · bennett/codex-poly-bot",
        online = true,
        section = DeviceSection.Machines,
    )
    private val studio = DeviceOption(
        target = DeviceTarget.machine("studio-mac"),
        subtitle = "Offline · last seen yesterday",
        online = false,
        section = DeviceSection.Machines,
    )
    private val gpu = DeviceOption(
        target = DeviceTarget.pool("gpu"),
        subtitle = "2 connected · 1 in use",
        online = true,
        section = DeviceSection.Pools,
    )
    private val devices = listOf(KnownDevices.cloud, bennett, studio, gpu)

    private var picked: DeviceTarget? = null
    private var dismissed = false

    private fun show(selected: DeviceTarget = DeviceTarget.Cloud, rows: List<DeviceOption> = devices) {
        compose.setContent {
            var current by remember { mutableStateOf(selected) }
            CursorTheme(mode = ThemeMode.Dark) {
                DeviceSheet(
                    devices = rows,
                    selected = current,
                    loading = false,
                    onSelect = {
                        picked = it
                        current = it
                    },
                    onRefresh = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(REMOTE).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openRemote() {
        compose.onNodeWithText(REMOTE, substring = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Search remote machines…").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `cloud is the default row, remote holds the rest, and this phone is not offered`() {
        show()
        compose.onNodeWithText("Cloud").assertIsSelected()
        compose.onNodeWithText("Cursor-hosted VM", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("This device").assertDoesNotExist()
        compose.onNodeWithText("Local").assertDoesNotExist()
        compose.onNodeWithText("This phone can't run an agent itself", substring = true).assertExists()
        compose.onNodeWithText("bennett").assertDoesNotExist()
        openRemote()
        compose.onNodeWithText("My machines").assertExists()
        compose.onNodeWithText("bennett", substring = true).assertExists()
        compose.onNodeWithText("Team pools").assertExists()
        compose.onNodeWithText("gpu", substring = true).assertExists()
    }

    @Test
    fun `remote names the machine in use and checks it`() {
        show(selected = bennett.target)
        compose.onNodeWithText(REMOTE, substring = true).assertIsSelected()
        compose.onNodeWithText("bennett", useUnmergedTree = true).assertExists()
        openRemote()
        compose.onNodeWithText("Busy · bennett/codex-poly-bot", substring = true).assertIsSelected()
    }

    @Test
    fun `picking a machine reports that target and closes the picker`() {
        show()
        openRemote()
        compose.onNodeWithText("bennett", substring = true).performClick()
        assertThat(picked).isEqualTo(DeviceTarget.machine("bennett"))
        compose.waitUntil(10_000) { dismissed }
    }

    @Test
    fun `an offline machine is still offered, dimmed`() {
        show()
        openRemote()
        compose.onNodeWithText("studio-mac", substring = true).performClick()
        assertThat(picked).isEqualTo(DeviceTarget.machine("studio-mac"))
    }

    @Test
    fun `a typed name is offered as a machine and as a pool`() {
        show()
        openRemote()
        compose.onNodeWithTag(PickerTags.Search).performTextInput("lab")
        compose.onNodeWithText("Use \u201Clab\u201D as a machine").assertExists()
        compose.onNodeWithText("Use \u201Clab\u201D as a team pool").assertExists()
        compose.onNodeWithText("Use \u201Clab\u201D as a machine").performClick()
        assertThat(picked).isEqualTo(DeviceTarget.machine("lab"))
    }

    @Test
    fun `the open submenu and its filter survive the process being killed`() {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                DeviceSheet(
                    devices = devices,
                    selected = DeviceTarget.Cloud,
                    loading = false,
                    onSelect = {},
                    onRefresh = {},
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(REMOTE).fetchSemanticsNodes().isNotEmpty() }
        openRemote()
        compose.onNodeWithTag(PickerTags.Search).performTextInput("gpu")
        compose.onNodeWithText("2 connected \u00b7 1 in use", substring = true, useUnmergedTree = true).assertExists()

        restorer.emulateSavedInstanceStateRestore()

        compose.waitUntil(10_000) { compose.onAllNodes(hasText("2 connected \u00b7 1 in use")).fetchSemanticsNodes().isNotEmpty() }
        assertThat(compose.onAllNodes(hasText("bennett")).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `search hides unmatched sections`() {
        show()
        openRemote()
        compose.onNodeWithTag(PickerTags.Search).performTextInput("gpu")
        compose.onNodeWithText("Team pools").assertExists()
        assertThat(compose.onAllNodes(hasText("My machines")).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasText("bennett")).fetchSemanticsNodes()).isEmpty()
    }
}
