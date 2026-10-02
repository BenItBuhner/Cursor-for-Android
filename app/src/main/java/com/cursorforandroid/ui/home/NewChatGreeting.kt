package com.cursorforandroid.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cursorforandroid.data.local.GreetingMemory
import com.cursorforandroid.domain.CursorUser
import com.cursorforandroid.domain.FirstName
import com.cursorforandroid.domain.Greeting
import com.cursorforandroid.domain.GreetingBucket
import com.cursorforandroid.domain.GreetingContext
import com.cursorforandroid.domain.GreetingLine
import com.cursorforandroid.domain.GreetingLog
import com.cursorforandroid.domain.GreetingMoments
import com.cursorforandroid.domain.GreetingRhythm
import com.cursorforandroid.domain.Greetings
import com.cursorforandroid.domain.Hemisphere
import com.cursorforandroid.ui.agents.AgentListUiState
import com.cursorforandroid.ui.theme.CursorTheme
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The page's greeting, picked when the page is composed for a visit and again when the part of the day (or the day)
 * turns with the page open — read on each composition of the page, which the list's minute tick brings — and at no
 * other time: not as the list changes under it, nor as the window rotates (the activity is kept across configuration
 * changes). The line is recorded as shown, so the next picks keep off it and the reader's visits are counted.
 *
 * A name arriving or changing (the account's profile republished) renders into the same line rather than picking
 * another, unless the line needs the name and it is gone.
 */
@Composable
internal fun rememberNewChatGreeting(memory: GreetingMemory, user: CursorUser?, list: AgentListUiState): ShownGreeting {
    val zone = remember { ZoneId.systemDefault() }
    val name = remember(user?.firstName, user?.email) { FirstName.of(user) }
    val bucket = GreetingBucket.of(AppClock.now(), zone)
    val visit = remember { Visit() }
    val first = remember(bucket) {
        pickNewChatGreeting(AppClock.now(), zone, name, list, memory.peek(), firstOfVisit = !visit.picked).also { visit.picked = true }
    }
    val picked = if (name == null && first.greeting.line.needsName) {
        remember(first) { pickNewChatGreeting(AppClock.now(), zone, null, list, memory.peek(), firstOfVisit = false) }
    } else {
        first
    }
    LaunchedEffect(picked) { memory.shown(picked.greeting.line.id, AppClock.now(), zone) }
    val text = remember(picked, name) { picked.greeting.line.render(picked.context.copy(name = name)) }
    return ShownGreeting(Greeting(picked.greeting.line, text), picked)
}

/**
 * The page's pick at [now]: the moment read from the clock and [list], the reader's rhythm from [log] — on the
 * visit's first line alone; a later one is the clock's turning, the reader still here — and the seed from both.
 */
internal fun pickNewChatGreeting(now: Long, zone: ZoneId, name: String?, list: AgentListUiState, log: GreetingLog, firstOfVisit: Boolean): PickedGreeting {
    val moments = GreetingMoments.of(list.runningCount, list.allAgents, list.recentRows, list.projectRows, now, zone)
    val context = GreetingContext(
        at = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone),
        name = name,
        southern = Hemisphere.southern(zone.id),
        running = moments.running,
        justFinished = moments.justFinished,
        prMerged = moments.prMerged,
        newProject = moments.newProject,
        chatsToday = moments.chatsToday,
        rhythm = if (firstOfVisit) log.rhythm(now, zone) else GreetingRhythm(),
    )
    return PickedGreeting(Greetings.pick(context, log.recent, Random(Greetings.seed(now, log.recent))), context)
}

/** A greeting as the page shows it; [pick] is what its entrance is keyed on, so a name filled in does not play it again. */
internal class ShownGreeting(val greeting: Greeting, val pick: Any) {
    val line: GreetingLine get() = greeting.line
    val text: String get() = greeting.text
}

private class Visit {
    var picked = false
}

internal class PickedGreeting(val greeting: Greeting, val context: GreetingContext)

/**
 * The greeting heading the composer's block, left-aligned over the selectors in the page's display type. It comes in
 * word by word, each popping up into place on a spring a beat after the one before; with animations removed it is
 * simply there. It plays once per line, not on recomposition.
 *
 * With the keyboard up it stays, the composer centred under it as one block (see [PageCentring.alone]): a heading
 * takes no touch and lists nothing, and the page alone with a bare composer reads emptier than with its greeting.
 * Only where the keyboard leaves less than [FoldBelow] above it (a phone in landscape) does it fold away as the
 * keyboard rises, leaving the room to the composer, and leave the accessibility tree while folded.
 */
@Composable
internal fun NewChatGreeting(shown: ShownGreeting, focus: KeyboardFocus, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val display = CursorTheme.typography.display
    val wide = LocalConfiguration.current.screenWidthDp >= WideFrom
    val style = if (wide) display.copy(fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp) else display
    val words = remember(shown.text) { wordRanges(shown.text) }
    val entrance = remember(shown.pick) { Entrance(words.size, settled = focus.reducedMotion) }
    LaunchedEffect(entrance) { entrance.play() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val folded by remember(focus) { derivedStateOf { focus.engaged.value && focus.roomAbove < FoldBelow } }
    Text(
        shown.text,
        style = style,
        color = colors.textPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .foldedUnder(focus)
            .then(if (folded) Modifier.clearAndSetSemantics {} else Modifier.semantics { heading() })
            .testTag(NewChatHomeTags.GREETING)
            .padding(start = GreetingInset, end = GreetingInset, bottom = GreetingGap)
            .drawWithContent {
                val fontPx = style.fontSize.toPx()
                with(entrance) { draw(layout, words, rise = fontPx * RiseEm, pad = fontPx * PadEm) }
            },
    )
}

/** How far the greeting is folded away: by as much as the keyboard is up, where it leaves less than [FoldBelow]. */
private fun KeyboardFocus.fold(): Float = if (roomAbove < FoldBelow) fraction else 0f

/** Shortened from the top by [fold], its words slid up out of the way and faded, all in layout and drawing. */
private fun Modifier.foldedUnder(focus: KeyboardFocus): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val fold = focus.fold().coerceIn(0f, 1f)
    val height = (placeable.height * (1f - fold)).roundToInt()
    layout(placeable.width, height) {
        placeable.placeWithLayer(0, height - placeable.height) { alpha = (1f - fold * 2f).coerceIn(0f, 1f) }
    }
}

/** The character ranges of [text]'s words, split at whitespace. */
internal fun wordRanges(text: String): List<IntRange> = Regex("\\S+").findAll(text).map { it.range }.toList()

/** One line's entrance: each word's spring, 0 (not yet up) to 1 (in place), overshooting on the way. */
@Stable
private class Entrance(count: Int, settled: Boolean) {
    private val words = List(count) { Animatable(if (settled) 1f else 0f) }
    var done by mutableStateOf(settled)
        private set

    suspend fun play() {
        if (done) return
        coroutineScope {
            words.forEachIndexed { i, word ->
                launch {
                    delay(i * StaggerMillis)
                    word.animateTo(1f, Pop)
                }
            }
        }
        done = true
    }

    /**
     * Draws the text as the entrance has it: whole once it is done; until then each word on its own, clipped to its
     * box, risen by [rise] times what of its spring is left, scaled up from [StartScale], and faded in.
     */
    fun ContentDrawScope.draw(layout: TextLayoutResult?, ranges: List<IntRange>, rise: Float, pad: Float) {
        if (done || (layout != null && ranges.size != words.size)) {
            drawContent()
            return
        }
        layout ?: return
        val canvas = drawContext.canvas
        val paint = Paint()
        ranges.forEachIndexed { i, range ->
            val progress = words[i].value
            if (progress <= 0f) return@forEachIndexed
            val line = layout.getLineForOffset(range.first)
            if (line >= layout.lineCount || layout.isLineEllipsized(line) && range.first >= layout.getLineEnd(line, visibleEnd = true)) return@forEachIndexed
            val a = layout.getHorizontalPosition(range.first, usePrimaryDirection = true)
            val b = layout.getHorizontalPosition(range.last + 1, usePrimaryDirection = true)
            val box = Rect(minOf(a, b) - pad, layout.getLineTop(line), maxOf(a, b) + pad, layout.getLineBottom(line))
            val scale = StartScale + (1f - StartScale) * progress
            paint.alpha = (progress * 1.6f).coerceIn(0f, 1f)
            withTransform({
                translate(top = rise * (1f - progress))
                scale(scale, scale, pivot = box.center)
            }) {
                canvas.saveLayer(box, paint)
                clipRect(box.left, box.top, box.right, box.bottom) { this@draw.drawContent() }
                canvas.restore()
            }
        }
    }
}

/** Where the keyboard leaves less than this above it, the greeting folds away for the composer. */
internal val FoldBelow: Dp = 340.dp

/** Between the greeting and the selectors under it. */
internal val GreetingGap: Dp = 14.dp

/** In from the block's edge: level with the selectors' first label, which sit inset in their chips. */
private val GreetingInset: Dp = 8.dp

/** From this window width up (tablets, unfolded foldables) the greeting is a size larger. */
private const val WideFrom = 600

private const val StaggerMillis = 55L
private const val StartScale = 0.86f
/** How far below its place a word starts, in ems. */
private const val RiseEm = 0.45f
/** Each word's box reaches this far into the space beside it, so no glyph's overhang is clipped. */
private const val PadEm = 0.12f
private val Pop = spring<Float>(dampingRatio = 0.55f, stiffness = 380f)
