package com.cursorforandroid.ui.panel

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.ui.components.MarkdownCache
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Audit harness: the Project tab's notes are one lazy item ([ProjectTabContent]'s `notes`), so the tab's first frame
 * parses and composes the whole of `notes.md`. Times the first frame of a notes item at several sizes; prints.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-night-420dpi")
class ProjectNotesFirstFrameAuditTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun notes(kb: Int): String = buildString {
        var section = 0
        while (length < kb * 1024) {
            section++
            append("## Workstream $section\n\n")
            append("The coordinator's running notes for workstream $section: what shipped, what the workers are on, and what is blocked. ".repeat(2)).append("\n\n")
            repeat(4) { append("- [${if (it % 2 == 0) "x" else " "}] Task $section.$it — [PR #${section * 10 + it}](https://github.com/acme/app/pull/${section * 10 + it}) by `bc-worker-$it`\n") }
            append("\n")
        }
    }

    @Test
    fun `notes item first frame by size`() {
        var size by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CursorTheme(mode = ThemeMode.Dark) {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (size > 0) item("notes-$size") { PanelMarkdown(notes(size), modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        size = 2; compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        for (kb in listOf(96, 32, 8, 2, 96, 32, 8, 2)) {
            MarkdownCache.parse(notes(kb))
            val parse = (System.nanoTime().let { t -> MarkdownCache.parse(notes(kb) + " "); (System.nanoTime() - t) / 1e6 })
            size = 0; compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val t0 = System.nanoTime()
            size = kb
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val frame = (System.nanoTime() - t0) / 1e6
            println("AUDIT notes ${kb}KB blocks=${MarkdownCache.parse(notes(kb)).size} uncachedParse=${"%.1f".format(parse)}ms firstFrame(parse cached)=${"%.1f".format(frame)}ms")
        }
    }
}
