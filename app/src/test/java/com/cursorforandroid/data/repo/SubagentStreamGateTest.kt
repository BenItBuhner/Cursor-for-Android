package com.cursorforandroid.data.repo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubagentStreamGateTest {

    @Test
    fun `the first tickets up to the cap are watched, and the rest wait in the order they came`() {
        val gate = SubagentStreamGate(maxStreams = 2)
        val (a, b, c, d) = List(4) { gate.acquire() }
        assertThat(listOf(a, b, c, d).map { it.watched.value }).containsExactly(true, true, false, false).inOrder()

        gate.release(b)
        assertThat(b.watched.value).isFalse()
        assertThat(listOf(a, c, d).map { it.watched.value }).containsExactly(true, true, false).inOrder()

        // A waiter that leaves gives up its turn; the next in line is served.
        gate.release(d)
        gate.release(a)
        assertThat(c.watched.value).isTrue()
        assertThat(gate.counts()).isEqualTo(1 to 0)
    }

    @Test
    fun `a ticket given back twice frees one place`() {
        val gate = SubagentStreamGate(maxStreams = 1)
        val a = gate.acquire()
        val b = gate.acquire()
        val c = gate.acquire()
        gate.release(a)
        gate.release(a)
        assertThat(b.watched.value).isTrue()
        assertThat(c.watched.value).isFalse()
        assertThat(gate.counts()).isEqualTo(1 to 1)
    }
}
