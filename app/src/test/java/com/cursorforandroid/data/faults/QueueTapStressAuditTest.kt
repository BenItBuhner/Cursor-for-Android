package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Audit probe (prints `AUDIT stress …`, asserts nothing): the queue card's glyphs against a server 300–900 ms away —
 * how long a waiting card shows trash/edit/up while a retry is out and the repository ignores them, and what a trash
 * tap in that window leads to; and an edit of a failed head with text in the composer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QueueTapStressAuditTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig

    @Before
    fun setUp() {
        server = FaultServer().start()
        rig = FaultRig(server.baseUrl, folder.newFolder("rig"))
        server.addIdleAgent("bc-1", "Agent", "run-1")
    }

    @After
    fun tearDown() {
        rig.close()
        server.close()
    }

    private suspend fun open() {
        rig.agents.refresh()
        rig.conversations.attach("bc-1")
        rig.awaitUntil { rig.conversations.state("bc-1").value.let { !it.isLoading && it.activeRunId == "run-1" } }
    }

    @Test
    fun `busy server - glyphs dead while each retry is out`() = runBlocking<Unit> {
        open()
        server.busy = true
        val item = rig.followUps.enqueue("bc-1", "Now the tests")
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.singleOrNull()?.isHeld == true }
        var held = 0
        var dead = 0
        val windows = mutableListOf<Long>()
        var openedAt = -1L
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 12_000) {
            val q = rig.followUps.state("bc-1").value.queue.singleOrNull() ?: break
            val now = System.currentTimeMillis()
            if (q.isHeld) held++
            val glyphsDead = q.isHeld && q.isSending
            if (glyphsDead) {
                dead++
                if (openedAt < 0) openedAt = now
            } else if (openedAt >= 0) {
                windows += now - openedAt
                openedAt = -1
            }
            delay(10)
        }
        println("AUDIT stress busy: samples held=$held glyphsShownButIgnored=$dead (${100 * dead / held.coerceAtLeast(1)}% of the held time); windows ms=$windows attempts=${server.requests(Route.CreateRun).size}")
        // A trash tap in the next window.
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.single().let { it.isHeld && it.isSending } }
        rig.followUps.remove("bc-1", item.id)
        val stayed = rig.followUps.state("bc-1").value.queue.map { it.text }
        server.busy = false
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.isEmpty() }
        println("AUDIT stress busy: trash tapped mid-retry -> queue right after=$stayed; server filed=${server.sent.map { it.first }}")
    }

    @Test
    fun `refused head edited with a draft - the draft never leaves`() = runBlocking<Unit> {
        open()
        server.script(Route.CreateRun, Fault.Status(503, "unavailable", "Cursor is briefly unavailable. Try again."))
        val item = rig.followUps.enqueue("bc-1", "Now the tests")
        rig.awaitUntil { rig.followUps.state("bc-1").value.queue.singleOrNull()?.error != null }
        rig.followUps.setDraftText("bc-1", "Also bump the version")
        rig.followUps.takeForEdit("bc-1", item.id)
        val t0 = System.currentTimeMillis()
        rig.watch(8_000) { }
        val q = rig.followUps.state("bc-1").value.queue
        println("AUDIT stress refused-edit: after ${System.currentTimeMillis() - t0} ms on an idle agent: queue=${q.map { "${it.text}(error=${it.error}, sending=${it.isSending})" }} createRun requests=${server.requests(Route.CreateRun).size} filed=${server.sent.map { it.first }}")
    }
}
