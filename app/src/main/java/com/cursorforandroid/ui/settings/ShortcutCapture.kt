package com.cursorforandroid.ui.settings

import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cursorforandroid.ui.shortcuts.ChordCheck
import com.cursorforandroid.ui.shortcuts.ConflictResolution
import com.cursorforandroid.ui.shortcuts.KeyChord
import com.cursorforandroid.ui.shortcuts.Shortcut
import com.cursorforandroid.ui.shortcuts.ShortcutBindings
import com.cursorforandroid.ui.shortcuts.ShortcutRules

/**
 * A change that takes keys another shortcut is on, waiting for the user to swap or replace: the new keys for
 * [target] (one chord, or its defaults on a [reset]), and each chord with the shortcut on it now.
 */
data class PendingChange(val target: Shortcut, val chords: List<KeyChord>, val conflicts: List<Pair<KeyChord, Shortcut>>, val reset: Boolean)

/**
 * Settings › Keyboard shortcuts waiting for the keys a shortcut is to move to: [start] on the row tapped, then every
 * hardware key goes to [onKey]. A free chord is saved at once; one another shortcut is on waits as [pending] for
 * [resolve]; a blocked one says why in [problem] and the capture keeps waiting. Esc, [cancel], or the same keys again
 * end it with nothing changed.
 */
@Stable
internal class ShortcutCapture(private val bindings: () -> ShortcutBindings, private val change: (ShortcutBindings) -> Unit) {

    var target by mutableStateOf<Shortcut?>(null)
        private set

    /** The modifiers held right now, drawn ahead of the key still to come. */
    var held by mutableStateOf<List<String>>(emptyList())
        private set

    var problem by mutableStateOf<String?>(null)
        private set

    var pending by mutableStateOf<PendingChange?>(null)
        private set

    fun start(shortcut: Shortcut) {
        target = shortcut
        held = emptyList()
        problem = null
        pending = null
    }

    fun cancel() {
        target = null
        held = emptyList()
        problem = null
        pending = null
    }

    /** A hardware key while capturing: every one is taken, so none of them also does what it does today. */
    fun onKey(event: KeyEvent): Boolean {
        if (target == null) return false
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        val meta = event.isMetaPressed
        held = listOfNotNull("Ctrl".takeIf { ctrl }, "Shift".takeIf { shift }, "Alt".takeIf { alt }, "Meta".takeIf { meta })
        if (KeyEvent.isModifierKey(event.keyCode) || event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && !ctrl && !shift && !alt && !meta) {
            cancel()
            return true
        }
        offer(KeyChord(event.keyCode, ctrl, shift, alt), meta)
        return true
    }

    fun offer(chord: KeyChord, meta: Boolean = false) {
        val shortcut = target ?: return
        val blocked = ShortcutRules.blocked(chord.keyCode, chord.ctrl, chord.shift, chord.alt, meta)
        if (blocked != null) {
            problem = blocked
            pending = null
            return
        }
        when (val check = bindings().check(shortcut, chord)) {
            ChordCheck.Free -> commit(bindings().assign(shortcut, chord))
            ChordCheck.Unchanged -> cancel()
            is ChordCheck.Taken -> {
                problem = null
                pending = PendingChange(shortcut, listOf(chord), listOf(chord to check.owner), reset = false)
            }
            is ChordCheck.Blocked -> {
                problem = check.reason
                pending = null
            }
        }
    }

    /** [shortcut] back on its defaults: at once, unless another shortcut is on one of them now, which waits as [pending]. */
    fun reset(shortcut: Shortcut) {
        val conflicts = bindings().resetConflicts(shortcut)
        if (conflicts.isEmpty()) {
            commit(bindings().reset(shortcut))
            return
        }
        start(shortcut)
        pending = PendingChange(shortcut, shortcut.defaults, conflicts, reset = true)
    }

    fun resetAll() = commit(ShortcutBindings.Defaults)

    fun resolve(resolution: ConflictResolution) {
        val change = pending ?: return
        val current = bindings()
        commit(if (change.reset) current.reset(change.target, resolution) else current.assign(change.target, change.chords.single(), resolution))
    }

    /** What [change]'s target would hand over in a swap: the keys it is on now and gives up. */
    fun swapGives(change: PendingChange): List<KeyChord> = bindings().chords(change.target).filter { it !in change.chords }

    /** What [owner] is left on after a replace. */
    fun replaceLeaves(change: PendingChange, owner: Shortcut): List<KeyChord> = bindings().chords(owner).filter { it !in change.chords }

    private fun commit(next: ShortcutBindings) {
        cancel()
        if (next != bindings()) change(next)
    }
}
