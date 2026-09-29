package com.cursorforandroid.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.ui.components.QueueFlights

/**
 * Where a device-queue card stands while its message is on its way into the transcript: the repository drops the row
 * as soon as the run takes it, but the bubble is filed from the presenter a frame later unless the screen holds the
 * card until the transcript's user messages include it (by words, as [QueuePlacement] compares them). Aligns with
 * [SteeredCards] on steered rows (#475): one place on screen until the transcript shows the message.
 */
internal object QueuedCards {
    fun inTranscript(items: List<TimelineItem>, text: String): Boolean {
        val key = QueuePlacement.textKey(text)
        return items.any { it is UserMessage && QueuePlacement.textKey(it.text) == key }
    }
}

/** The device's queue for the stack, and the row map for [QueueDeliveries] — split while a ghost waits for its bubble. */
data class DeviceQueueHandoff(val standing: List<QueuedFollowUp>, val rowsForFlight: Map<String, String>)

/**
 * The device's queue as the stack draws it: rows the repository has let go of stay until the same composition that
 * files their bubble among [items].
 */
@Composable
fun rememberDeviceQueueHandoff(
    deviceQueue: List<QueuedFollowUp>,
    items: List<TimelineItem>,
    flights: QueueFlights,
    stillOnCardIds: Set<String> = emptySet(),
    stillOnCardTexts: Set<String> = emptySet(),
): DeviceQueueHandoff {
    val ghosts = remember { mutableStateMapOf<String, QueuedFollowUp>() }
    var previous by remember { mutableStateOf(deviceQueue) }
    SideEffect {
        val prevIds = previous.mapTo(HashSet()) { it.id }
        val currIds = deviceQueue.mapTo(HashSet()) { it.id }
        for (goneId in prevIds - currIds) {
            previous.firstOrNull { it.id == goneId }?.let { gone ->
                val key = QueuePlacement.textKey(gone.previewText)
                if (goneId in stillOnCardIds || key in stillOnCardTexts) return@let
                if (!QueuedCards.inTranscript(items, gone.previewText)) ghosts[goneId] = gone
            }
        }
        ghosts.keys.removeAll { id ->
            val ghost = ghosts[id]
            id in stillOnCardIds ||
                ghost?.let { QueuePlacement.textKey(it.previewText) in stillOnCardTexts } == true
        }
        previous = deviceQueue
    }
    val handoff = remember(deviceQueue, ghosts, items, flights.delivering) {
        val onDevice = deviceQueue.mapTo(HashSet()) { it.id }
        val extra = ghosts.values.filter { it.id !in onDevice }
        val standing = if (extra.isEmpty()) deviceQueue else extra.sortedBy { it.queuedAtMillis } + deviceQueue
        val rowsForFlight = LinkedHashMap<String, String>()
        for (item in standing) {
            val ghost = item.id in ghosts
            if (ghost && QueuedCards.inTranscript(items, item.previewText)) continue
            rowsForFlight[item.id] = item.previewText
        }
        DeviceQueueHandoff(standing, rowsForFlight)
    }
    val filing = handoff.standing.filter { it.id in ghosts && QueuedCards.inTranscript(items, it.previewText) }.mapTo(HashSet()) { it.id }
    if (filing.isNotEmpty()) flights.delivering = filing
    return handoff
}
