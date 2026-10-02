package com.cursorforandroid.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ModelParameter
import com.cursorforandroid.domain.ModelParameterValue
import com.cursorforandroid.domain.ModelVariant
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
class ModelSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The documented `GET /v1/models` example: one model, two variants, both named "Composer 2". */
    private val composer = ModelOption(
        id = "composer-2",
        displayName = "Composer 2",
        parameters = listOf(
            ModelParameter("fast", "Fast", listOf(ModelParameterValue("false"), ModelParameterValue("true", "Fast"))),
        ),
        variants = listOf(
            ModelVariant(displayName = "Composer 2", params = listOf(ModelParam("fast", "true")), isDefault = true),
            ModelVariant(displayName = "Composer 2", params = listOf(ModelParam("fast", "false")), isDefault = false),
        ),
    )
    private val sonnet = ModelOption(
        id = "claude-4.6-sonnet-thinking",
        displayName = "Claude 4.6 Sonnet",
        variants = listOf(ModelVariant(displayName = "Claude 4.6 Sonnet", params = emptyList(), isDefault = true)),
    )

    /** The live catalogue's effort × fast grid: four variants, every one named after the model. */
    private val grok = ModelOption(
        id = "cursor-grok-4.6",
        displayName = "Cursor Grok 4.6",
        parameters = listOf(
            ModelParameter("effort", "Effort", listOf(ModelParameterValue("low", "Low"), ModelParameterValue("high", "High"))),
            ModelParameter("fast", "Fast", listOf(ModelParameterValue("false"), ModelParameterValue("true", "Fast"))),
        ),
        variants = listOf("low", "high").flatMap { effort ->
            listOf("true", "false").map { fast ->
                ModelVariant("Cursor Grok 4.6", listOf(ModelParam("effort", effort), ModelParam("fast", fast)), isDefault = effort == "high" && fast == "true")
            }
        },
    )

    private fun ModelOption.variant(vararg params: Pair<String, String>): ModelVariant = variantWithParams(params.toMap())!!

    private var picked: Pair<ModelOption?, ModelVariant?>? = null
    private var dismissed = false
    private var pinned: List<String> = emptyList()

    private fun show(
        models: List<ModelOption>,
        selected: ModelOption? = models.firstOrNull(),
        loading: Boolean = false,
        unavailable: Boolean = false,
        onRefresh: () -> Unit = {},
        pinnedIds: List<String> = emptyList(),
        noModelRow: NoModelRow? = null,
    ) {
        compose.setContent {
            // The host applies what the picker reports; mirror that so it re-renders against the new selection.
            var selectedModel by remember { mutableStateOf(selected) }
            var selectedVariant by remember { mutableStateOf(selected?.defaultVariant) }
            var pins by remember { mutableStateOf(pinnedIds) }
            CursorTheme(mode = ThemeMode.Dark) {
                ModelSheet(
                    models = models,
                    selectedModel = selectedModel,
                    selectedVariant = selectedVariant,
                    loading = loading,
                    unavailable = unavailable,
                    onRefresh = onRefresh,
                    onSelect = { model, variant ->
                        picked = model to variant
                        selectedModel = model
                        selectedVariant = variant
                    },
                    onDismiss = { dismissed = true },
                    pinnedIds = pins,
                    onTogglePin = { id ->
                        pins = if (id in pins) pins - id else listOf(id) + pins
                        pinned = pins
                    },
                    noModelRow = noModelRow,
                )
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Search models")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertAbsent(text: String) = assertThat(compose.onAllNodes(hasText(text)).fetchSemanticsNodes()).isEmpty()

    private fun top(text: String): Float = compose.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot.top

    /** The options' "Fast" switch, not the selected row's muted "Fast" summary. */
    private val fastSwitch get() = compose.onNode(hasText("Fast") and isToggleable())

    private val hasSubmenu = SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)

    /**
     * The API identifies a variant only by `id`+`params` and reuses the model's display name for each one, so a row
     * per variant showed "Composer 2" twice. One row per model; the variant in force is muted after the selected
     * model's name only, as Cursor's picker writes "Grok 4.7 High Fast".
     */
    @Test
    fun `each model is one row, and only the selected one names its variant inline`() {
        show(listOf(grok, composer, sonnet))
        compose.onAllNodesWithText("Composer 2").assertCountEquals(1)
        compose.onNodeWithText("High Fast", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Fast", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Cursor Grok 4.6", substring = true).assertIsSelected()
        assertAbsent("Fast off")
        assertAbsent("High effort")
    }

    @Test
    fun `tapping a model with options selects it at its default variant and opens them`() {
        show(listOf(composer, sonnet, grok))
        compose.onNodeWithText("Cursor Grok 4.6").performClick()
        compose.waitForIdle()
        assertThat(picked).isEqualTo(grok to grok.defaultVariant)
        assertThat(dismissed).isFalse()
        compose.onNodeWithText("Effort").assertIsDisplayed()
        compose.onNodeWithText("High").assertIsSelected()
        compose.onNodeWithText("Low").assertIsDisplayed()
        fastSwitch.assertIsDisplayed()
    }

    @Test
    fun `a choice in a model's options keeps that model and the picker open`() {
        show(listOf(composer, grok))
        compose.onNodeWithText("Cursor Grok 4.6").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Low").performClick()
        compose.waitForIdle()
        // Effort changed, the default's fast stayed: the closest variant with the value asked for.
        assertThat(picked).isEqualTo(grok to grok.variant("effort" to "low", "fast" to "true"))
        assertThat(dismissed).isFalse()
        compose.onNodeWithText("Low").assertIsSelected()
    }

    @Test
    fun `flipping a switch reports the model with the matching variant and keeps the picker open`() {
        show(listOf(composer))
        compose.onNodeWithText("Composer 2", substring = true).performClick()
        compose.waitForIdle()
        fastSwitch.performClick()
        compose.waitForIdle()
        assertThat(picked).isEqualTo(composer to composer.variant("fast" to "false"))
        assertThat(dismissed).isFalse()
    }

    @Test
    fun `a model with nothing to choose is picked and the picker closes`() {
        show(listOf(composer, sonnet))
        compose.onNodeWithText("Claude 4.6 Sonnet").performClick()
        compose.waitUntil(10_000) { dismissed }
        assertThat(picked).isEqualTo(sonnet to sonnet.defaultVariant)
    }

    @Test
    fun `a model whose variants leave nothing to choose has no submenu`() {
        show(listOf(sonnet, composer), selected = null)
        compose.onAllNodes(hasText("Claude 4.6 Sonnet") and hasSubmenu).assertCountEquals(0)
        compose.onAllNodes(hasText("Composer 2") and hasSubmenu).assertCountEquals(1)
        compose.onNodeWithContentDescription("Pin Claude 4.6 Sonnet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pin Composer 2").assertIsDisplayed()
    }

    @Test
    fun `pinning a model reports it and an already-pinned model can be unpinned`() {
        show(listOf(composer, grok), pinnedIds = listOf(composer.id))
        compose.onNodeWithContentDescription("Unpin Composer 2").performClick()
        compose.waitForIdle()
        assertThat(pinned).isEmpty()
        compose.onNodeWithContentDescription("Pin Cursor Grok 4.6").performClick()
        compose.waitForIdle()
        assertThat(pinned).containsExactly(grok.id)
        compose.onNodeWithContentDescription("Unpin Cursor Grok 4.6").assertIsDisplayed()
        assertThat(dismissed).isFalse()
    }

    @Test
    fun `a pinned model that is not selected still sits under the selection`() {
        show(listOf(composer, sonnet, grok), selected = composer, pinnedIds = listOf(grok.id))
        assertThat(top("Composer 2")).isLessThan(top("Cursor Grok 4.6"))
        assertThat(top("Cursor Grok 4.6")).isLessThan(top("Claude 4.6 Sonnet"))
    }

    /** Picking a model must not move it out from under the finger, or from beside the options it opened. */
    @Test
    fun `the order the picker opened with holds while it is open`() {
        show(listOf(composer, sonnet, grok))
        compose.onNodeWithText("Cursor Grok 4.6").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PickerTags.Back).performClick()
        compose.waitForIdle()
        assertThat(top("Composer 2")).isLessThan(top("Claude 4.6 Sonnet"))
        assertThat(top("Claude 4.6 Sonnet")).isLessThan(top("Cursor Grok 4.6"))
    }

    @Test
    fun `typing filters the models and says when nothing matches`() {
        show(listOf(composer, sonnet, grok))
        compose.onNodeWithTag(PickerTags.Search).performTextInput("son")
        compose.waitForIdle()
        compose.onNodeWithText("Claude 4.6 Sonnet").assertIsDisplayed()
        assertAbsent("Composer 2")
        assertAbsent("Cursor Grok 4.6")
        compose.onNodeWithTag(PickerTags.Search).performTextInput("zzz")
        compose.waitForIdle()
        compose.onNodeWithText("No models match \u201Csonzzz\u201D").assertIsDisplayed()
    }

    @Test
    fun `a failed catalogue load says so and is refreshed from the foot instead of loading forever`() {
        var refreshes = 0
        show(emptyList(), unavailable = true, onRefresh = { refreshes++ })
        compose.onNodeWithText("Couldn't load the model list. Refresh to try again.").assertIsDisplayed()
        compose.onNodeWithText("Refresh models").performClick()
        compose.waitForIdle()
        assertThat(refreshes).isEqualTo(1)
        assertThat(dismissed).isFalse()
        assertThat(compose.onAllNodes(hasText("Loading models", substring = true)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun `an empty catalogue shows the loading row while the request is in flight`() {
        show(emptyList(), loading = true)
        compose.onNodeWithText("Loading models…").assertIsDisplayed()
    }

    @Test
    fun `the refresh row sits under the models and asks for the list again`() {
        var refreshes = 0
        show(listOf(composer, sonnet), onRefresh = { refreshes++ })
        assertThat(top("Refresh models")).isGreaterThan(top("Claude 4.6 Sonnet"))
        compose.onNodeWithText("Refresh models").performClick()
        compose.waitForIdle()
        assertThat(refreshes).isEqualTo(1)
    }

    @Test
    fun `the refresh row spins and takes no tap while the list is fetched, and the list shown stays`() {
        show(listOf(composer, sonnet), loading = true)
        compose.onNodeWithText("Refresh models").assertIsNotEnabled()
        compose.onNodeWithText("Claude 4.6 Sonnet").assertIsDisplayed()
    }

    @Test
    fun `a refresh started while the picker is up turns its row off, and finishing turns it back on`() {
        var loading by mutableStateOf(false)
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ModelSheet(
                    models = listOf(composer, sonnet),
                    selectedModel = composer,
                    selectedVariant = composer.defaultVariant,
                    loading = loading,
                    unavailable = false,
                    onRefresh = {},
                    onSelect = { _, _ -> },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Refresh models").assertIsEnabled()
        loading = true
        compose.waitForIdle()
        compose.onNodeWithText("Refresh models").assertIsNotEnabled()
        loading = false
        compose.waitForIdle()
        compose.onNodeWithText("Refresh models").assertIsEnabled()
    }

    @Test
    fun `the picker has no Default row and no options of its own until a model opens them`() {
        show(listOf(sonnet))
        assertAbsent("Default")
        assertAbsent("Your Cursor default model")
        assertAbsent("Options")
        assertAbsent("Plan mode")
        assertAbsent("Auto-create PR")
        compose.onNodeWithText("Claude 4.6 Sonnet").assertIsDisplayed()
    }

    /** On a follow-up the row stands for the chat's current model, and picking it reports no model at all. */
    @Test
    fun `the no-model row reads as the caller says and still reports no model`() {
        show(listOf(sonnet), selected = null, noModelRow = NoModelRow("Current model", "Keep the model this chat has been using"))
        compose.onNodeWithText("Keep the model this chat has been using", useUnmergedTree = true).assertIsDisplayed()
        assertAbsent("Default")
        compose.onNodeWithText("Current model", substring = true).performClick()
        compose.waitUntil(10_000) { dismissed }
        assertThat(picked).isEqualTo(null to null)
    }

    /** A chat whose model the catalog lists needs no extra row. */
    @Test
    fun `without a no-model row none is shown`() {
        show(listOf(sonnet), noModelRow = null)
        compose.onNodeWithText("Claude 4.6 Sonnet").assertIsDisplayed()
        assertAbsent("Default")
        assertAbsent("Current model")
    }

    /** A phone's rows are full touch targets: 48dp, as Android asks, where the desktop's are 28. */
    @Test
    fun `a phone's model rows are 48dp touch targets`() {
        show(listOf(composer, sonnet))
        val row = compose.onNodeWithText("Claude 4.6 Sonnet").fetchSemanticsNode().boundsInRoot
        assertThat(row.height / compose.density.density).isWithin(0.5f).of(48f)
    }

    /** At the system's largest font a row grows round its label rather than cutting it off. */
    @Test
    @Config(fontScale = 2f)
    fun `a row grows round its label at the largest system font`() {
        show(listOf(composer, sonnet))
        val row = compose.onNodeWithText("Claude 4.6 Sonnet").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("Claude 4.6 Sonnet", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertThat(row.top).isAtMost(label.top)
        assertThat(row.bottom).isAtLeast(label.bottom)
    }
}
