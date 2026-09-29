package com.cursorforandroid.data.repo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SubagentStreamGateTest {

    @Test
    fun `only the most recent interests receive a grant`() = runTest {
        val gate = SubagentStreamGate(maxStreams = 2)
        repeat(4) { gate.track("bc-$it") }
        val granted = (0 until 4).count { gate.watched("bc-$it").value }
        assertThat(granted).isEqualTo(2)
        assertThat(gate.watched("bc-3").value).isTrue()
        assertThat(gate.watched("bc-2").value).isTrue()
        assertThat(gate.watched("bc-1").value).isFalse()
        gate.track("bc-0")
        assertThat(gate.watched("bc-0").value).isTrue()
        assertThat(gate.watched("bc-1").value).isFalse()
    }

    @Test
    fun `untrack frees a slot for the next waiter`() = runTest {
        val gate = SubagentStreamGate(maxStreams = 1)
        gate.track("bc-a")
        gate.track("bc-b")
        assertThat(gate.watched("bc-a").value).isFalse()
        assertThat(gate.watched("bc-b").value).isTrue()
        gate.untrack("bc-b")
        assertThat(gate.watched("bc-a").value).isTrue()
    }
}
