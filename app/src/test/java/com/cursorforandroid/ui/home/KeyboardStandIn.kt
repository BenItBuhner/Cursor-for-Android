package com.cursorforandroid.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the screenshots draw where the window's IME inset is, since Robolectric has no keyboard window: a QWERTY panel
 * [full] tall, standing on the window's foot and showing as much of itself as the inset is high, so it slides up and
 * down with the inset frame by frame rather than squashing. An inset no taller than [SuggestionStrip] (a hardware keyboard's
 * suggestion strip) is drawn as the strip alone.
 */
@Composable
internal fun KeyboardStandIn(full: Dp, dark: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val inset = WindowInsets.ime.getBottom(LocalDensity.current)
    val panel = if (dark) Color(0xFF1B1B1F) else Color(0xFFE3E5EA)
    val key = if (dark) Color(0xFF3B3B42) else Color(0xFFFFFFFF)
    val modKey = if (dark) Color(0xFF2A2A30) else Color(0xFFC9CDD4)
    val label = if (dark) Color(0xFFE8E8EC) else Color(0xFF202124)
    val hint = if (dark) Color(0xFF9A9AA2) else Color(0xFF5F6368)
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (inset <= 0) return@Canvas
            val strip = SuggestionStrip.toPx()
            val height = if (inset <= strip + 1f) inset.toFloat() else maxOf(full.toPx(), inset.toFloat())
            val top = size.height - inset
            clipRect(top = top) {
                drawRect(panel, Offset(0f, top), Size(size.width, height))
                val words = listOf("the", "I", "fix")
                val third = size.width / 3f
                words.forEachIndexed { i, word ->
                    val text = measurer.measure(word, TextStyle(color = hint, fontSize = 15.sp))
                    drawText(text, topLeft = Offset(third * i + (third - text.size.width) / 2f, top + (strip - text.size.height) / 2f))
                }
                if (height <= strip + 1f) return@clipRect
                val gap = 6.dp.toPx()
                val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
                val keysTop = top + strip
                val rowHeight = (height - strip - 24.dp.toPx() - gap) / 4f
                val keyWidth = (size.width - gap * 11) / 10f
                val radius = CornerRadius(6.dp.toPx())
                fun keyAt(x: Float, row: Int, width: Float, color: Color, text: String?) {
                    val y = keysTop + gap + row * rowHeight
                    drawRoundRect(color, Offset(x, y), Size(width, rowHeight - gap), radius)
                    if (text != null) {
                        val laid = measurer.measure(text, TextStyle(color = label, fontSize = 20.sp))
                        drawText(laid, topLeft = Offset(x + (width - laid.size.width) / 2f, y + (rowHeight - gap - laid.size.height) / 2f))
                    }
                }
                rows.forEachIndexed { row, letters ->
                    val wide = if (row == 2) keyWidth * 1.5f + gap / 2f else 0f
                    val start = (size.width - letters.length * keyWidth - (letters.length - 1) * gap - if (row == 2) 2 * (wide + gap) else 0f) / 2f
                    if (row == 2) keyAt(start, row, wide, modKey, null)
                    val lettersStart = start + if (row == 2) wide + gap else 0f
                    letters.forEachIndexed { i, c -> keyAt(lettersStart + i * (keyWidth + gap), row, keyWidth, key, c.toString()) }
                    if (row == 2) keyAt(lettersStart + letters.length * (keyWidth + gap), row, wide, modKey, null)
                }
                keyAt(gap, 3, keyWidth * 1.5f, modKey, null)
                keyAt(gap * 2 + keyWidth * 1.5f, 3, keyWidth, modKey, ",")
                val space = size.width - 2 * (gap * 2 + keyWidth * 2.5f) - gap * 2
                keyAt(gap * 3 + keyWidth * 2.5f, 3, space, key, null)
                keyAt(size.width - gap * 2 - keyWidth * 2.5f, 3, keyWidth, modKey, ".")
                keyAt(size.width - gap - keyWidth * 1.5f, 3, keyWidth * 1.5f, if (dark) Color(0xFF5B8DEF) else Color(0xFF1A73E8), null)
            }
        }
    }
}

/** A hardware keyboard's suggestion strip, all an on-screen keyboard shows over one. */
internal val SuggestionStrip = 48.dp
