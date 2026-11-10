package com.cursorforandroid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * A screen arriving piece by piece: each piece fades in and rises a little, one after the other, top to bottom, so a
 * screen that opens cold reads in the order it is meant to be read rather than appearing all at once. One clock runs
 * the whole arrival ([progress], 0 to 1 over [totalMillis]); a piece takes its own stretch of it by its [order].
 *
 * It is drawn in the graphics layer alone: nothing is laid out differently, every piece is in the tree and can be
 * found and pressed from the first frame, and the arrived screen is exactly the screen without this.
 */
@Stable
class Arrival internal constructor(internal val progress: Animatable<Float, AnimationVector1D>, private val pieces: Int) {
    internal val totalMillis: Int get() = PieceMillis + StaggerMillis * (pieces - 1).coerceAtLeast(0)

    /** How far piece [order] has come, 0 to 1, eased. */
    fun at(order: Int): Float = shareAt(progress.value, order, pieces)

    companion object {
        const val PieceMillis = 360
        const val StaggerMillis = 55

        /** Piece [order]'s own progress when the whole arrival of [pieces] stands at [progress]. */
        fun shareAt(progress: Float, order: Int, pieces: Int): Float {
            val total = PieceMillis + StaggerMillis * (pieces - 1).coerceAtLeast(0)
            val elapsed = progress.coerceIn(0f, 1f) * total - order.coerceIn(0, (pieces - 1).coerceAtLeast(0)) * StaggerMillis
            return FastOutSlowInEasing.transform((elapsed / PieceMillis).coerceIn(0f, 1f))
        }
    }
}

/**
 * The arrival of a screen of [pieces] pieces, played once: a screen recreated (a turn of the phone, a return from the
 * browser) is already there and does not arrive again. With animations off in the system it is there at once.
 */
@Composable
fun rememberArrival(pieces: Int): Arrival {
    var arrived by rememberSaveable { mutableStateOf(false) }
    val arrival = remember(pieces) { Arrival(Animatable(if (arrived) 1f else 0f), pieces) }
    LaunchedEffect(arrival) {
        if (!arrived) {
            arrival.progress.animateTo(1f, tween(arrival.totalMillis, easing = LinearEasing))
            arrived = true
        }
    }
    return arrival
}

/** Draws this as piece [order] of [arrival]. */
fun Modifier.arriving(arrival: Arrival, order: Int): Modifier = graphicsLayer {
    val t = arrival.at(order)
    alpha = t
    translationY = (1f - t) * 14.dp.toPx()
}
