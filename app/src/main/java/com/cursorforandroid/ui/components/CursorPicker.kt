package com.cursorforandroid.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What a [CursorPicker] opens from: the bounds of the chip or button that opened it, in its window, kept current as
 * the anchor moves (the keyboard lifting the composer, say). Put [popoverAnchor] on the control.
 */
@Stable
class PopoverAnchor {
    var bounds: IntRect? by mutableStateOf(null)
        internal set
}

@Composable
fun rememberPopoverAnchor(): PopoverAnchor = remember { PopoverAnchor() }

fun Modifier.popoverAnchor(anchor: PopoverAnchor?): Modifier =
    if (anchor == null) {
        this
    } else {
        onGloballyPositioned { coordinates ->
            val box = coordinates.boundsInWindow()
            anchor.bounds = IntRect(box.left.roundToInt(), box.top.roundToInt(), box.right.roundToInt(), box.bottom.roundToInt())
        }
    }

/** What the composer's pickers open from: its source, branch, device and model chips. */
@Stable
class ComposerPickerAnchors {
    val repository = PopoverAnchor()
    val branch = PopoverAnchor()
    val device = PopoverAnchor()
    val model = PopoverAnchor()
}

@Composable
fun rememberComposerPickerAnchors(): ComposerPickerAnchors = remember { ComposerPickerAnchors() }

/**
 * One line of a [CursorPicker], as Cursor's own dropdowns lay them out: section headers, choice rows, switches,
 * the "+" actions at the foot, notes, and the hairlines between groups. [key] is unique within its list.
 */
sealed interface PickerEntry {
    val key: String
}

/** A small muted header over a group of rows ("Recents", "My Machines"). */
data class PickerSection(override val key: String, val title: String) : PickerEntry

/** What a pick does to the picker: closes it, steps back out of the submenu it was made in, or leaves it open. */
enum class PickerDismiss { All, Submenu, None }

/**
 * A choice. [detail] is muted text inline after the label (a variant, an owner, "default"); [subtitle] a second line
 * for the rows that need one. [selected] wears the accent check; [dimmed] greys an offline row that can still be
 * picked. A row with a [submenu] wears a chevron and opens it, beside the picker where there is room and in its
 * place where there is not; [onPick], when set as well, runs first (a model is selected as its options open).
 */
data class PickerItem(
    override val key: String,
    val label: String,
    val onPick: (() -> Unit)? = null,
    val detail: String? = null,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val iconTint: Color? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val dimmed: Boolean = false,
    val badge: String? = null,
    val trailingAction: PickerTrailingAction? = null,
    val submenu: PickerSubmenu? = null,
    val dismiss: PickerDismiss = PickerDismiss.All,
    val testTag: String? = null,
) : PickerEntry

/** A row's own small control before its check (a model's pin): a tap on it is the control's, not the row's. */
data class PickerTrailingAction(
    val icon: ImageVector,
    val contentDescription: String,
    val active: Boolean = false,
    val onClick: () -> Unit,
)

/** A switch row ("Thinking", "Show workspace"): flips in place, never closes the picker. */
data class PickerToggle(
    override val key: String,
    val label: String,
    val checked: Boolean,
    val onToggle: (Boolean) -> Unit,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
) : PickerEntry

/** A "+ Add…" or "Refresh" row at the foot of a list, in the secondary tone; [busy] spins in its glyph's place. */
data class PickerAction(
    override val key: String,
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector = CursorIcons.Plus,
    val busy: Boolean = false,
    val enabled: Boolean = true,
    val dismiss: PickerDismiss = PickerDismiss.None,
    val selected: Boolean = false,
    val detail: String? = null,
    val testTag: String? = null,
) : PickerEntry

/** A line of explanation in the quaternary tone: why a list is empty, what a choice means. */
data class PickerNote(override val key: String, val text: String) : PickerEntry

/** The hairline between groups. */
data class PickerDivider(override val key: String) : PickerEntry

/** The page a [PickerItem] opens: its own title (the back row's, where it opens in place), search and rows. */
class PickerSubmenu(
    val title: String,
    val searchPlaceholder: String? = null,
    val width: Dp = PickerWidths.Submenu,
    val entries: (query: String) -> List<PickerEntry>,
)

/** How a [CursorPicker] is shown: anchored to what opened it, or as a bottom sheet where an anchored one cannot fit. */
enum class PickerPresentation { Anchored, Sheet }

object PickerWidths {
    val Narrow = 240.dp
    val Default = 280.dp
    val Wide = 320.dp
    val Submenu = 260.dp
}

/**
 * Every picker in the app on one popover, derived from Cursor's own dropdowns: an anchored rounded surface with the
 * menus' hairline and shadow, a search field with its magnifier, small section headers, rows with a leading glyph, a
 * label and muted inline detail, the accent check on the selection, a soft inset highlight on the row a pointer or
 * keyboard is on, hairlines between groups, "+" actions at the foot, switches, and submenus that cascade to the side.
 *
 * It opens below its [anchor], or above where there is not room below, grows in from it and shrinks back to it. On a
 * phone a submenu opens in the popover's place with a back row, there being no room beside it. With no anchor, or
 * not [CursorPickerDefaults.MinRoom] free on either side of it, it is a bottom sheet with the same rows instead.
 *
 * A physical keyboard drives it as the desktop's menus take one: typing searches, the arrows move the highlight round
 * the ends, Enter or Space picks, Right opens a row's submenu and Left or Esc steps back out, Esc closes.
 *
 * [entries] is asked for the rows for the current query whenever it is composed, so the rows follow the state
 * behind them; [onDismiss] is called once the picker has animated away.
 */
@Composable
fun CursorPicker(
    onDismiss: () -> Unit,
    anchor: PopoverAnchor?,
    title: String,
    entries: (query: String) -> List<PickerEntry>,
    modifier: Modifier = Modifier,
    searchPlaceholder: String? = null,
    width: Dp = PickerWidths.Default,
    presentation: PickerPresentation? = null,
    testTag: String = PickerTags.Picker,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val insets = WindowInsets.safeDrawing
    val layoutDirection = LocalLayoutDirection.current
    val area = with(density) {
        val root = view.rootView
        val w = root.width.takeIf { it > 0 } ?: configuration.screenWidthDp.dp.roundToPx()
        val h = root.height.takeIf { it > 0 } ?: configuration.screenHeightDp.dp.roundToPx()
        val margin = CursorDimens.menuEdgeMargin.roundToPx()
        IntRect(
            left = insets.getLeft(this, layoutDirection) + margin,
            top = insets.getTop(this) + margin,
            right = w - insets.getRight(this, layoutDirection) - margin,
            bottom = h - insets.getBottom(this) - margin,
        )
    }
    val anchorBounds = anchor?.bounds
    // Decided once: the keyboard coming up for the search field must not turn the popover into a sheet under the finger.
    val shown = rememberSaveable { mutableStateOf<PickerPresentation?>(null) }
    if (shown.value == null) {
        shown.value = presentation ?: with(density) {
            PickerGeometry.presentation(
                anchor = anchorBounds,
                area = area,
                gap = CursorDimens.menuGap.roundToPx(),
                minRoom = CursorDimens.pickerMinRoom.roundToPx(),
                minWidth = CursorDimens.pickerMinWidth.roundToPx(),
            )
        }
    }
    val state = rememberPickerState(title, searchPlaceholder, entries, onDismiss)
    when (shown.value) {
        PickerPresentation.Sheet, null -> SheetPicker(state, modifier, testTag)
        PickerPresentation.Anchored -> AnchoredPicker(state, anchorBounds ?: IntRect(area.center, area.center), area, width, modifier, testTag)
    }
}

object CursorPickerDefaults {
    /** The least room on one side of the anchor an anchored picker takes before it is a sheet instead. */
    val MinRoom: Dp get() = CursorDimens.pickerMinRoom
}

/** Test tags shared by every picker. */
object PickerTags {
    const val Picker = "cursor-picker"
    const val Search = "cursor-picker-search"
    const val List = "cursor-picker-list"
    const val Submenu = "cursor-picker-submenu"
    const val Back = "cursor-picker-back"
}

/** The row a keyboard or pointer is on, for tests: the highlight is drawn, not announced. */
val PickerHighlighted = SemanticsPropertyKey<Boolean>("PickerHighlighted")
private var SemanticsPropertyReceiver.pickerHighlighted by PickerHighlighted

/** A picker's row height and label style: 48dp and 14sp on a phone, 44dp and 13sp on a tablet or foldable. */
@Immutable
internal data class PickerMetrics(val row: Dp, val label: TextStyle, val compact: Boolean)

@Composable
internal fun pickerMetrics(): PickerMetrics {
    val compact = LocalConfiguration.current.screenWidthDp < CursorDimens.compactWidthLimit.value
    val type = CursorTheme.typography
    return if (compact) PickerMetrics(CursorDimens.menuRowTouch, type.input.copy(lineHeight = type.title.lineHeight), compact = true)
    else PickerMetrics(CursorDimens.menuRowRegular, type.base, compact = false)
}

/** One page of a picker, the root or a submenu: its query and the row the keyboard or pointer is on. */
@Stable
internal class PickerPage(val key: String?, query: TextFieldValue = TextFieldValue("")) {
    var query by mutableStateOf(query)
    var highlightKey by mutableStateOf<String?>(null)
    var keyMoves by mutableIntStateOf(0)
    var fieldFocused by mutableStateOf(false)
    val fieldFocus = FocusRequester()

    fun highlightIndex(entries: List<PickerEntry>): Int {
        val held = highlightKey?.let { key -> entries.indexOfFirst { it.key == key } } ?: -1
        return if (held >= 0 && PickerNavigation.isSelectable(entries[held])) held else PickerNavigation.initial(entries)
    }
}

@Stable
internal class PickerState(
    val title: String,
    val searchPlaceholder: String?,
    val root: PickerPage,
) {
    var entries: (String) -> List<PickerEntry> by mutableStateOf({ emptyList() })
    var onDismiss: () -> Unit = {}
    val visible = MutableTransitionState(false)
    val submenuVisible = MutableTransitionState(false)
    var submenu by mutableStateOf<PickerPage?>(null)
    var cascade by mutableStateOf(true)
    var closing by mutableStateOf(false)
    var sheetDismiss: (() -> Unit)? = null
    /** The submenu and its query saved with the activity, opened again once the picker has been laid out. */
    var pendingSubmenu: Pair<String, String>? = null
    private var dismissed = false

    /** Where rows and the root surface were last placed, in the overlay: read on a tap, never to draw. */
    val rowBounds = HashMap<String, IntRect>()
    var rootBounds = IntRect.Zero
    var rootOrigin = TransformOrigin(0.5f, 0f)
    var below = true

    val rootEntries: List<PickerEntry> get() = entries(root.query.text)

    fun submenuOf(key: String?): PickerSubmenu? =
        key?.let { k -> (rootEntries.firstOrNull { it.key == k } as? PickerItem)?.submenu }

    fun openSubmenu(key: String, cascade: Boolean, query: String = "") {
        if (submenu?.key != key) {
            submenu = PickerPage(key, TextFieldValue(query, TextRange(query.length)))
            this.cascade = cascade
        }
        submenuVisible.targetState = true
    }

    fun restoreSubmenu(canCascade: (String) -> Boolean) {
        val (key, query) = pendingSubmenu ?: return
        pendingSubmenu = null
        if (submenuOf(key) != null) openSubmenu(key, canCascade(key), query)
    }

    fun closeSubmenu() {
        submenuVisible.targetState = false
        if (!cascade) submenu = null
    }

    /** Back one level: out of the submenu, else the picker closed. */
    fun back() {
        if (submenu != null && submenuVisible.targetState) closeSubmenu() else close()
    }

    fun close() {
        if (closing) return
        closing = true
        submenuVisible.targetState = false
        visible.targetState = false
        sheetDismiss?.invoke()
    }

    fun finished() {
        if (dismissed) return
        dismissed = true
        onDismiss()
    }
}

@Composable
private fun rememberPickerState(
    title: String,
    searchPlaceholder: String?,
    entries: (String) -> List<PickerEntry>,
    onDismiss: () -> Unit,
): PickerState {
    var savedQuery by rememberSaveable { mutableStateOf("") }
    var savedSubmenu by rememberSaveable { mutableStateOf<String?>(null) }
    var savedSubmenuQuery by rememberSaveable { mutableStateOf("") }
    val state = remember {
        PickerState(title, searchPlaceholder, PickerPage(null, TextFieldValue(savedQuery, TextRange(savedQuery.length)))).apply {
            pendingSubmenu = savedSubmenu?.let { it to savedSubmenuQuery }
        }
    }
    state.entries = entries
    state.onDismiss = onDismiss
    LaunchedEffect(state) { snapshotFlow { state.root.query.text }.collect { savedQuery = it } }
    LaunchedEffect(state) {
        snapshotFlow { state.submenu?.takeIf { state.submenuVisible.targetState }?.let { it.key to it.query.text } }
            .collect { open -> if (state.pendingSubmenu == null) { savedSubmenu = open?.first; savedSubmenuQuery = open?.second.orEmpty() } }
    }
    return state
}

/** Positions for an anchored picker and its submenus, in the overlay's pixels. Pure, so it is tested on its own. */
internal object PickerGeometry {
    fun roomBelow(anchor: IntRect, area: IntRect, gap: Int): Int = area.bottom - (anchor.bottom + gap)

    fun roomAbove(anchor: IntRect, area: IntRect, gap: Int): Int = (anchor.top - gap) - area.top

    /** The tallest the picker may be: all the room on the roomier side of the anchor. */
    fun maxHeight(anchor: IntRect, area: IntRect, gap: Int): Int = max(0, max(roomBelow(anchor, area, gap), roomAbove(anchor, area, gap)))

    fun presentation(anchor: IntRect?, area: IntRect, gap: Int, minRoom: Int, minWidth: Int): PickerPresentation = when {
        anchor == null -> PickerPresentation.Sheet
        area.width < minWidth -> PickerPresentation.Sheet
        maxHeight(anchor, area, gap) < minRoom -> PickerPresentation.Sheet
        else -> PickerPresentation.Anchored
    }

    data class Placement(val offset: IntOffset, val below: Boolean, val origin: TransformOrigin)

    /**
     * Below the anchor where it fits, else above where it fits, else on the roomier side; lined up with the anchor's
     * start where that fits and its end where only that does, else clamped into [area].
     */
    fun place(anchor: IntRect, size: IntSize, area: IntRect, gap: Int, ltr: Boolean): Placement {
        val below = roomBelow(anchor, area, gap)
        val above = roomAbove(anchor, area, gap)
        val placeBelow = size.height <= below || (size.height > above && below >= above)
        val y = (if (placeBelow) anchor.bottom + gap else anchor.top - gap - size.height)
            .coerceIn(area.top, max(area.top, area.bottom - size.height))
        val start = if (ltr) anchor.left else anchor.right - size.width
        val end = if (ltr) anchor.right - size.width else anchor.left
        val x = listOf(start, end).firstOrNull { it >= area.left && it + size.width <= area.right }
            ?: start.coerceIn(area.left, max(area.left, area.right - size.width))
        val pivotX = ((anchor.left + anchor.right) / 2f - x).coerceIn(0f, size.width.toFloat())
        val origin = if (size.width > 0 && size.height > 0) {
            TransformOrigin(pivotX / size.width, if (placeBelow) 0f else 1f)
        } else {
            TransformOrigin(0.5f, 0f)
        }
        return Placement(IntOffset(x, y), placeBelow, origin)
    }

    /** Whether a submenu [width] wide fits beside [parent] on either side; where it does not it opens in place. */
    fun cascades(parent: IntRect, width: Int, area: IntRect, gap: Int): Boolean =
        area.right - parent.right - gap >= width || parent.left - gap - area.left >= width

    /**
     * Beside [parent] on its trailing side, else its leading side, the first row level with the [row] that opened it
     * ([inset] above it, the surface's own inset), clamped into [area]. Null where it fits on neither side.
     */
    fun placeSubmenu(row: IntRect, parent: IntRect, size: IntSize, area: IntRect, gap: Int, inset: Int, ltr: Boolean): Placement? {
        val after = if (ltr) parent.right + gap else parent.left - gap - size.width
        val before = if (ltr) parent.left - gap - size.width else parent.right + gap
        val x = listOf(after, before).firstOrNull { it >= area.left && it + size.width <= area.right } ?: return null
        val y = (row.top - inset).coerceIn(area.top, max(area.top, area.bottom - size.height))
        val toRight = x >= parent.right
        val pivotY = if (size.height > 0) ((row.top + row.bottom) / 2f - y).coerceIn(0f, size.height.toFloat()) / size.height else 0f
        return Placement(IntOffset(x, y), below = true, origin = TransformOrigin(if (toRight) 0f else 1f, pivotY))
    }
}

/** Which rows a keyboard can land on, and where the highlight starts and goes. */
internal object PickerNavigation {
    fun isSelectable(entry: PickerEntry): Boolean = when (entry) {
        is PickerItem -> entry.enabled
        is PickerToggle -> entry.enabled
        is PickerAction -> entry.enabled && !entry.busy
        is PickerSection, is PickerNote, is PickerDivider -> false
    }

    /** The selected row, else the first a keyboard can land on, else -1. */
    fun initial(entries: List<PickerEntry>): Int {
        val selected = entries.indexOfFirst { (it is PickerItem && it.selected || it is PickerAction && it.selected) && isSelectable(it) }
        return if (selected >= 0) selected else entries.indexOfFirst(::isSelectable)
    }

    /** [step] rows on from [from] over the rows a keyboard can land on, round from the last to the first and back. */
    fun move(entries: List<PickerEntry>, from: Int, step: Int): Int {
        val stops = entries.indices.filter { isSelectable(entries[it]) }
        if (stops.isEmpty()) return -1
        val at = stops.indexOf(from)
        if (at < 0) return if (step >= 0) stops.first() else stops.last()
        return stops[Math.floorMod(at + step, stops.size)]
    }

    /** The next row after [from] whose label starts with [typed], round the ends, for a page with no search; else -1. */
    fun typeAhead(entries: List<PickerEntry>, from: Int, typed: String): Int {
        if (typed.isBlank()) return -1
        val stops = entries.indices.filter { isSelectable(entries[it]) && label(entries[it]).startsWith(typed, ignoreCase = true) }
        if (stops.isEmpty()) return -1
        return stops.firstOrNull { it > from } ?: stops.first()
    }

    private fun label(entry: PickerEntry): String = when (entry) {
        is PickerItem -> entry.label
        is PickerToggle -> entry.label
        is PickerAction -> entry.label
        is PickerSection, is PickerNote, is PickerDivider -> ""
    }
}

/** What a physical key does to an open picker. */
internal object PickerKeys {
    enum class Press { NotOurs, Nothing, Up, Down, Pick, Open, Back, Close, Erase, Type }

    fun press(event: KeyEvent, fieldFocused: Boolean, query: String): Press {
        if (!event.isFromHardwareKeyboard) return Press.NotOurs
        val ctrlOnly = event.isCtrlPressed && !event.isMetaPressed
        val chord = event.isCtrlPressed || event.isMetaPressed || event.isAltPressed
        val press = when (event.key) {
            Key.DirectionUp -> Press.Up
            Key.DirectionDown -> Press.Down
            Key.P, Key.K -> if (ctrlOnly) Press.Up else typed(event, fieldFocused, chord)
            Key.N, Key.J -> if (ctrlOnly) Press.Down else typed(event, fieldFocused, chord)
            Key.Enter, Key.NumPadEnter -> Press.Pick
            Key.Escape -> Press.Close
            Key.DirectionRight -> if (!fieldFocused || query.isEmpty()) Press.Open else Press.NotOurs
            Key.DirectionLeft -> if (!fieldFocused || query.isEmpty()) Press.Back else Press.NotOurs
            Key.Spacebar -> when {
                fieldFocused -> Press.NotOurs
                query.isEmpty() -> Press.Pick
                else -> Press.Type
            }
            Key.Backspace -> if (!fieldFocused && query.isNotEmpty()) Press.Erase else Press.NotOurs
            else -> typed(event, fieldFocused, chord)
        }
        if (press == Press.NotOurs) return press
        return if (event.type == KeyEventType.KeyDown) press else Press.Nothing
    }

    private fun typed(event: KeyEvent, fieldFocused: Boolean, chord: Boolean): Press {
        if (fieldFocused || chord) return Press.NotOurs
        val code = event.utf16CodePoint
        return if (code > 0x1F && code != 0x7F) Press.Type else Press.NotOurs
    }
}

/** The page a key acts on: the open submenu's, else the root's, and whether it has a search field. */
private fun PickerState.focusedPage(): Triple<PickerPage, List<PickerEntry>, Boolean>? {
    val sub = submenu
    if (sub != null && submenuVisible.targetState) {
        val menu = submenuOf(sub.key) ?: return null
        return Triple(sub, menu.entries(sub.query.text), menu.searchPlaceholder != null)
    }
    return Triple(root, rootEntries, searchPlaceholder != null)
}

private fun PickerState.handleKey(event: KeyEvent, canCascade: (String) -> Boolean): Boolean {
    val (page, entries, searchable) = focusedPage() ?: return false
    val press = PickerKeys.press(event, page.fieldFocused, page.query.text)
    if (!searchable && (press == PickerKeys.Press.Type || press == PickerKeys.Press.Erase)) {
        if (press == PickerKeys.Press.Erase) return false
        val next = PickerNavigation.typeAhead(entries, page.highlightIndex(entries), String(Character.toChars(event.utf16CodePoint)))
        if (next < 0) return false
        page.highlightKey = entries[next].key
        page.keyMoves++
        return true
    }
    val index = page.highlightIndex(entries)
    when (press) {
        PickerKeys.Press.NotOurs -> return false
        PickerKeys.Press.Nothing -> Unit
        PickerKeys.Press.Up, PickerKeys.Press.Down -> {
            val next = PickerNavigation.move(entries, index, if (press == PickerKeys.Press.Up) -1 else 1)
            page.highlightKey = entries.getOrNull(next)?.key
            page.keyMoves++
        }
        PickerKeys.Press.Pick -> entries.getOrNull(index)?.let { activate(page, it, canCascade) }
        PickerKeys.Press.Open -> {
            val item = entries.getOrNull(index) as? PickerItem
            if (page === root && item?.submenu != null && item.enabled) openSubmenu(item.key, canCascade(item.key))
            else return page.fieldFocused.not()
        }
        PickerKeys.Press.Back -> if (page !== root) closeSubmenu() else return page.fieldFocused.not()
        PickerKeys.Press.Close -> back()
        PickerKeys.Press.Erase -> {
            val text = page.query.text.dropLast(1)
            page.query = TextFieldValue(text, TextRange(text.length))
            page.highlightKey = null
        }
        PickerKeys.Press.Type -> {
            val text = page.query.text + String(Character.toChars(event.utf16CodePoint))
            page.query = TextFieldValue(text, TextRange(text.length))
            page.highlightKey = null
            runCatching { page.fieldFocus.requestFocus() }
        }
    }
    return true
}

/** A row picked by a tap or a key, on [page]. */
private fun PickerState.activate(page: PickerPage, entry: PickerEntry, canCascade: (String) -> Boolean) {
    page.highlightKey = entry.key
    when (entry) {
        is PickerItem -> {
            if (!entry.enabled) return
            entry.onPick?.invoke()
            when {
                entry.submenu != null && page === root -> openSubmenu(entry.key, canCascade(entry.key))
                entry.dismiss == PickerDismiss.All -> close()
                entry.dismiss == PickerDismiss.Submenu && page !== root -> closeSubmenu()
                page === root && submenu != null -> closeSubmenu()
            }
        }
        is PickerToggle -> if (entry.enabled) entry.onToggle(!entry.checked)
        is PickerAction -> {
            if (!entry.enabled || entry.busy) return
            entry.onClick()
            when (entry.dismiss) {
                PickerDismiss.All -> close()
                PickerDismiss.Submenu -> if (page !== root) closeSubmenu()
                PickerDismiss.None -> Unit
            }
        }
        is PickerSection, is PickerNote, is PickerDivider -> Unit
    }
}

private object FullWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset.Zero
}

/**
 * The anchored picker: one focusable window over the whole app holding the picker and its cascading submenu, so a
 * tap outside both closes it and a tap on the picker while a submenu is open is the picker's, with no second window
 * to tell the two apart. The anchor and the safe area are the app window's; the overlay's own offset from that window
 * is measured and taken off, in case the platform puts a panel below the status bar.
 */
@Composable
private fun AnchoredPicker(state: PickerState, anchor: IntRect, area: IntRect, width: Dp, modifier: Modifier, testTag: String) {
    val hostRoot = LocalView.current.rootView
    val metrics = pickerMetrics()
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    LaunchedEffect(Unit) { state.visible.targetState = true }
    LaunchedEffect(state) {
        snapshotFlow { state.visible.isIdle && !state.visible.currentState && state.closing }.collect { if (it) state.finished() }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.submenuVisible.isIdle && !state.submenuVisible.currentState }.collect { if (it && state.cascade) state.submenu = null }
    }
    Popup(
        popupPositionProvider = FullWindow,
        onDismissRequest = state::back,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        val popupView = LocalView.current
        val density = LocalDensity.current
        var shift by remember { mutableStateOf(IntOffset.Zero) }
        val local = { rect: IntRect -> rect.translate(shift) }
        val rootFocus = remember { FocusRequester() }
        val gap = with(density) { CursorDimens.menuGap.roundToPx() }
        val inset = with(density) { CursorDimens.menuInset.roundToPx() }
        val canCascade = { key: String ->
            val menu = state.submenuOf(key)
            menu != null && with(density) { PickerGeometry.cascades(state.rootBounds, menu.width.roundToPx(), local(area), gap) }
        }
        LaunchedEffect(Unit) {
            runCatching { rootFocus.requestFocus() }
            withFrameNanos { }
            state.restoreSubmenu(canCascade)
        }
        val transition = rememberTransition(state.visible, label = "picker")
        val progress by transition.animateFloat(
            transitionSpec = { if (targetState) tween(MENU_ENTER_MILLIS, easing = MenuEnterEasing) else tween(MENU_EXIT_MILLIS, easing = MenuExitEasing) },
            label = "picker-progress",
        ) { if (it) 1f else 0f }
        val subTransition = rememberTransition(state.submenuVisible, label = "picker-submenu")
        val subProgress by subTransition.animateFloat(
            transitionSpec = { if (targetState) tween(MENU_ENTER_MILLIS, easing = MenuEnterEasing) else tween(MENU_EXIT_MILLIS, easing = MenuExitEasing) },
            label = "picker-submenu-progress",
        ) { if (it) 1f else 0f }
        var subOrigin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }

        Layout(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned {
                    val inPopup = IntArray(2).also(popupView::getLocationOnScreen)
                    val inHost = IntArray(2).also(hostRoot::getLocationOnScreen)
                    shift = IntOffset(inHost[0] - inPopup[0], inHost[1] - inPopup[1])
                }
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { state.handleKey(it, canCascade) }
                .semantics { paneTitle = state.title }
                .testTag(testTag),
            content = {
                Box(
                    Modifier
                        .layoutId("backdrop")
                        .pointerInput(state) { detectTapGestures { state.close() } },
                )
                Column(
                    modifier
                        .layoutId("root")
                        .graphicsLayer {
                            val p = progress
                            val scale = MENU_ENTER_SCALE + (1f - MENU_ENTER_SCALE) * p
                            scaleX = scale
                            scaleY = scale
                            alpha = p
                            transformOrigin = state.rootOrigin
                            translationY = (if (state.below) -1f else 1f) * CursorDimens.menuGap.toPx() * (1f - p)
                        }
                        .menuShadow(CursorTheme.colors.elevated, CursorTheme.colors.shadow)
                        .cursorSurface(CursorTheme.colors.elevated, CursorTheme.colors.strokeSubtle, CursorTheme.shapes.menu)
                        .hitTestBoundary(),
                ) {
                    val drilled = state.submenu?.takeIf { !state.cascade }
                    AnimatedContent(
                        targetState = drilled,
                        transitionSpec = {
                            val forward = targetState != null
                            val slide = tween<IntOffset>(PAGE_MILLIS, easing = MenuEnterEasing)
                            val pages = if (forward) {
                                (slideInHorizontally(slide) { it / 3 } + fadeIn(tween(PAGE_MILLIS))) togetherWith (slideOutHorizontally(slide) { -it / 3 } + fadeOut(tween(PAGE_MILLIS / 2)))
                            } else {
                                (slideInHorizontally(slide) { -it / 3 } + fadeIn(tween(PAGE_MILLIS))) togetherWith (slideOutHorizontally(slide) { it / 3 } + fadeOut(tween(PAGE_MILLIS / 2)))
                            }
                            pages using SizeTransform(clip = true) { _, _ -> tween(PAGE_MILLIS, easing = MenuEnterEasing) }
                        },
                        label = "picker-page",
                    ) { page ->
                        Column {
                            if (page == null) {
                                PageBody(state, state.root, state.searchPlaceholder, state.rootEntries, metrics, canCascade, trackRows = true)
                            } else {
                                val menu = state.submenuOf(page.key)
                                if (menu != null) {
                                    BackRow(menu.title, metrics) { state.closeSubmenu() }
                                    PageBody(state, page, menu.searchPlaceholder, menu.entries(page.query.text), metrics, canCascade, trackRows = false)
                                }
                            }
                        }
                    }
                }
                val sub = state.submenu?.takeIf { state.cascade }
                val menu = sub?.let { state.submenuOf(it.key) }
                if (sub != null && menu != null) {
                    Column(
                        Modifier
                            .layoutId("submenu")
                            .graphicsLayer {
                                val p = subProgress
                                val scale = MENU_ENTER_SCALE + (1f - MENU_ENTER_SCALE) * p
                                scaleX = scale
                                scaleY = scale
                                alpha = p
                                transformOrigin = subOrigin
                            }
                            .menuShadow(CursorTheme.colors.elevated, CursorTheme.colors.shadow)
                            .cursorSurface(CursorTheme.colors.elevated, CursorTheme.colors.strokeSubtle, CursorTheme.shapes.menu)
                            .hitTestBoundary()
                            .semantics { paneTitle = menu.title }
                            .testTag(PickerTags.Submenu),
                    ) {
                        PageBody(state, sub, menu.searchPlaceholder, menu.entries(sub.query.text), metrics, canCascade, trackRows = false)
                    }
                }
            },
        ) { measurables, constraints ->
            val full = Constraints.fixed(constraints.maxWidth, constraints.maxHeight)
            val bounds = local(area)
            val anchorHere = local(anchor)
            val backdrop = measurables.first { it.layoutId == "backdrop" }.measure(full)
            val rootWidth = width.roundToPx().coerceAtMost(max(0, bounds.width))
            val rootHeight = PickerGeometry.maxHeight(anchorHere, bounds, gap)
            val root = measurables.first { it.layoutId == "root" }.measure(Constraints(minWidth = rootWidth, maxWidth = rootWidth, maxHeight = max(0, rootHeight)))
            val placement = PickerGeometry.place(anchorHere, IntSize(root.width, root.height), bounds, gap, ltr)
            state.rootBounds = IntRect(placement.offset, IntSize(root.width, root.height))
            state.rootOrigin = placement.origin
            state.below = placement.below

            val subMeasurable = measurables.firstOrNull { it.layoutId == "submenu" }
            val subKey = state.submenu?.key
            val subMenu = state.submenuOf(subKey)
            val sub = if (subMeasurable != null && subMenu != null) {
                val w = subMenu.width.roundToPx().coerceAtMost(max(0, bounds.width))
                subMeasurable.measure(Constraints(minWidth = w, maxWidth = w, maxHeight = max(0, bounds.height)))
            } else {
                null
            }
            val row = subKey?.let { state.rowBounds[it] } ?: state.rootBounds
            val subPlacement = sub?.let { PickerGeometry.placeSubmenu(row, state.rootBounds, IntSize(it.width, it.height), bounds, gap, inset, ltr) }
            subPlacement?.let { subOrigin = it.origin }

            layout(constraints.maxWidth, constraints.maxHeight) {
                backdrop.place(0, 0)
                root.place(placement.offset)
                if (sub != null) {
                    val at = subPlacement?.offset ?: IntOffset(bounds.right - sub.width, row.top - inset)
                    sub.place(at)
                }
            }
        }
    }
}

/** The bottom-sheet picker, for a phone with no room beside the anchor: the same rows, with submenus as pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetPicker(state: PickerState, modifier: Modifier, testTag: String) {
    val metrics = pickerMetrics()
    val rootFocus = remember { FocusRequester() }
    CursorSheet(onDismiss = state::finished, modifier = modifier) { dismiss ->
        state.sheetDismiss = dismiss
        BackHandler(enabled = state.submenu != null) { state.closeSubmenu() }
        LaunchedEffect(Unit) {
            runCatching { rootFocus.requestFocus() }
            state.restoreSubmenu { false }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { state.handleKey(it) { false } }
                .semantics { paneTitle = state.title }
                .testTag(testTag),
        ) {
            AnimatedContent(
                targetState = state.submenu,
                transitionSpec = {
                    val slide = tween<IntOffset>(PAGE_MILLIS, easing = MenuEnterEasing)
                    val pages = if (targetState != null) slideInHorizontally(slide) { it } togetherWith slideOutHorizontally(slide) { -it }
                    else slideInHorizontally(slide) { -it } togetherWith slideOutHorizontally(slide) { it }
                    pages using SizeTransform { _, _ -> tween(PAGE_MILLIS, easing = MenuEnterEasing) }
                },
                label = "picker-sheet-page",
            ) { page ->
                Column {
                    val menu = page?.let { state.submenuOf(it.key) }
                    SheetHeader(
                        menu?.title ?: state.title,
                        leading = if (page != null) { { FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = state::closeSubmenu, modifier = Modifier.testTag(PickerTags.Back)) } } else null,
                    )
                    if (page == null || menu == null) {
                        PageBody(state, state.root, state.searchPlaceholder, state.rootEntries, metrics, { false }, trackRows = false, sheet = true)
                    } else {
                        PageBody(state, page, menu.searchPlaceholder, menu.entries(page.query.text), metrics, { false }, trackRows = false, sheet = true)
                    }
                }
            }
        }
    }
}

/** A page's search field and rows. [trackRows] records each row's bounds, for a submenu to line up with its row. */
@Composable
private fun ColumnScope.PageBody(
    state: PickerState,
    page: PickerPage,
    searchPlaceholder: String?,
    entries: List<PickerEntry>,
    metrics: PickerMetrics,
    canCascade: (String) -> Boolean,
    trackRows: Boolean,
    sheet: Boolean = false,
) {
    if (searchPlaceholder != null) {
        PickerSearchField(page, searchPlaceholder, metrics, sheet)
        HairlineDivider(Modifier.padding(horizontal = if (sheet) 12.dp else 0.dp))
    }
    val highlight = page.highlightIndex(entries)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (highlight - 3).coerceAtLeast(0).takeIf { highlight > 6 } ?: 0)
    LaunchedEffect(page.keyMoves) { if (page.keyMoves > 0) listState.reveal(page.highlightIndex(state.pageEntries(page))) }
    val openKey = state.submenu?.key?.takeIf { page === state.root && state.cascade && state.submenuVisible.targetState }
    FadingLazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag(PickerTags.List),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = if (sheet) 4.dp else CursorDimens.menuInset),
        surface = CursorTheme.colors.elevated,
    ) {
        itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
            val rowModifier = if (trackRows) {
                Modifier.onGloballyPositioned { state.rowBounds[entry.key] = it.boundsInWindow().let { b -> IntRect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()) } }
            } else {
                Modifier
            }
            val highlighted = index == highlight || entry.key == openKey
            val hover = { page.highlightKey = entry.key }
            val pick = { state.activate(page, entry, canCascade) }
            when (entry) {
                is PickerSection -> PickerSectionHeader(entry.title, rowModifier, sheet)
                is PickerItem -> PickerItemRow(entry, highlighted, metrics, rowModifier, sheet, open = entry.key == openKey, onHover = hover, onClick = pick)
                is PickerToggle -> PickerToggleRow(entry, highlighted, metrics, rowModifier, sheet, onHover = hover, onClick = pick)
                is PickerAction -> PickerActionRow(entry, highlighted, metrics, rowModifier, sheet, onHover = hover, onClick = pick)
                is PickerNote -> PickerNoteText(entry.text, rowModifier, sheet)
                is PickerDivider -> HairlineDivider(rowModifier.padding(vertical = CursorDimens.menuInset, horizontal = if (sheet) 12.dp else 0.dp))
            }
        }
    }
}

private fun PickerState.pageEntries(page: PickerPage): List<PickerEntry> =
    if (page === root) rootEntries else submenuOf(page.key)?.entries?.invoke(page.query.text).orEmpty()

private suspend fun LazyListState.reveal(index: Int) {
    if (index < 0) return
    val visible = layoutInfo.visibleItemsInfo
    val item = visible.firstOrNull { it.index == index }
    val viewportEnd = layoutInfo.viewportEndOffset
    if (item == null || item.offset < layoutInfo.viewportStartOffset || item.offset + item.size > viewportEnd) {
        if (item != null && item.offset + item.size > viewportEnd) animateScrollBy((item.offset + item.size - viewportEnd).toFloat())
        else animateScrollToItem(index)
    }
}

/** Hover moves the highlight, as a pointer over the desktop's menus does; a finger has no hover. */
private fun Modifier.onHover(onHover: () -> Unit): Modifier = pointerInput(onHover) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Enter || event.type == PointerEventType.Move) {
                if (event.changes.any { it.type == PointerType.Mouse || it.type == PointerType.Stylus }) onHover()
            }
        }
    }
}

/** The row's inset highlight, concentric with the surface, and the row's press. */
@Composable
private fun Modifier.pickerRowShell(highlighted: Boolean, sheet: Boolean, enabled: Boolean, role: Role?, onClick: () -> Unit): Modifier {
    val colors = CursorTheme.colors
    val shape = if (sheet) CursorTheme.shapes.base else CursorTheme.shapes.menuItem
    val interaction = remember { MutableInteractionSource() }
    return this
        .fillMaxWidth()
        .padding(horizontal = if (sheet) 8.dp else CursorDimens.menuInset)
        .then(if (highlighted) Modifier.background(colors.fill, shape) else Modifier)
        .semantics { pickerHighlighted = highlighted }
        .clip(shape)
        .clickable(interactionSource = interaction, indication = ripple(color = colors.base, bounded = true), enabled = enabled, role = role, onClick = onClick)
}

@Composable
private fun PickerItemRow(
    item: PickerItem,
    highlighted: Boolean,
    metrics: PickerMetrics,
    modifier: Modifier,
    sheet: Boolean,
    open: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val faded = !item.enabled || item.dimmed
    val labelColor = if (faded) colors.textQuaternary else colors.textPrimary
    val glyph = when {
        faded -> colors.iconQuaternary
        item.iconTint != null -> item.iconTint
        else -> colors.iconSecondary
    }
    Row(
        modifier
            .onHover(onHover)
            .pickerRowShell(highlighted, sheet, item.enabled, role = null, onClick = onClick)
            .semantics {
                if (item.submenu == null || item.selected) selected = item.selected
                if (item.submenu != null) stateDescription = if (open) "Submenu open" else "Has submenu"
            }
            .then(item.testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .heightIn(min = metrics.row)
            .padding(start = rowStart(sheet), end = if (item.trailingAction != null) 2.dp else rowEnd(sheet), top = if (item.subtitle != null) 6.dp else 0.dp, bottom = if (item.subtitle != null) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.icon != null) {
            Icon(item.icon, null, tint = glyph, modifier = Modifier.size(CursorDimens.menuIcon))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, style = metrics.label, color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f, fill = false))
                if (item.detail != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(item.detail, style = metrics.label, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                if (item.badge != null) {
                    Spacer(Modifier.width(6.dp))
                    PickerBadge(item.badge)
                }
            }
            if (item.subtitle != null) {
                Text(item.subtitle, style = type.small, color = if (faded) colors.textQuaternary else colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.selected) {
            Spacer(Modifier.width(8.dp))
            Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(CursorDimens.menuIcon))
        }
        item.trailingAction?.let { action ->
            Spacer(Modifier.width(2.dp))
            Box(
                Modifier
                    .size(width = 36.dp, height = CursorDimens.iconButton)
                    .pressable(action.onClick, CursorTheme.shapes.menuItem)
                    .semantics { contentDescription = action.contentDescription },
                contentAlignment = Alignment.Center,
            ) {
                Icon(action.icon, null, tint = if (action.active) colors.accent else colors.iconQuaternary, modifier = Modifier.size(14.dp))
            }
        }
        if (item.submenu != null) {
            Spacer(Modifier.width(6.dp))
            Icon(CursorIcons.ChevronRight, null, tint = if (open) colors.iconSecondary else colors.iconTertiary, modifier = Modifier.size(14.dp))
            if (item.trailingAction != null) Spacer(Modifier.width(rowEnd(sheet) - 2.dp))
        }
    }
}

@Composable
private fun PickerBadge(text: String) {
    val colors = CursorTheme.colors
    Box(
        Modifier
            .background(colors.accent.copy(alpha = 0.16f), CursorTheme.shapes.sm)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, style = CursorTheme.typography.tiny, color = colors.accent, maxLines = 1)
    }
}

@Composable
private fun PickerToggleRow(
    toggle: PickerToggle,
    highlighted: Boolean,
    metrics: PickerMetrics,
    modifier: Modifier,
    sheet: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = CursorTheme.colors
    val haptics = rememberHaptics()
    Row(
        modifier
            .onHover(onHover)
            .pickerRowShell(highlighted, sheet, toggle.enabled, role = Role.Switch) { haptics.toggle(!toggle.checked); onClick() }
            .semantics { toggleableState = ToggleableState(toggle.checked) }
            .heightIn(min = metrics.row)
            .padding(start = rowStart(sheet), end = rowEnd(sheet)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (toggle.icon != null) {
            Icon(toggle.icon, null, tint = if (toggle.enabled) colors.iconSecondary else colors.iconQuaternary, modifier = Modifier.size(CursorDimens.menuIcon))
            Spacer(Modifier.width(10.dp))
        }
        Text(toggle.label, style = metrics.label, color = if (toggle.enabled) colors.textPrimary else colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        CursorToggle(toggle.checked, onCheckedChange = { toggle.onToggle(it) }, enabled = toggle.enabled, modifier = Modifier.semantics { role = Role.Switch })
    }
}

@Composable
private fun PickerActionRow(
    action: PickerAction,
    highlighted: Boolean,
    metrics: PickerMetrics,
    modifier: Modifier,
    sheet: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = CursorTheme.colors
    val tone = if (action.enabled) colors.textSecondary else colors.textQuaternary
    Row(
        modifier
            .onHover(onHover)
            .pickerRowShell(highlighted, sheet, action.enabled && !action.busy, role = Role.Button, onClick = onClick)
            .semantics { if (action.selected) selected = true }
            .then(action.testTag?.let { Modifier.testTag(it) } ?: Modifier)
            .heightIn(min = metrics.row)
            .padding(start = rowStart(sheet), end = rowEnd(sheet)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(CursorDimens.menuIcon), contentAlignment = Alignment.Center) {
            if (action.busy) SpinnerRing(size = 12.dp) else Icon(action.icon, null, tint = if (action.enabled) colors.iconSecondary else colors.iconQuaternary, modifier = Modifier.size(CursorDimens.menuIcon))
        }
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(action.label, style = metrics.label, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f, fill = false))
            if (action.detail != null) {
                Spacer(Modifier.width(6.dp))
                Text(action.detail, style = metrics.label, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
        if (action.selected) {
            Spacer(Modifier.width(8.dp))
            Icon(CursorIcons.Check, null, tint = colors.accent, modifier = Modifier.size(CursorDimens.menuIcon))
        }
    }
}

@Composable
private fun PickerSectionHeader(title: String, modifier: Modifier, sheet: Boolean) {
    Text(
        title,
        style = CursorTheme.typography.small,
        color = CursorTheme.colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() }
            .padding(start = textInset(sheet), end = textInset(sheet), top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun PickerNoteText(text: String, modifier: Modifier, sheet: Boolean) {
    Text(
        text,
        style = CursorTheme.typography.small,
        color = CursorTheme.colors.textQuaternary,
        modifier = modifier.fillMaxWidth().padding(horizontal = textInset(sheet), vertical = 8.dp),
    )
}

/** The search row: a magnifier, the placeholder, flush on the surface with the hairline under it, as Cursor's are. */
@Composable
private fun PickerSearchField(page: PickerPage, placeholder: String, metrics: PickerMetrics, sheet: Boolean) {
    val colors = CursorTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(metrics.row)
            .stylusWriting()
            .padding(horizontal = textInset(sheet)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.Search, null, tint = colors.iconTertiary, modifier = Modifier.size(CursorDimens.menuIcon))
        Spacer(Modifier.width(10.dp))
        StylusTextInput {
            BasicTextField(
                value = page.query,
                onValueChange = {
                    if (it.text != page.query.text) page.highlightKey = null
                    page.query = it
                },
                singleLine = true,
                textStyle = metrics.label.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(page.fieldFocus)
                    .onFocusChanged { page.fieldFocused = it.isFocused }
                    .testTag(PickerTags.Search),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (page.query.text.isEmpty()) Text(placeholder, style = metrics.label, color = colors.textQuaternary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
        }
    }
}

/** A submenu opened in the picker's place: the way back, then its title. */
@Composable
private fun BackRow(title: String, metrics: PickerMetrics, onBack: () -> Unit) {
    val colors = CursorTheme.colors
    Column {
        Row(
            Modifier
                .padding(top = CursorDimens.menuInset)
                .pickerRowShell(highlighted = false, sheet = false, enabled = true, role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back" }
                .testTag(PickerTags.Back)
                .heightIn(min = metrics.row)
                .padding(start = rowStart(false) - 2.dp, end = rowEnd(false)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(CursorIcons.ChevronLeft, null, tint = colors.iconSecondary, modifier = Modifier.size(CursorDimens.menuIcon))
            Spacer(Modifier.width(8.dp))
            Text(title, style = metrics.label.copy(fontWeight = CursorTheme.typography.baseMedium.fontWeight), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(CursorDimens.menuInset))
        HairlineDivider()
    }
}

private fun rowStart(sheet: Boolean): Dp = if (sheet) 12.dp else CursorDimens.menuItemPadding
private fun rowEnd(sheet: Boolean): Dp = if (sheet) 12.dp else CursorDimens.menuItemPadding
private fun textInset(sheet: Boolean): Dp = if (sheet) 20.dp else CursorDimens.menuTextInset

private const val PAGE_MILLIS = 220

/** Lowercase, space-insensitive containment, for a picker's search over a label and its detail. */
fun pickerMatches(query: String, vararg fields: String?): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return fields.any { it != null && it.contains(q, ignoreCase = true) }
}
