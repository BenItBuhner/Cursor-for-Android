package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import com.cursorforandroid.data.repo.SubagentStreamGate

/** The cap gate for cloud subagent streams on this surface; absent where rows are rendered without the app graph. */
val LocalSubagentStreamGate = compositionLocalOf<SubagentStreamGate?> { null }

/** Registers live-stream interest for [agentId] while [wants] is true and this composable stays on screen. */
@Composable
internal fun TrackSubagentStream(agentId: String?, wants: Boolean) {
    val gate = LocalSubagentStreamGate.current ?: return
    DisposableEffect(gate, agentId, wants) {
        if (wants && agentId != null) {
            gate.track(agentId)
            onDispose { gate.untrack(agentId) }
        } else {
            onDispose {}
        }
    }
}
