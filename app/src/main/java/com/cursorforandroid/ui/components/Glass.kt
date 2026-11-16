package com.cursorforandroid.ui.components

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/**
 * The content that scrolls under the chat's bottom dock — the transcript — so the composer and the cards docked over
 * it can show it through themselves, blurred, instead of the dock cutting it off behind a fade. Provided by a screen
 * whose content runs under its dock; null everywhere else, where every glass surface is the plain opaque one it was.
 */
val LocalGlass = staticCompositionLocalOf<HazeState?> { null }

/** How see-through a glass surface's fill is: enough of the chat shows that it reads as beneath, not behind a wall. */
internal const val GlassFillAlpha = 0.72f

/** The blur of what shows through: soft enough that text under the composer is shape and colour, never letters. */
internal val GlassBlur: Dp = 20.dp

/** Marks the content glass surfaces show through. */
fun Modifier.glassSource(state: HazeState?): Modifier = if (state == null) this else haze(state)

/**
 * A surface over [LocalGlass]'s content in [shape]: that content blurred and tinted with [fill] at [GlassFillAlpha],
 * with the hairline [border]. Without glass in place, the opaque [cursorSurface] it stands in for, pixel for pixel.
 * Never clips: what lies inside (the composer's main button's touch area) may reach past the shape.
 */
@Composable
fun Modifier.glassSurface(fill: Color, border: Color, shape: Shape): Modifier {
    val state = LocalGlass.current ?: return cursorSurface(fill, border, shape, clip = false)
    val canvas = CursorTheme.colors.canvas
    return this
        .hazeChild(state, shape, HazeStyle(backgroundColor = canvas, tint = HazeTint(fill.copy(alpha = GlassFillAlpha * fill.alpha)), blurRadius = GlassBlur, noiseFactor = 0.02f))
        .then(if (border.alpha > 0f) Modifier.border(CursorDimens.hairline, border, shape) else Modifier)
}

/**
 * The band the chat's bottom dock stands on: the content under it blurred, from nothing at the band's top edge to the
 * full [GlassBlur] where the dock begins ([rampFraction] of the way down) and beyond, with a canvas tint rising the
 * same way. It replaces the fade the transcript used to dissolve into above the composer: the rows go on under the
 * dock, softened rather than cut, and the gutters beside and below the composer are as quiet as the composer is.
 */
@Composable
fun Modifier.glassBand(rampFraction: Float): Modifier {
    val state = LocalGlass.current ?: return this
    val canvas = CursorTheme.colors.canvas
    val ramp = rampFraction.coerceIn(0.05f, 1f)
    return hazeChild(state) {
        style = HazeStyle(backgroundColor = canvas, tint = HazeTint(canvas.copy(alpha = 0.55f)), blurRadius = GlassBlur, noiseFactor = 0f)
        // The blurred, tinted copy fades in down the band: a progressive blur by its mask, the same on every API level.
        mask = Brush.verticalGradient(0f to Color.Transparent, ramp to Color.Black, 1f to Color.Black)
    }
}
