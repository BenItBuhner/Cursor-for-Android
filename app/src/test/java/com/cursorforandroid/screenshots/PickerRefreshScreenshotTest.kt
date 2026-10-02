package com.cursorforandroid.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.cursorforandroid.ui.components.PickerTags
import com.cursorforandroid.ui.home.ModelSheet
import com.cursorforandroid.ui.home.RepositorySheet
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
 * The pickers' refresh row, at the foot of the list as Cursor's dropdowns have it: the model picker's (`339`), a
 * spinner in its glyph's place and the row off while the list is fetched, the list shown kept (`340`), and the failed
 * first load, whose note points at it rather than offering a button of its own (`341`); the repository picker's
 * beside them for the match (`342`). Written to `screenshots/`; CI compares them pixel for pixel.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class PickerRefreshScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("user.dir"), "../screenshots").normalize()
    private val models = LiveModelCatalog.models
    private val repositories = listOf("app", "billing", "infra", "web", "payments-service").map { Repository("https://github.com/acme/$it") }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) { content() }
            }
        }
    }

    @Composable
    private fun Models(loading: Boolean = false, unavailable: Boolean = false, shown: Boolean = true) {
        val list = if (shown) models else emptyList()
        val selected = list.firstOrNull { it.id == "claude-opus-5.5" } ?: list.firstOrNull()
        ModelSheet(
            models = list,
            selectedModel = selected,
            selectedVariant = selected?.defaultVariant,
            loading = loading,
            unavailable = unavailable,
            onRefresh = {},
            onSelect = { _, _ -> },
            onDismiss = {},
        )
    }

    private fun waitForText(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun toTheFoot() {
        compose.onNodeWithTag(PickerTags.List).performScrollToNode(hasText("Refresh models"))
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path, RoborazziOptions())
    }

    @Test
    fun modelPickerHeader() {
        show { Models() }
        waitForText("Claude Opus 5.5")
        toTheFoot()
        capture("339_model_picker_header")
    }

    @Test
    fun modelPickerHeaderRefreshing() {
        // The spinner turns for as long as the fetch is out: the frame is taken at a fixed point of its turn. The
        // list is scrolled to its foot while the clock still runs, since a scroll never settles with it held.
        show { Models(loading = true) }
        waitForText("Claude Opus 5.5")
        toTheFoot()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Refresh models").assertIsNotEnabled()
        captureScreenRoboImage(File(outDir, "340_model_picker_header_refreshing.png").path, RoborazziOptions())
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun modelPickerHeaderAfterAFailedLoad() {
        show { Models(unavailable = true, shown = false) }
        waitForText("Couldn't load the model list")
        capture("341_model_picker_header_unavailable")
    }

    @Test
    fun repositoryPickerHeader() {
        show {
            RepositorySheet(
                repos = repositories,
                recent = emptyList(),
                selected = repositories.first(),
                noRepo = false,
                loading = false,
                unavailable = false,
                onSelect = {},
                onRefresh = {},
                onDismiss = {},
            )
        }
        waitForText("payments-service")
        capture("342_repository_picker_header")
    }
}
