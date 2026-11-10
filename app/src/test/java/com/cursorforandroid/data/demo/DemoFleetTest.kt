package com.cursorforandroid.data.demo

import com.cursorforandroid.util.AppClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/** The demo account's computers: one busy from the start, and a free one that comes online a little into the session. */
class DemoFleetTest {

    private val clock = AppClock.nowMillis

    @After
    fun restoreClock() {
        AppClock.nowMillis = clock
    }

    @Test
    fun `the second computer is listed, free, once the session is a little old`() = runBlocking {
        var now = 1_700_000_000_000L
        AppClock.nowMillis = { now }
        val api = DemoCursorApi(DemoStore())

        assertThat(api.listWorkers(null, "personal", 50, null).workers.map { it.name }).containsExactly("bennett")

        now += SECOND_MACHINE_WAKES_MS - 1
        assertThat(api.listWorkers(null, "personal", 50, null).workers.map { it.name }).containsExactly("bennett")

        now += 1
        val listed = api.listWorkers(null, "personal", 50, null).workers
        assertThat(listed.map { it.name }).containsExactly("bennett", SECOND_MACHINE).inOrder()
        assertThat(listed.last().isInUse).isFalse()
        assertThat(api.listWorkers(null, "team_pool", 50, null).workers).isEmpty()
    }
}
