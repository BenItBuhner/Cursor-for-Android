package com.cursorforandroid.ui.components

import androidx.compose.ui.unit.IntRect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The caption bar's geometry: when the window counts as having one the app draws into, the occupied caps in the
 * window's own pixels, and how a row standing in the bar is held in from each end — including a one-bar report
 * that must not open a hole the width of the bar.
 */
class CaptionBarTest {

    private val appMenu = IntRect(0, 0, 160, 40)
    private val windowControls = IntRect(1000, 0, 1200, 40)
    private val bar = CaptionBar(40, listOf(appMenu, windowControls), 1200)

    @Test
    fun `discrete buttons occupy the real cluster edges`() {
        assertThat(bar.occupancy()).isEqualTo(CaptionBar.Occupancy(leftExclusive = 160, rightExclusive = 1000))
    }

    @Test
    fun `a row across the whole window clears the app menu at its start and the window controls at its end`() {
        assertThat(bar.clearance(0f, 1200f)).isEqualTo(CaptionBar.Clearance(left = 160f, right = 200f))
    }

    @Test
    fun `a rail's header at the start clears only the app menu`() {
        assertThat(bar.clearance(0f, 300f)).isEqualTo(CaptionBar.Clearance(left = 160f, right = 0f))
    }

    @Test
    fun `a chat header beside the rail clears only the window controls`() {
        assertThat(bar.clearance(300f, 1200f)).isEqualTo(CaptionBar.Clearance(left = 0f, right = 200f))
    }

    @Test
    fun `a chat header between the rail and a pinned panel is not padded by the window controls`() {
        assertThat(bar.clearance(300f, 900f)).isEqualTo(CaptionBar.Clearance(left = 0f, right = 0f))
    }

    @Test
    fun `a row the controls only partly reach is held in by the part they reach`() {
        assertThat(bar.clearance(100f, 1100f)).isEqualTo(CaptionBar.Clearance(left = 60f, right = 100f))
    }

    @Test
    fun `one bar across the top keeps only the end caps, not a hole the width of the bar`() {
        val oneBar = CaptionBar(40, listOf(IntRect(0, 0, 1200, 40)), 1200)
        assertThat(oneBar.occupancy()).isEqualTo(CaptionBar.Occupancy(leftExclusive = 80, rightExclusive = 1000))
        assertThat(oneBar.clearance(0f, 1200f)).isEqualTo(CaptionBar.Clearance(left = 80f, right = 200f))
        assertThat(oneBar.occupancy().rightExclusive - oneBar.occupancy().leftExclusive).isGreaterThan(800)
    }

    @Test
    fun `a wide right-hand bar reserves a cluster at its end, not the whole bar`() {
        val wideRight = CaptionBar(40, listOf(appMenu, IntRect(700, 0, 1200, 40)), 1200)
        assertThat(wideRight.occupancy()).isEqualTo(CaptionBar.Occupancy(leftExclusive = 160, rightExclusive = 1000))
    }

    @Test
    fun `occupancy follows a new window width for a single bar`() {
        val narrow = CaptionBar(40, listOf(IntRect(0, 0, 800, 40)), 800)
        val wide = CaptionBar(40, listOf(IntRect(0, 0, 1200, 40)), 1200)
        assertThat(narrow.occupancy()).isEqualTo(CaptionBar.Occupancy(leftExclusive = 80, rightExclusive = 600))
        assertThat(wide.occupancy()).isEqualTo(CaptionBar.Occupancy(leftExclusive = 80, rightExclusive = 1000))
    }

    @Test
    fun `a higher-density caption uses the same pixel occupancy against its own rects`() {
        val hdpi = CaptionBar(80, listOf(IntRect(0, 0, 320, 80), IntRect(1600, 0, 2000, 80)), 2000)
        assertThat(hdpi.occupancy()).isEqualTo(CaptionBar.Occupancy(leftExclusive = 320, rightExclusive = 1600))
    }

    @Test
    fun `no caption, a caption under the status bar, or no controls reported leave the headers where they were`() {
        assertThat(CaptionBar.of(captionTopPx = 0, statusTopPx = 0, controls = listOf(windowControls))).isNull()
        assertThat(CaptionBar.of(captionTopPx = 40, statusTopPx = 24, controls = listOf(windowControls))).isNull()
        assertThat(CaptionBar.of(captionTopPx = 40, statusTopPx = 0, controls = emptyList())).isNull()
        assertThat(CaptionBar.of(captionTopPx = 40, statusTopPx = 0, controls = listOf(windowControls), windowWidthPx = 1200))
            .isEqualTo(CaptionBar(40, listOf(windowControls), 1200))
    }
}
