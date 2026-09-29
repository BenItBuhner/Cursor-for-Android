package com.cursorforandroid.ui.conversation

import com.cursorforandroid.domain.PendingFollowup
import com.cursorforandroid.domain.QueuePlacement
import com.cursorforandroid.domain.QueuedFollowUp

/**
 * Where a device card being steered stands against the account's rows (#475): the card stands for the account's row
 * until the transcript files the message. Steer-phase fields land with that PR; until then this is a no-op pass-through.
 */
internal object SteeredCards {
    fun standing(queue: List<QueuedFollowUp>, placement: QueuePlacement): List<QueuedFollowUp> = queue

    fun accountRows(rows: List<PendingFollowup>, queue: List<QueuedFollowUp>): List<PendingFollowup> = rows
}
