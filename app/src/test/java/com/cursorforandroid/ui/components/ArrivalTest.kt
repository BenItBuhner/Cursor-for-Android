package com.cursorforandroid.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArrivalTest {

    @Test
    fun `nothing has arrived at the start and everything has at the end`() {
        for (order in 0 until 6) {
            assertThat(Arrival.shareAt(0f, order, pieces = 6)).isEqualTo(0f)
            assertThat(Arrival.shareAt(1f, order, pieces = 6)).isEqualTo(1f)
        }
    }

    @Test
    fun `pieces arrive in order, each ahead of the one after it`() {
        for (step in 1 until 20) {
            val progress = step / 20f
            val shares = (0 until 6).map { Arrival.shareAt(progress, it, pieces = 6) }
            assertThat(shares).isInOrder(Comparator.reverseOrder<Float>())
        }
    }

    @Test
    fun `a piece never goes backwards`() {
        var last = 0f
        for (step in 0..100) {
            val now = Arrival.shareAt(step / 100f, order = 3, pieces = 6)
            assertThat(now).isAtLeast(last)
            last = now
        }
    }

    @Test
    fun `an order outside the screen is held to its last piece and a single piece takes the whole clock`() {
        assertThat(Arrival.shareAt(0.5f, order = 9, pieces = 6)).isEqualTo(Arrival.shareAt(0.5f, order = 5, pieces = 6))
        assertThat(Arrival.shareAt(1f, order = 0, pieces = 1)).isEqualTo(1f)
    }
}
