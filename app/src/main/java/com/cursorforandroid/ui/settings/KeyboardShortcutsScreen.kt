package com.cursorforandroid.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.fadingVerticalScroll
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.shortcuts.Chord
import com.cursorforandroid.ui.shortcuts.ConflictResolution
import com.cursorforandroid.ui.shortcuts.KeyChord
import com.cursorforandroid.ui.shortcuts.LocalKeyboardShortcuts
import com.cursorforandroid.ui.shortcuts.Shortcut
import com.cursorforandroid.ui.shortcuts.ShortcutBindings
import com.cursorforandroid.ui.shortcuts.ShortcutLineRow
import com.cursorforandroid.ui.shortcuts.ShortcutsCopy
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme

object KeyboardShortcutsTags {
    const val PAGE = "keyboard_shortcuts_page"
    const val CAPTURE = "keyboard_shortcuts_capture"
    const val SWAP = "keyboard_shortcuts_swap"
    const val REPLACE = "keyboard_shortcuts_replace"
    const val CANCEL = "keyboard_shortcuts_cancel"
    const val RESET_ALL = "keyboard_shortcuts_reset_all"

    fun row(shortcut: Shortcut) = "keyboard_shortcut_${shortcut.id}"

    fun reset(shortcut: Shortcut) = "keyboard_shortcut_reset_${shortcut.id}"
}

object KeyboardShortcutsCopy {
    const val PRESS = "Press the new keys. Esc cancels."
    const val WAITING = "Press keys"
    const val CHANGE = "Change keys"
    const val SWAP = "Swap"
    const val REPLACE = "Replace"
    const val CANCEL = "Cancel"
    const val RESET_ALL = "Reset all shortcuts"
    const val RESET_ALL_DETAIL = "Puts every shortcut back on its default keys."
    const val ALL_DEFAULT = "Every shortcut is on its default keys."

    fun keys(chords: List<KeyChord>): String = chords.joinToString(" or ") { it.label }

    fun resetTo(shortcut: Shortcut) = "Reset to ${keys(shortcut.defaults)}"

    /** "Ctrl+B is already on “Show or hide the sidebar”." */
    fun taken(change: PendingChange): String = change.conflicts.joinToString(" ") { (chord, owner) ->
        if (change.reset) "${chord.label} is on “${owner.label}” now." else "${chord.label} is already on “${owner.label}”."
    }

    /** "Swap moves it to Ctrl+Shift+E. Replace leaves it with no shortcut." */
    fun choice(swapTo: List<KeyChord>, replaceLeaves: List<KeyChord>): String {
        val replace = if (replaceLeaves.isEmpty()) "Replace leaves it with no shortcut." else "Replace leaves it on ${keys(replaceLeaves)}."
        return if (swapTo.isEmpty()) replace else "Swap moves it to ${keys(swapTo)}. $replace"
    }
}

/**
 * Settings › Keyboard shortcuts: every chord the app answers from a hardware keyboard, in the groups the cheat sheet
 * (Ctrl+/) shows them in, from the same copy ([ShortcutsCopy]). A shortcut that can move is a row to tap: it then
 * waits for the new keys ([ShortcutCapture]), says so when they are kept for something else, and asks whether to swap
 * or replace when another shortcut is on them — never leaving two on the same keys. A moved shortcut has a reset beside
 * its keys, and the page ends on resetting them all. [onChange] saves; [bindings] is what is saved.
 */
@Composable
fun KeyboardShortcutsScreen(
    bindings: ShortcutBindings,
    onChange: (ShortcutBindings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    // Shown at once, ahead of the store's write coming back, so a second change builds on the first.
    var shown by remember { mutableStateOf(bindings) }
    LaunchedEffect(bindings) { shown = bindings }
    val save by rememberUpdatedState(onChange)
    val capture = remember {
        ShortcutCapture(bindings = { shown }) { next ->
            shown = next
            save(next)
        }
    }
    val capturing = capture.target != null
    val keyboard = LocalKeyboardShortcuts.current
    if (capturing && keyboard != null) {
        DisposableEffect(keyboard) {
            val release = keyboard.capture(capture::onKey)
            onDispose { release() }
        }
    }
    BackHandler(enabled = capturing) { capture.cancel() }

    Column(modifier.fillMaxSize().background(colors.canvas).testTag(KeyboardShortcutsTags.PAGE)) {
        CursorHeader(
            title = ShortcutsCopy.TITLE,
            leading = { FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack) },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .navigationBarsPadding()
                .fadingVerticalScroll(surface = colors.canvas)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A reading column, centred on a wide pane.
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Text(ShortcutsCopy.SETTINGS_INTRO, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(top = 12.dp, start = 2.dp))
                ShortcutsCopy.groups(shown).forEach { group ->
                    Group(group.title)
                    SettingsCard {
                        group.lines.forEachIndexed { index, line ->
                            if (index > 0) HairlineDivider()
                            val shortcut = line.shortcut
                            if (shortcut == null) {
                                ShortcutLineRow(line, Modifier.heightIn(min = CursorDimens.listRow).padding(horizontal = RowInset, vertical = 11.dp))
                            } else {
                                BindingRow(line, shortcut, customized = !shown.isDefault(shortcut), capture = capture)
                            }
                        }
                    }
                }
                SettingsCard(Modifier.padding(top = 18.dp)) {
                    SettingsRow(
                        title = KeyboardShortcutsCopy.RESET_ALL,
                        description = if (shown.isAllDefault) KeyboardShortcutsCopy.ALL_DEFAULT else KeyboardShortcutsCopy.RESET_ALL_DETAIL,
                        modifier = Modifier.testTag(KeyboardShortcutsTags.RESET_ALL),
                        onClick = capture::resetAll,
                        enabled = !shown.isAllDefault,
                        leading = { RowGlyph(CursorIcons.Reset) },
                    )
                }
                Text(ShortcutsCopy.TEXT_EDITING, style = type.small, color = colors.textQuaternary, modifier = Modifier.padding(top = 12.dp, start = 2.dp))
            }
        }
    }
}

/** A shortcut whose keys can change: tapped, it waits for them, and the panel under it says what happens next. */
@Composable
private fun BindingRow(line: ShortcutsCopy.Line, shortcut: Shortcut, customized: Boolean, capture: ShortcutCapture) {
    val colors = CursorTheme.colors
    val capturing = capture.target == shortcut
    Column(Modifier.fillMaxWidth().background(if (capturing) colors.fillFaint else Color.Transparent)) {
        ShortcutLineRow(
            line,
            Modifier
                .testTag(KeyboardShortcutsTags.row(shortcut))
                .pressable({ if (capturing) capture.cancel() else capture.start(shortcut) }, RectangleShape)
                .heightIn(min = CursorDimens.listRow)
                .padding(horizontal = RowInset, vertical = 11.dp),
            beforeChords = if (customized && !capturing) {
                {
                    FlatIconButton(
                        CursorIcons.Reset,
                        KeyboardShortcutsCopy.resetTo(shortcut),
                        onClick = { capture.reset(shortcut) },
                        modifier = Modifier.testTag(KeyboardShortcutsTags.reset(shortcut)),
                        size = 28.dp,
                        iconSize = 14.dp,
                        tint = colors.iconTertiary,
                    )
                    Spacer(Modifier.width(6.dp))
                }
            } else {
                null
            },
            trailing = if (capturing) ({ CapturePill(capture) }) else null,
        )
        if (capturing) CapturePanel(capture)
    }
}

/** Where the keys will go: the chord waiting on a choice, the modifiers held so far, or a prompt. */
@Composable
private fun CapturePill(capture: ShortcutCapture) {
    val colors = CursorTheme.colors
    val shape = CursorTheme.shapes.sm
    val pending = capture.pending?.takeUnless { it.reset }?.chords?.single()
    Box(
        Modifier.height(26.dp).border(CursorDimens.hairline, colors.accent, shape).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            pending != null -> Chord(pending.keys)
            capture.held.isNotEmpty() -> Chord(capture.held + "…")
            else -> Text(KeyboardShortcutsCopy.WAITING, style = CursorTheme.typography.small, color = colors.accent, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/**
 * Under the row being changed: the prompt, why the keys pressed cannot be used, or who is on them already with the
 * swap and the replace spelled out; and the buttons. With no hardware-keyboard reader around it (previews, tests of the
 * page alone) it reads the keys itself, focused as it opens.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun CapturePanel(capture: ShortcutCapture) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val focus = remember { FocusRequester() }
    val inView = remember { BringIntoViewRequester() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val clearFade = with(LocalDensity.current) { CursorDimens.scrollFade.toPx() }
    LaunchedEffect(capture.target) { runCatching { focus.requestFocus() } }
    val pending = capture.pending
    // Opened on the last rows, or grown by a conflict's buttons, the panel would sit under the page's bottom edge.
    LaunchedEffect(capture.target, pending, capture.problem, size) {
        if (size != IntSize.Zero) inView.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + clearFade))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { size = it }
            .bringIntoViewRequester(inView)
            .focusRequester(focus)
            .onPreviewKeyEvent { capture.onKey(it.nativeKeyEvent) }
            .focusable()
            .testTag(KeyboardShortcutsTags.CAPTURE)
            .padding(start = RowInset, end = RowInset, bottom = 12.dp),
    ) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when {
                pending != null -> {
                    Text(KeyboardShortcutsCopy.taken(pending), style = type.small, color = colors.textPrimary)
                    val owner = pending.conflicts.first().second
                    Text(
                        KeyboardShortcutsCopy.choice(capture.swapGives(pending), capture.replaceLeaves(pending, owner)),
                        style = type.small,
                        color = colors.textTertiary,
                    )
                }
                capture.problem != null -> Text(capture.problem.orEmpty(), style = type.small, color = colors.orange)
                else -> Text(KeyboardShortcutsCopy.PRESS, style = type.small, color = colors.textTertiary)
            }
        }
        Spacer(Modifier.height(10.dp))
        // Wraps on a narrow pane rather than pushing Swap out of sight.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CursorButton(KeyboardShortcutsCopy.CANCEL, onClick = capture::cancel, height = 30.dp, modifier = Modifier.testTag(KeyboardShortcutsTags.CANCEL))
            if (pending != null) {
                val swap = capture.swapGives(pending).isNotEmpty()
                CursorButton(
                    KeyboardShortcutsCopy.REPLACE,
                    onClick = { capture.resolve(ConflictResolution.Replace) },
                    primary = !swap,
                    height = 30.dp,
                    modifier = Modifier.testTag(KeyboardShortcutsTags.REPLACE),
                )
                if (swap) {
                    CursorButton(
                        KeyboardShortcutsCopy.SWAP,
                        onClick = { capture.resolve(ConflictResolution.Swap) },
                        primary = true,
                        height = 30.dp,
                        modifier = Modifier.testTag(KeyboardShortcutsTags.SWAP),
                    )
                }
            }
        }
    }
}
