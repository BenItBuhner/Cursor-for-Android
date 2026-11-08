package com.cursorforandroid.ui.home

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.components.hardwareKeyboardAttached

/**
 * The New Chat page given over to its composer while the on-screen keyboard is up for it: what the page lists under
 * the composer — the Project shortcuts, the recent chats — fades and slides down out of view, and the composer glides
 * to the middle of the room left above the keyboard (see [PageCentring.alone]), so the page is the composer alone while
 * it is typed into. As the keyboard goes — dismissed, Back pressed, or scrubbed down by predictive back — the page
 * comes back the same way, the draft untouched.
 *
 * [fraction] says how far, 0 to 1, and is read as the page lays out and draws, never as it composes. It is the
 * keyboard's own progress: the share of its height risen, which the platform reports through `WindowInsetsAnimation`
 * every frame it moves and Compose relays through [WindowInsets.ime]. The composer and the lists therefore ride the
 * keyboard's curve frame by frame instead of running an animation of their own beside it, and stop where it stops.
 *
 * When it applies is [keyboardTakesPage]'s: the composer focused, an on-screen keyboard rather than a hardware one, of
 * a keyboard's height, on a phone-style page. Focus and a hardware keyboard coming or going while the keyboard is up
 * ease the page over in [GateMillis] rather than in one frame; with animations removed in the system's settings that
 * is immediate too, and the lists only fade (the keyboard itself then appears in one step).
 */
@Stable
internal class KeyboardFocus internal constructor(
    private val gate: Animatable<Float, AnimationVector1D>,
    /** Animations removed in the system's settings: the lists fade without sliding. */
    val reducedMotion: Boolean,
    private val keyboard: () -> Float,
    private val room: () -> Dp = { Dp.Infinity },
) {
    /** How far the page is given over to the composer, 0 to 1. Read in layout and drawing. */
    val fraction: Float get() = gate.value * keyboard()

    /** The pane's height left above the keyboard at its full height (all of it with none). Read in layout and drawing. */
    val roomAbove: Dp get() = room()

    /** Any of the page given over: its lists take no touch and say nothing to accessibility services. */
    val engaged: State<Boolean> = derivedStateOf { fraction > 0f }

    internal suspend fun follow(on: Boolean) {
        val target = if (on) 1f else 0f
        if (reducedMotion || keyboard() == 0f) gate.snapTo(target) else gate.animateTo(target, tween(GateMillis, easing = FastOutSlowInEasing))
    }

    companion object {
        /** A keyboard's change of focus or of kind, while it is up: the page eases over in this long. */
        const val GateMillis = 220
    }
}

/**
 * The page's [KeyboardFocus] while [composerFocused], for a pane [paneHeight] px tall (the page's own height, the
 * keyboard not taken off it). Read once per composition of the page; what changes frame by frame is read in [KeyboardFocus.fraction].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberKeyboardFocus(composerFocused: Boolean, paneHeight: () -> Int): KeyboardFocus {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val from = WindowInsets.imeAnimationSource
    val to = WindowInsets.imeAnimationTarget
    val windowWidth by rememberUpdatedState(configuration.screenWidthDp.dp)
    val reducedMotion = rememberReducedMotion()
    val gate = remember { Animatable(0f) }
    val focus = remember(density, reducedMotion) {
        fun fullKeyboard() = maxOf(ime.getBottom(density), from.getBottom(density), to.getBottom(density))
        KeyboardFocus(
            gate,
            reducedMotion = reducedMotion,
            keyboard = {
                val now = ime.getBottom(density)
                val start = from.getBottom(density)
                val end = to.getBottom(density)
                val keyboard = maxOf(now, start, end)
                val takes = keyboardTakesPage(
                    // A hardware keyboard is the gate's, so that one attached with the keyboard up eases the page back.
                    hardwareKeyboard = false,
                    keyboard = density.dp(keyboard),
                    windowWidth = windowWidth,
                    roomAbove = density.dp(paneHeight() - keyboard),
                )
                if (takes) keyboardRisen(now, start, end) else 0f
            },
            room = { density.dp(paneHeight() - fullKeyboard()) },
        )
    }
    val on = composerFocused && !configuration.hardwareKeyboardAttached
    LaunchedEffect(focus, on) { focus.follow(on) }
    return focus
}

private fun Density.dp(px: Int): Dp = px.toDp()

/**
 * How much of the keyboard is up, 0 to 1, from its inset [now] and the insets its animation runs [from] and [to] (the
 * three equal while it stands still). Up and standing, or moving between two heights of its own (a panel of emoji
 * opened over it), it is all the way up; rising or falling, it is the share of its full height showing.
 */
internal fun keyboardRisen(now: Int, from: Int, to: Int): Float {
    val full = maxOf(now, from, to)
    if (now <= 0 || full <= 0) return 0f
    if (minOf(from, to) > 0) return 1f
    return (now.toFloat() / full).coerceIn(0f, 1f)
}

/**
 * Whether a [keyboard] this tall gives the page over to its composer, in a window [windowWidth] wide that leaves
 * [roomAbove] above it. Never with a [hardwareKeyboard] attached (a physical keyboard, a tablet's keyboard cover): the
 * page has the room it had, and what the platform still reports at the foot — a suggestion strip, a floating
 * keyboard's leftover — is shorter than [MinKeyboard], which rules it out on its own.
 *
 * Phone-style means the window is Compact in width (narrower than [CompactWidth], the shell's own breakpoint between
 * the drawer and the rail: a phone in portrait, a foldable folded), or the keyboard leaves less than [RoomForPage]
 * above it, the Compact height class, where the lists would only show as a sliver under the composer: a phone in
 * landscape, and a tablet or an unfolded foldable held in landscape with the keyboard docked. A tablet or an unfolded
 * foldable in portrait keeps its page with the keyboard docked: the composer and the first of what it lists still
 * fit above the keyboard, as they do on a desktop.
 */
internal fun keyboardTakesPage(hardwareKeyboard: Boolean, keyboard: Dp, windowWidth: Dp, roomAbove: Dp): Boolean =
    !hardwareKeyboard && keyboard >= MinKeyboard && (windowWidth < CompactWidth || roomAbove < RoomForPage)

/** Shorter than this, what stands at the foot is not an on-screen keyboard: a suggestion strip, a floating keyboard's leftover. */
internal val MinKeyboard = 120.dp
internal val CompactWidth = 600.dp
internal val RoomForPage = 480.dp

/** How far the lists slide down as the keyboard rises, on top of where the composer takes them. */
internal val ListSlide = 64.dp

/** How far up the keyboard is when the lists have faded out entirely: they are gone before it reaches them. */
private const val FadeBy = 0.6f

/** What a list at [fraction] given over is drawn at: opaque at 0, clear from [FadeBy] on. */
internal fun listAlpha(fraction: Float): Float = (1f - fraction / FadeBy).coerceIn(0f, 1f)

/**
 * One thing the page lists under its composer, as [focus] gives the page over: faded and slid down by
 * [ListSlide] in step with the keyboard (faded alone with animations removed), and — while any of the page is given
 * over — taking no touch and leaving the accessibility tree, so TalkBack reads the composer alone, as it is shown.
 */
@Composable
internal fun Modifier.listedUnder(focus: KeyboardFocus): Modifier {
    val slide = with(LocalDensity.current) { ListSlide.toPx() }
    val engaged by focus.engaged
    return this
        .graphicsLayer {
            val fraction = focus.fraction
            alpha = listAlpha(fraction)
            translationY = if (focus.reducedMotion) 0f else slide * fraction
        }
        .then(if (engaged) GivenOver else Modifier)
}

private val GivenOver = Modifier
    .clearAndSetSemantics { testTag = NewChatHomeTags.LISTED_HIDDEN }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

/** Animations removed in the system's settings (Accessibility › Remove animations sets the animator scale to 0). */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
