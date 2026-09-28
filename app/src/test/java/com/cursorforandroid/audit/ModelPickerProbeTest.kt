package com.cursorforandroid.audit

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelParam
import com.cursorforandroid.domain.ModelParameter
import com.cursorforandroid.domain.ModelParameterValue
import com.cursorforandroid.domain.ModelSearch
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.arrangedForPicker
import com.cursorforandroid.ui.components.SlashMenu
import com.cursorforandroid.ui.components.SlashOffer
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.ui.home.ModelSheet
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ModelPickerProbeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() = RecomposeCounter.install()

    private fun model(i: Int): ModelOption {
        val family = listOf("Claude Opus", "Claude Sonnet", "GPT", "Gemini Pro", "Grok Code", "Composer", "Kimi", "DeepSeek")[i % 8]
        val version = "${4 + i / 8 % 3}.${i % 10}"
        return ModelOption(
            id = "${family.lowercase().replace(' ', '-')}-$version-$i",
            displayName = "$family $version${if (i % 5 == 0) " Thinking Extended Context Preview Edition" else ""}",
            parameters = listOf(
                ModelParameter("effort", "Effort", listOf(ModelParameterValue("low", "Low"), ModelParameterValue("medium", "Medium"), ModelParameterValue("high", "High"), ModelParameterValue("max", "Max"))),
                ModelParameter("fast", "Fast", listOf(ModelParameterValue("false"), ModelParameterValue("true", "Fast"))),
                ModelParameter("context", "Context", listOf(ModelParameterValue("200k", "200K"), ModelParameterValue("1m", "1M"))),
            ),
            variants = listOf("low", "medium", "high", "max").flatMap { e ->
                listOf("true", "false").map { f -> ModelVariant("$family $version", listOf(ModelParam("effort", e), ModelParam("fast", f)), isDefault = e == "medium" && f == "false") }
            },
        )
    }

    private fun catalog(n: Int) = listOf(ModelOption("default", "Auto")) + (0 until n).map(::model)

    @Test
    fun selectingAModelRecomposesTheSheet() {
        val models = catalog(40)
        var selected by mutableStateOf<ModelOption?>(models[1])
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                ModelSheet(
                    models = models, selectedModel = selected, selectedVariant = selected?.variants?.firstOrNull(),
                    planMode = false, autoCreatePr = false, loading = false, unavailable = false,
                    onPlanMode = null, onAutoCreatePr = null, onRefresh = {},
                    onSelect = { m, _ -> selected = m }, onDismiss = {},
                )
            }
        }
        compose.waitForIdle()
        RecomposeCounter.reset()
        val target = models[6].displayName
        compose.onAllNodes(hasText(target)).onFirst().performClick()
        compose.waitForIdle()
        AuditLog.line("=== ModelSheet, 41 rows, select row 6 ===")
        AuditLog.line("ModelRow=${RecomposeCounter.count("ModelRow")} PinButton=${RecomposeCounter.count("PinButton")} ModelPickers=${RecomposeCounter.count("ModelPickers")} ChoiceChip=${RecomposeCounter.count("ChoiceChip")}")
        AuditLog.line(RecomposeCounter.top(15))
    }

    @Test
    fun searchTimings() {
        AuditLog.line("=== ModelSearch / SlashMenu timings (JVM, per call, median of 30) ===")
        val queries = listOf("o", "op", "opu", "opus", "opus ", "opus 4", "opus 4.", "opus 4.5", "opus 4.5 m", "opus 4.5 max")
        for (n in listOf(30, 100, 400)) {
            val models = catalog(n)
            repeat(20) { queries.forEach { q -> ModelSearch.search(models, q) } } // warm
            val perQuery = queries.map { q ->
                val samples = (0 until 30).map { val t = System.nanoTime(); ModelSearch.search(models, q); System.nanoTime() - t }.sorted()
                samples[15] / 1000
            }
            val slash = queries.map { q ->
                val samples = (0 until 30).map { val t = System.nanoTime(); SlashMenu.items(q, SlashCatalog.BUILT_IN, emptyList(), SlashOffer(models = models)); System.nanoTime() - t }.sorted()
                samples[15] / 1000
            }
            val arrange = (0 until 30).map { val t = System.nanoTime(); models.arrangedForPicker(listOf(models[3].id, models[7].id), models[5].id); System.nanoTime() - t }.sorted()[15] / 1000
            AuditLog.line("n=$n ModelSearch us/keystroke=${perQuery} max=${perQuery.max()} | SlashMenu.items us=${slash} max=${slash.max()} | arrangedForPicker us=$arrange")
        }
    }
}
