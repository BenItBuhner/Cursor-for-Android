package com.cursorforandroid.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Where the New Chat pane's content begins. Short of the pane's height, far enough down that the composer and what it
 * lists sit as one block in the middle; taller, at the top, scrolling from there.
 *
 * The room above is a lead item of its own ahead of the composer ([Lead]), so everything under it moves with it. As
 * the list loads or changes, the block glides to its new place, and what came in is already in its place under the
 * composer. As the pane itself resizes (the keyboard rising, a window resized), the block keeps to the middle frame by
 * frame, which the keyboard's own animation makes smooth; a glide would lag behind it and leave the composer under the
 * keyboard on the way.
 *
 * While the keyboard gives the page over to the composer ([alone]), the lead goes from that one towards the one that
 * puts the composer alone in the middle of the room above the keyboard, by as much as the page is given over. That
 * lead is worked out as the lead lays out, from the room the list measures in that same pass, so the composer is
 * where the keyboard's inset puts it in the frame the inset arrives, never a frame behind; and the block's own lead is
 * held while it is, so that the keyboard leaving lands the page on exactly the lead it had.
 *
 * Until a first layout says how tall the block is, [arrangement] centres it instead, which draws it where the lead then
 * puts it.
 */
@Stable
internal class PageCentring {
    private var lead: Animatable<Float, AnimationVector1D>? by mutableStateOf(null)

    /** Read as the list lays out rather than as it composes, so that it and [Lead] never disagree in any one layout. */
    val arrangement: Arrangement.Vertical = object : Arrangement.Vertical {
        override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
            with(if (lead == null) Arrangement.Center else Arrangement.Top) { arrange(totalSize, sizes, outPositions) }
        }
    }

    /**
     * How much of the lead the composer has taken, 0 to 1: an expanding composer grows into the room above it as well as
     * below, so it reaches the top of the pane as it reaches its full height. Read as the lead lays out.
     */
    var squeeze: () -> Float = { 0f }

    /** While true, the block stays where it is as the list changes; let go, it glides to the middle again. Read as state. */
    var held: () -> Boolean = { false }

    /** How far the keyboard has given the page over to the composer alone, 0 to 1 ([KeyboardFocus.fraction]). Read as the lead lays out. */
    var alone: () -> Float = { 0f }

    /** The room under the list's top padding, as the list measures in this pass: set by [measuringRoom] before its items are. */
    private var room = 0

    /** The composer item's height as it last laid out; [composerHeight] glides to it while the composer stands alone. */
    private var composerPx by mutableIntStateOf(0)
    private val composerHeight = Animatable(0f)

    /** The composer item's height, from its layout. */
    fun composerLaidOut(height: Int) {
        composerPx = height
    }

    /** On the list, after the padding that keeps it clear of the keyboard: what is under its [top] padding is the room. */
    fun Modifier.measuringRoom(top: Int): Modifier = layout { measurable, constraints ->
        room = constraints.maxHeight - top
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    /** The room above the composer; the list's first item, keyed [LEAD_KEY], ahead of the item keyed [COMPOSER_KEY]. */
    @Composable
    fun Lead() {
        Layout(Modifier) { _, _ ->
            val block = lead?.value ?: 0f
            val alone = alone().coerceIn(0f, 1f)
            val centred = if (alone > 0f) block + (aloneLead() - block) * alone else block
            layout(0, (centred * (1f - squeeze().coerceIn(0f, 1f))).roundToInt()) {}
        }
    }

    /** The lead that puts the composer alone in the middle of the room. */
    private fun aloneLead(): Float = ((room - composerHeight.value) / 2f).coerceAtLeast(0f)

    /** Keeps the lead to [list]'s layouts for as long as it is called. */
    suspend fun follow(list: LazyListState) = coroutineScope {
        launch {
            // A line typed onto the composer standing alone grows it under its top; it glides back to the middle.
            snapshotFlow { composerPx }.collectLatest { height ->
                if (alone() > 0f && composerHeight.value > 0f) composerHeight.animateTo(height.toFloat(), Glide) else composerHeight.snapTo(height.toFloat())
            }
        }
        var pane = Int.MIN_VALUE
        snapshotFlow { list.layoutInfo.takeIf { it.totalItemsCount > 0 }?.let { Laid(centredLead(it), it.viewportSize.height, held(), alone() > 0f) } }.collectLatest { laidOut ->
            laidOut ?: return@collectLatest
            val lead = lead
            if (lead == null) {
                pane = laidOut.height
                this@PageCentring.lead = Animatable(laidOut.target)
                return@collectLatest
            }
            // The composer's alone: the block's lead waits, as it was, for the keyboard to leave.
            if (laidOut.alone) return@collectLatest
            val resized = laidOut.height != pane
            pane = laidOut.height
            when {
                resized -> lead.snapTo(laidOut.target)
                laidOut.held -> Unit
                else -> lead.animateTo(laidOut.target, Glide)
            }
        }
    }

    private data class Laid(val target: Float, val height: Int, val held: Boolean, val alone: Boolean)

    companion object {
        const val LEAD_KEY = "lead"
        const val COMPOSER_KEY = "composer"

        private val Glide = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }
}

/**
 * The lead that centres the composer and all under it in the room between the list's content padding. While the end of
 * the list is out of sight, the block is at least as tall as what shows of it from the composer down, so the lead is
 * at most half the composer's offset; each layout on the way there sees more of it, until the end shows or the lead is
 * none.
 */
internal fun centredLead(info: LazyListLayoutInfo): Float {
    val room = info.viewportEndOffset - info.afterContentPadding
    val items = info.visibleItemsInfo
    val composer = items.firstOrNull { it.key == PageCentring.COMPOSER_KEY } ?: return 0f
    val last = items.last()
    if (last.index == info.totalItemsCount - 1) return ((room - (last.offset + last.size - composer.offset)) / 2f).coerceAtLeast(0f)
    // Rounded down: a lead of a pixel drawn is a composer a pixel down, and half of it rounded to the nearest is that pixel again.
    return (composer.offset / 2).coerceAtLeast(0).toFloat()
}
