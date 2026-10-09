package com.cursorforandroid.ui.components

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import com.cursorforandroid.ui.theme.CursorDimens
import kotlin.math.roundToInt

/**
 * The caption bar of a desktop window (Android desktop windowing, Samsung DeX on One UI 8, a freeform window), when
 * the app's own top chrome is drawn into it rather than under it — the way Chrome and Edge put their tabs there.
 *
 * The app asks for a transparent caption ([CaptionBarAppearance]); a system that grants it lays the window out from
 * its top edge, reports the bar as a `captionBar` inset, and reports where its own controls stand with
 * `WindowInsets.getBoundingRects` (API 35), in the window's pixels — the same space `positionInWindow` answers in,
 * and the same space a window's density or Samsung's per-window scale is already applied in. Discrete minimize /
 * maximize / close buttons arrive as a tight cluster at the end; some Samsung windows report one bar across the top
 * instead. [occupancy] keeps only the end caps of a bar, so the app's buttons sit naturally against the system chrome
 * rather than in a hole the width of the bar, and never under a real control cluster.
 *
 * The headers then stand in the bar's row ([captionRow]). Leading chrome (the rail) packs just after the app-menu
 * chip. Trailing chrome (the panel button, More) is drawn in the caption's pixel space just before the window
 * controls ([CaptionTrailing]), so a pinned panel cannot leave them stranded at the chat's edge. The buttons claim
 * their touches from the system ([captionControls]); everywhere else in the row the system still drags the window.
 *
 * Null (the [LocalCaptionBar] default) everywhere else: a phone, a tablet or a foldable full screen, a system that
 * keeps its caption opaque (an opaque caption is consumed before the app sees it, so the window reads no inset), or
 * one that reports no controls to stay clear of. The headers are then laid out exactly as they always were.
 *
 * [heightPx] is the bar's height from the window's top edge; [controls] are the system's controls in the window's
 * pixels; [windowWidthPx] is that same window, so a scale or resize can move the end cluster without the occupancy
 * being computed against a stale width.
 */
@Immutable
class CaptionBar(
    val heightPx: Int,
    val controls: List<IntRect>,
    val windowWidthPx: Int = controls.maxOfOrNull { it.right } ?: 0,
) {

    /**
     * The window-pixel band the app may draw into: just after the left system chrome, just before the right. A
     * control that is only a cluster of buttons keeps its real edge. A control that is one bar — most of the
     * window, or much wider than a button cluster — keeps only a cap the size of that chrome, so the gap before
     * the system controls stays tight and does not wander with the pane split.
     */
    fun occupancy(): Occupancy {
        val width = windowWidthPx.coerceAtLeast(controls.maxOfOrNull { it.right } ?: 0)
        if (width <= 0) return Occupancy(0, 0)
        var leftExclusive = 0
        var rightExclusive = width
        var hasLeftCluster = false
        var hasRightCluster = false
        for (control in controls) {
            val wide = control.width * 2 >= width
            val onLeft = (control.left + control.right) / 2f < width / 2f
            when {
                wide -> {
                    if (!hasLeftCluster) leftExclusive = maxOf(leftExclusive, control.left + leftCap())
                    if (!hasRightCluster) rightExclusive = minOf(rightExclusive, control.right - rightCap())
                }
                onLeft -> {
                    hasLeftCluster = true
                    val edge = if (control.width > wideLeft()) control.left + leftCap() else control.right
                    leftExclusive = maxOf(leftExclusive, edge)
                }
                else -> {
                    hasRightCluster = true
                    val edge = if (control.width > wideRight()) control.right - rightCap() else control.left
                    rightExclusive = minOf(rightExclusive, edge)
                }
            }
        }
        if (leftExclusive > rightExclusive) {
            val mid = width / 2
            leftExclusive = minOf(leftExclusive, mid)
            rightExclusive = maxOf(rightExclusive, mid)
        }
        return Occupancy(leftExclusive, rightExclusive)
    }

    /**
     * How far a row spanning [left] to [right] (window pixels) has to hold its content in from each end so none of
     * it stands under the occupied caps. A row that never reaches a cap — a chat between the rail and a pinned
     * panel — is not padded by it; the trailing buttons still hug the right cap through [CaptionTrailing].
     */
    fun clearance(left: Float, right: Float): Clearance {
        val occ = occupancy()
        return Clearance(
            left = maxOf(0f, occ.leftExclusive - left),
            right = maxOf(0f, right - occ.rightExclusive),
        )
    }

    /** Window pixels exclusive of the app on the left and right of the caption. */
    data class Occupancy(val leftExclusive: Int, val rightExclusive: Int)

    /** Pixels to hold in from the window's left ([left]) and right ([right]) edges of a row. */
    data class Clearance(val left: Float, val right: Float)

    /** App-menu chip: about two caption-heights (icon + chevron), in the caption's own pixels. */
    internal fun leftCap(): Int = heightPx * 2

    /** Minimize / maximize / close as one cluster, not a hole the width of the bar. */
    internal fun rightCap(): Int = heightPx * 5

    private fun wideLeft(): Int = heightPx * 4

    private fun wideRight(): Int = heightPx * 6

    override fun equals(other: Any?): Boolean =
        other is CaptionBar && other.heightPx == heightPx && other.controls == controls && other.windowWidthPx == windowWidthPx

    override fun hashCode(): Int = 31 * (31 * heightPx + controls.hashCode()) + windowWidthPx

    companion object {
        /**
         * The bar as the window reports it, or null where the headers keep their usual place: no caption inset, a
         * caption overlapping the status bar (an OEM reporting one full screen; Chrome's `CaptionBarInsetsRectProvider`
         * guards the same case), or no controls reported.
         */
        fun of(captionTopPx: Int, statusTopPx: Int, controls: List<IntRect>, windowWidthPx: Int = controls.maxOfOrNull { it.right } ?: 0): CaptionBar? =
            if (captionTopPx <= 0 || statusTopPx > 0 || controls.isEmpty()) null else CaptionBar(captionTopPx, controls, windowWidthPx)
    }
}

/** How the window describes its caption chrome: discrete system buttons, or one bar across the top. */
enum class CaptionReportedShape { System, SingleBar }

/** The window's [CaptionBar] while the app's chrome is drawn into it; null everywhere else. */
val LocalCaptionBar = compositionLocalOf<CaptionBar?> { null }

/** Where the caption's trailing buttons are drawn, above every pane, in the bar's pixel space. */
internal val LocalCaptionEndSlot = compositionLocalOf<CaptionEndSlot?> { null }

internal class CaptionEndSlot {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    var widthPx by mutableIntStateOf(0)
}

/**
 * Reads the window's caption bar and provides it to [content]. Read again when the insets change, when the window
 * is resized, and when the density / UI scale changes — Samsung can change a window's scale while it is already
 * windowed, and the controls must be re-read in that new pixel space. [reportedShape] is [CaptionReportedShape.System]
 * on device; [CaptionReportedShape.SingleBar] is the Samsung one-bar report, used by tests and the desktop demo.
 */
@Composable
fun CaptionBarHost(
    reportedShape: CaptionReportedShape = CaptionReportedShape.System,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val captionTop = WindowInsets.captionBar.getTop(density)
    val statusTop = WindowInsets.statusBars.getTop(density)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bar = remember(captionTop, statusTop, size, density.density, reportedShape) {
        if (captionTop <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            null
        } else {
            val width = size.width.coerceAtLeast(view.width)
            val controls = when (reportedShape) {
                CaptionReportedShape.SingleBar -> listOf(IntRect(0, 0, width, captionTop))
                CaptionReportedShape.System -> captionControls(view)
            }
            CaptionBar.of(captionTop, statusTop, controls, width)
        }
    }
    CaptionBarScope(bar, onSize = { size = it }, content)
}

/**
 * Provides [bar] and the trailing-button slot the way [CaptionBarHost] does, so tests can stand a known bar in the
 * same pixel space without a real window.
 */
@Composable
fun CaptionBarScope(
    bar: CaptionBar?,
    onSize: (IntSize) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val slot = remember { CaptionEndSlot() }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalCaptionBar provides bar, LocalCaptionEndSlot provides slot) {
        Box(Modifier.fillMaxSize().onSizeChanged(onSize)) {
            content()
            if (bar != null) CaptionEndOverlay(bar, slot, density)
        }
    }
}

@Composable
private fun BoxScope.CaptionEndOverlay(bar: CaptionBar, slot: CaptionEndSlot, density: Density) {
    val end = slot.content ?: return
    val occ = bar.occupancy()
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .absoluteOffset { IntOffset(-(bar.windowWidthPx - occ.rightExclusive), 0) }
            .heightIn(min = bar.height(density))
            .captionControls(bar)
            .onSizeChanged { slot.widthPx = it.width }
            .testTag("caption-end"),
        contentAlignment = Alignment.CenterEnd,
    ) { end() }
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun captionControls(view: View): List<IntRect> =
    view.rootWindowInsets?.getBoundingRects(android.view.WindowInsets.Type.captionBar())
        ?.map { IntRect(it.left, it.top, it.right, it.bottom) }
        ?.filter { it.width > 0 && it.height > 0 }
        .orEmpty()

/**
 * Asks for a transparent caption, whose glyphs read on the theme: dark ones over a light theme. A request, not a
 * guarantee — [CaptionBarHost] finds out whether the system granted it — and nothing where there is no caption.
 */
@Composable
fun CaptionBarAppearance(activity: Activity, dark: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    SideEffect {
        val light = if (dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
        activity.window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or light,
            WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND or WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS,
        )
    }
}

/**
 * What a header pads its top by outside the caption bar: the status bar, and a caption the window reports without
 * the app's chrome in it (see [CaptionBar.of]), so nothing lands under it. On a phone the caption is empty and this
 * is the status bar alone, as it always was.
 */
val HeaderTopInsets: WindowInsets
    @Composable get() = WindowInsets.statusBars.union(WindowInsets.captionBar)

/** The caption bar's height in [Dp], never less than a header button. */
fun CaptionBar.height(density: Density): Dp = with(density) { heightPx.toDp() }.coerceAtLeast(CursorDimens.iconButton)

/**
 * A header row standing in the caption bar: the bar's height, so its buttons are centred on the system's, and held
 * in from each end by the occupied caps ([CaptionBar.clearance]), measured where the row is in the window — in that
 * window's pixels, not a dp guess, so a density or window-scale change moves the inset with the controls.
 */
@Composable
fun Modifier.captionRow(bar: CaptionBar): Modifier {
    val density = LocalDensity.current
    var clearance by remember(bar) { mutableStateOf(CaptionBar.Clearance(0f, 0f)) }
    return this
        .onGloballyPositioned {
            val left = it.positionInWindow().x
            clearance = bar.clearance(left, left + it.size.width)
        }
        .heightIn(min = bar.height(density))
        .then(HorizontalPixelPadding(clearance.left.roundToInt(), clearance.right.roundToInt()))
}

/**
 * A group of header buttons in the caption bar claims its touches from the system, which otherwise drags the window
 * from anywhere in the bar. The claim reaches as far as the buttons' touch targets do past their glyphs; the row's
 * empty stretches claim nothing, so the window is still dragged by them.
 */
@Composable
fun Modifier.captionControls(bar: CaptionBar?): Modifier {
    if (bar == null) return this
    val reach = with(LocalDensity.current) { ((CursorDimens.touchTarget - CursorDimens.iconButton) / 2).toPx() }
    return systemGestureExclusion { coordinates ->
        Rect(-reach, -reach, coordinates.size.width + reach, coordinates.size.height + reach)
    }
}

/**
 * Trailing caption chrome (panel, More): drawn in the bar's overlay, hugging [CaptionBar.Occupancy.rightExclusive],
 * so the gap before the system controls stays tight whether the chat column reaches them or a panel stands in
 * between. Without the overlay slot the buttons stay in the header, still clear of whatever cap the row reaches.
 */
@Composable
fun CaptionTrailing(bar: CaptionBar, content: @Composable RowScope.() -> Unit) {
    val slot = LocalCaptionEndSlot.current
    if (slot == null) {
        Row(Modifier.captionControls(bar), verticalAlignment = Alignment.CenterVertically, content = content)
        return
    }
    val row = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
    slot.content = row
    DisposableEffect(slot) { onDispose { slot.content = null } }
}

/** Reserves the overlay's width in a header that already reaches the window controls, so a title cannot run under it. */
@Composable
fun Modifier.captionEndReserve(bar: CaptionBar, headerRightPx: Float): Modifier {
    val slot = LocalCaptionEndSlot.current ?: return this
    val density = LocalDensity.current
    val reserve = headerRightPx >= bar.occupancy().rightExclusive - 1f && slot.widthPx > 0
    return if (reserve) width(with(density) { slot.widthPx.toDp() }) else this
}

/** A stretch of the caption bar's height that holds nothing of the app's, left for the window to be dragged by. */
@Composable
fun CaptionBarSpacer() {
    val bar = LocalCaptionBar.current ?: return
    Spacer(Modifier.fillMaxWidth().height(bar.height(LocalDensity.current)))
}

/**
 * Insets a row by whole window pixels on each side. [androidx.compose.foundation.layout.absolutePadding] goes through
 * Dp and back, which at a non-integer density (or after a window scale change) walks the gap.
 */
private data class HorizontalPixelPadding(val left: Int, val right: Int) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val start = left.coerceAtLeast(0)
        val end = right.coerceAtLeast(0)
        val horizontal = start + end
        val placeable = measurable.measure(constraints.offset(horizontal = -horizontal))
        val width = constraints.constrainWidth(placeable.width + horizontal)
        val height = constraints.constrainHeight(placeable.height)
        return layout(width, height) { placeable.place(start, 0) }
    }
}
