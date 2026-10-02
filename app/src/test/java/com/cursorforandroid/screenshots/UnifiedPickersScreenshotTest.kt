package com.cursorforandroid.screenshots

import android.content.Context
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.FilterKind
import com.cursorforandroid.domain.NewChatHome
import com.cursorforandroid.ui.agents.AgentRowActions
import com.cursorforandroid.ui.agents.AgentsViewModel
import com.cursorforandroid.ui.agents.Sidebar
import com.cursorforandroid.ui.agents.SidebarCallbacks
import com.cursorforandroid.ui.components.Keyboard
import com.cursorforandroid.ui.components.PickerTags
import com.cursorforandroid.ui.components.pressKey
import com.cursorforandroid.ui.components.rememberPopoverAnchor
import com.cursorforandroid.ui.customize.CHATS_MENU_TAG
import com.cursorforandroid.ui.customize.CustomizeSheet
import com.cursorforandroid.ui.customize.filterTag
import com.cursorforandroid.ui.home.HomeScreen
import com.cursorforandroid.ui.home.MODEL_PICKER_TAG
import com.cursorforandroid.ui.home.NewChatHomeCopy
import com.cursorforandroid.ui.home.NewChatHomeFixtures
import com.cursorforandroid.ui.navigation.SidebarRail
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import com.cursorforandroid.util.AppClock
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.TimeZone

/**
 * Every picker on the one anchored popover, as Cursor's own dropdowns draw it: the model picker and a model's options,
 * the repository, branch and device pickers with the device's Remote submenu, and the chats menu with its Status
 * filter. Each opens from its real chip or button, on a phone (a submenu drilling in place) and a tablet (cascading
 * beside the menu), dark and light: `1100`–`1131`, four frames a picker. `1132`–`1133` are the model picker searched
 * from a hardware keyboard. Written to `screenshots/`; CI compares them pixel for pixel.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_DARK)
class UnifiedPickersScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private lateinit var graph: AppGraph

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        AppClock.nowMillis = { NewChatHomeFixtures.NOW }
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric has no Android Keystore; an ordinary private file stands in, as in AppScreenshotTest.
        graph = AppGraph(context, SecureKeyStore(context) { context.getSharedPreferences("stand-in-secure", Context.MODE_PRIVATE) }, appVersion = SCREENSHOT_APP_VERSION)
        runBlocking {
            graph.session.enterDemo()
            graph.drafts.clear()
            graph.catalog.loadRepositories()
            graph.catalog.loadModels()
            graph.agents.refresh()
        }
    }

    @After
    fun tearDown() {
        runBlocking { graph.drafts.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun onScreen(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun tagged(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** A submenu cascades beside the menu where there is room, and otherwise drills in place behind a back row. */
    private fun submenuOpen() = tagged(PickerTags.Submenu) || tagged(PickerTags.Back)

    private fun inPicker(text: String): SemanticsNodeInteraction =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(PickerTags.List)))

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    private fun showComposer(mode: ThemeMode) {
        compose.setContent {
            CursorTheme(mode = mode) {
                // Ripples on API 31+ animate a noise "sparkle", so a frame caught mid-fade is never reproducible.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Box(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        HomeScreen(
                            graph = graph,
                            listState = NewChatHomeFixtures.list(),
                            onOpenSidebar = {},
                            onOpenAgent = {},
                            onLaunchOpen = {},
                            rowActions = ROW_ACTIONS,
                            modifier = Modifier.fillMaxSize(),
                            home = NewChatHome.COMPOSER,
                            projectsAvailable = true,
                            onNewProject = {},
                            onOpenSettings = {},
                        )
                    }
                }
            }
        }
        // The first composition in a cold sandbox loads the native renderer and the fonts.
        compose.waitUntil(30_000) { onScreen(NewChatHomeCopy.PLACEHOLDER) }
        compose.waitUntil(30_000) { onScreen(MODEL_CHIP) }
        compose.waitUntil(30_000) { onScreen(REPO_CHIP) }
    }

    /** Opens a composer picker from its chip and waits for the popover to settle. */
    private fun openFromChip(mode: ThemeMode, chip: () -> Unit) {
        showComposer(mode)
        chip()
        compose.waitUntil(10_000) { tagged(PickerTags.List) }
        compose.waitForIdle()
    }

    private fun clickChip(label: String) = compose.onNode(hasText(label) and !hasAnyAncestor(hasTestTag(PickerTags.List))).performClick()

    /** The branch chip reads the demo state's branch, so it is found where it sits: between the repository and device chips. */
    private fun clickBranchChip() {
        val repo = compose.onNodeWithText(REPO_CHIP).fetchSemanticsNode().boundsInRoot
        val device = compose.onNodeWithText(DEVICE_CHIP).fetchSemanticsNode().boundsInRoot
        val branch = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().firstOrNull { node ->
            val b = node.boundsInRoot
            b.left >= repo.right && b.right <= device.left && b.center.y in repo.top..repo.bottom
        } ?: error("No branch chip between $REPO_CHIP and $DEVICE_CHIP")
        compose.onNode(SemanticsMatcher("the branch chip") { it.id == branch.id }).performClick()
    }

    private fun modelPicker(mode: ThemeMode, frame: String) {
        openFromChip(mode) { clickChip(MODEL_CHIP) }
        capture(frame)
    }

    /** The selected model's options: its row opens them, in place on a phone and beside the list on a tablet. */
    private fun modelOptions(mode: ThemeMode, frame: String) {
        openFromChip(mode) { clickChip(MODEL_CHIP) }
        inPicker(MODEL_CHIP).performClick()
        compose.waitUntil(10_000) { submenuOpen() }
        capture(frame)
    }

    private fun repositoryPicker(mode: ThemeMode, frame: String) {
        openFromChip(mode) { clickChip(REPO_CHIP) }
        capture(frame)
    }

    private fun branchPicker(mode: ThemeMode, frame: String) {
        openFromChip(mode, ::clickBranchChip)
        compose.waitUntil(10_000) { !onScreen("Loading branches") }
        capture(frame)
    }

    /** The device list settled: its refresh row is a spinner, disabled, while machines and pools are fetched. */
    private fun devicesLoaded() = compose.onAllNodes(hasText(REFRESH_DEVICES) and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    private fun devicePicker(mode: ThemeMode, frame: String) {
        openFromChip(mode) { clickChip(DEVICE_CHIP) }
        compose.waitUntil(20_000) { devicesLoaded() }
        capture(frame)
    }

    private fun deviceRemote(mode: ThemeMode, frame: String) {
        openFromChip(mode) { clickChip(DEVICE_CHIP) }
        compose.waitUntil(20_000) { devicesLoaded() }
        inPicker(REMOTE).performClick()
        compose.waitUntil(10_000) { submenuOpen() && onScreen("bennett") }
        capture(frame)
    }

    /** The rail as the app lays it out, the chats menu anchored to its filter button in the account row. */
    private fun showChatsMenu(mode: ThemeMode) {
        val viewModel = AgentsViewModel(graph)
        compose.setContent {
            CursorTheme(mode = mode) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    val anchor = rememberPopoverAnchor()
                    var open by remember { mutableStateOf(false) }
                    val state by viewModel.uiState.collectAsState()
                    Row(Modifier.fillMaxSize().background(CursorTheme.colors.canvas)) {
                        SidebarRail(expanded = true) {
                            Sidebar(
                                state = state,
                                user = DEMO_USER,
                                isDemo = true,
                                selectedAgentId = null,
                                selectedDestination = null,
                                onQueryChange = {},
                                callbacks = SidebarCallbacks(
                                    onNewChat = {},
                                    onSettings = {},
                                    onCustomize = { open = true },
                                    onToggleSidebar = {},
                                    onRefresh = {},
                                    rowActions = ROW_ACTIONS,
                                    onNewProject = {},
                                    customizeAnchor = anchor,
                                ),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxHeight())
                    }
                    if (open) CustomizeSheet(viewModel, onDismiss = { open = false }, anchor = anchor)
                }
            }
        }
        compose.waitUntil(30_000) { onScreen("Demo User") }
        compose.waitUntil(30_000) { graph.agents.state.value.let { it.hasLoaded && !it.isRefreshing } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(FILTER_BUTTON).performClick()
        compose.waitUntil(10_000) { tagged(CHATS_MENU_TAG) }
        compose.waitForIdle()
    }

    private fun chatsMenu(mode: ThemeMode, frame: String) {
        showChatsMenu(mode)
        capture(frame)
    }

    private fun chatsStatusFilter(mode: ThemeMode, frame: String) {
        showChatsMenu(mode)
        compose.onNodeWithTag(filterTag(FilterKind.Status)).performClick()
        compose.waitUntil(10_000) { submenuOpen() }
        capture(frame)
    }

    /** Typed from a hardware keyboard into the model picker's search, the first match highlighted for Enter. */
    private fun modelSearch(mode: ThemeMode, frame: String) {
        openFromChip(mode) { clickChip(MODEL_CHIP) }
        val picker = compose.onNodeWithTag(MODEL_PICKER_TAG)
        picker.pressKey(NativeKeyEvent.KEYCODE_5, keyboard = Keyboard.Physical)
        compose.waitUntil(10_000) { !onScreen("Gemini") }
        picker.pressKey(NativeKeyEvent.KEYCODE_DPAD_DOWN, keyboard = Keyboard.Physical)
        compose.waitForIdle()
        capture(frame)
    }

    @Test fun modelPhoneDark() = modelPicker(ThemeMode.Dark, "1100_picker_model_phone_dark")
    @Test @Config(sdk = [35], qualifiers = PHONE_LIGHT) fun modelPhoneLight() = modelPicker(ThemeMode.Light, "1101_picker_model_phone_light")
    @Test @Config(sdk = [35], qualifiers = TABLET_DARK) fun modelTabletDark() = modelPicker(ThemeMode.Dark, "1102_picker_model_tablet_dark")
    @Test @Config(sdk = [35], qualifiers = TABLET_LIGHT) fun modelTabletLight() = modelPicker(ThemeMode.Light, "1103_picker_model_tablet_light")

    @Test fun modelOptionsPhoneDark() = modelOptions(ThemeMode.Dark, "1104_picker_model_options_phone_dark")
    @Test @Config(sdk = [35], qualifiers = PHONE_LIGHT) fun modelOptionsPhoneLight() = modelOptions(ThemeMode.Light, "1105_picker_model_options_phone_light")
    @Test @Config(sdk = [35], qualifiers = TABLET_DARK) fun modelOptionsTabletDark() = modelOptions(ThemeMode.Dark, "1106_picker_model_options_tablet_dark")
    @Test @Config(sdk = [35], qualifiers = TABLET_LIGHT) fun modelOptionsTabletLight() = modelOptions(ThemeMode.Light, "1107_picker_model_options_tablet_light")

    @Test fun repositoryPhoneDark() = repositoryPicker(ThemeMode.Dark, "1108_picker_repository_phone_dark")
    @Test @Config(sdk = [35], qualifiers = PHONE_LIGHT) fun repositoryPhoneLight() = repositoryPicker(ThemeMode.Light, "1109_picker_repository_phone_light")
    @Test @Config(sdk = [35], qualifiers = TABLET_DARK) fun repositoryTabletDark() = repositoryPicker(ThemeMode.Dark, "1110_picker_repository_tablet_dark")
    @Test @Config(sdk = [35], qualifiers = TABLET_LIGHT) fun repositoryTabletLight() = repositoryPicker(ThemeMode.Light, "1111_picker_repository_tablet_light")

    @Test fun branchPhoneDark() = branchPicker(ThemeMode.Dark, "1112_picker_branch_phone_dark")
    @Test @Config(sdk = [35], qualifiers = PHONE_LIGHT) fun branchPhoneLight() = branchPicker(ThemeMode.Light, "1113_picker_branch_phone_light")
    @Test @Config(sdk = [35], qualifiers = TABLET_DARK) fun branchTabletDark() = branchPicker(ThemeMode.Dark, "1114_picker_branch_tablet_dark")
    @Test @Config(sdk = [35], qualifiers = TABLET_LIGHT) fun branchTabletLight() = branchPicker(ThemeMode.Light, "1115_picker_branch_tablet_light")

    @Test fun devicePhoneDark() = devicePicker(ThemeMode.Dark, "1116_picker_device_phone_dark")
    @Test @Config(sdk = [35], qualifiers = PHONE_LIGHT) fun devicePhoneLight() = devicePicker(ThemeMode.Light, "1117_picker_device_phone_light")
    @Test @Config(sdk = [35], qualifiers = TABLET_DARK) fun deviceTabletDark() = devicePicker(ThemeMode.Dark, "1118_picker_device_tablet_dark")
    @Test @Config(sdk = [35], qualifiers = TABLET_LIGHT) fun deviceTabletLight() = devicePicker(ThemeMode.Light, "1119_picker_device_tablet_light")

    @Test fun deviceRemotePhoneDark() = deviceRemote(ThemeMode.Dark, "1120_picker_device_remote_phone_dark")
    @Test @Config(sdk = [35], qualifiers = PHONE_LIGHT) fun deviceRemotePhoneLight() = deviceRemote(ThemeMode.Light, "1121_picker_device_remote_phone_light")
    @Test @Config(sdk = [35], qualifiers = TABLET_DARK) fun deviceRemoteTabletDark() = deviceRemote(ThemeMode.Dark, "1122_picker_device_remote_tablet_dark")
    @Test @Config(sdk = [35], qualifiers = TABLET_LIGHT) fun deviceRemoteTabletLight() = deviceRemote(ThemeMode.Light, "1123_picker_device_remote_tablet_light")

    @Test fun chatsPhoneDark() = chatsMenu(ThemeMode.Dark, "1124_picker_chats_phone_dark")
    @Test @Config(sdk = [35], qualifiers = PHONE_LIGHT) fun chatsPhoneLight() = chatsMenu(ThemeMode.Light, "1125_picker_chats_phone_light")
    @Test @Config(sdk = [35], qualifiers = TABLET_DARK) fun chatsTabletDark() = chatsMenu(ThemeMode.Dark, "1126_picker_chats_tablet_dark")
    @Test @Config(sdk = [35], qualifiers = TABLET_LIGHT) fun chatsTabletLight() = chatsMenu(ThemeMode.Light, "1127_picker_chats_tablet_light")

    @Test fun chatsStatusPhoneDark() = chatsStatusFilter(ThemeMode.Dark, "1128_picker_chats_status_phone_dark")
    @Test @Config(sdk = [35], qualifiers = PHONE_LIGHT) fun chatsStatusPhoneLight() = chatsStatusFilter(ThemeMode.Light, "1129_picker_chats_status_phone_light")
    @Test @Config(sdk = [35], qualifiers = TABLET_DARK) fun chatsStatusTabletDark() = chatsStatusFilter(ThemeMode.Dark, "1130_picker_chats_status_tablet_dark")
    @Test @Config(sdk = [35], qualifiers = TABLET_LIGHT) fun chatsStatusTabletLight() = chatsStatusFilter(ThemeMode.Light, "1131_picker_chats_status_tablet_light")

    @Test fun modelSearchPhoneDark() = modelSearch(ThemeMode.Dark, "1132_picker_model_search_keyboard_phone_dark")
    @Test @Config(sdk = [35], qualifiers = TABLET_DARK) fun modelSearchTabletDark() = modelSearch(ThemeMode.Dark, "1133_picker_model_search_keyboard_tablet_dark")

    private companion object {
        val ROW_ACTIONS = AgentRowActions({}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
        val DEMO_USER = CursorUser("Demo", "demo@cursor.local", "Demo", "User", null)

        /** What the demo account's newest chat puts on a new chat's composer: the page has settled once it reads these. */
        const val MODEL_CHIP = "Claude Fable 5.1"
        const val REPO_CHIP = "codex-poly-bot"
        const val DEVICE_CHIP = "Cloud"
        const val REMOTE = "Remote"
        const val REFRESH_DEVICES = "Refresh devices"
        const val FILTER_BUTTON = "Filter and group chats"
    }
}

private const val PHONE_DARK = "w411dp-h914dp-night-420dpi"
private const val PHONE_LIGHT = "w411dp-h914dp-notnight-420dpi"

/** An 11" tablet held sideways (SM-X700: 2560×1600 at xhdpi). */
private const val TABLET_DARK = "w1280dp-h800dp-night-320dpi"
private const val TABLET_LIGHT = "w1280dp-h800dp-notnight-320dpi"
