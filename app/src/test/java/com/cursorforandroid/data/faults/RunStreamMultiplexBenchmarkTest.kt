package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A coordinator's [WORKERS] cloud subagents all working at once, each run's stream held open by an HTTP/2 server as
 * the API holds it and writing about a hundred events a second, every one followed through the real [SseRunStreamer]
 * [com.cursorforandroid.data.api.SseRunStreamer] and [LiveRunHub][com.cursorforandroid.data.repo.LiveRunHub]. Each
 * stream is one HTTP/2 stream on the client's one connection to the host; a stream holds a thread for as long as it
 * reads (`execute()` blocks on the socket), so every run followed must have a thread of its own or its stream waits,
 * unread, until another ends. `SCALE run-streams` reports the streams the server held, the client's connections and
 * the events each run's subscriber was handed per second.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RunStreamMultiplexBenchmarkTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig
    private val now = 1_800_000_000_000L

    @After
    fun tearDown() {
        if (::rig.isInitialized) rig.close()
        if (::server.isInitialized) server.close()
    }

    @Test
    fun `every working subagent's stream flows at once over one HTTP-2 connection`() {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.clock = { now }
        server.liveRunStreams = true
        server.liveRunBeatMs = BEAT_MS
        server.liveRunGenerator = { _, tick -> listOf("assistant" to """{"text":"t$tick "}""") }
        val ids = (0 until WORKERS).map { "bc-stream-worker-%03d".format(it) }
        ids.forEachIndexed { i, id ->
            val at = Instant.ofEpochMilli(now - i).toString()
            server.agents[id] = AgentDto(id = id, name = "Worker $i", status = "ACTIVE", createdAt = at, updatedAt = at, latestRunId = "run-$id")
            server.v0[id] = V0AgentDto(id = id, name = "Worker $i", status = "RUNNING")
            server.startTurnElsewhere(id, "Scan market $i.", "run-$id")
        }
        rig = FaultRig(server.baseUrl, folder.newFolder("disk"), readTimeoutMs = 10_000L, http2 = true).also { it.now = now }
        runBlocking { rig.agents.refresh() }

        val events = ConcurrentHashMap<String, Int>()
        val jobs: List<Job> = ids.map { id -> rig.scope.launch { rig.hub.snapshots(id, "run-$id").collect { events[id] = it.eventCount } } }
        Thread.sleep(SETTLE_MS)

        val before = ids.associateWith { events[it] ?: 0 }
        var peakOpen = 0
        var peakConnections = 0
        val sampler = Executors.newSingleThreadScheduledExecutor()
        sampler.scheduleAtFixedRate({
            peakOpen = maxOf(peakOpen, server.liveRunOpen.get())
            peakConnections = maxOf(peakConnections, rig.client.connectionPool.connectionCount())
        }, 0, 100, TimeUnit.MILLISECONDS)
        Thread.sleep(PHASE_MS)
        sampler.shutdown()
        sampler.awaitTermination(1, TimeUnit.SECONDS)
        val rates = ids.map { ((events[it] ?: 0) - before.getValue(it)) * 1_000.0 / PHASE_MS }.sorted()
        val stalled = rates.count { it < 1.0 }
        val hub = rig.hub.stats()
        jobs.forEach { it.cancel() }

        val line = "SCALE run-streams workers=$WORKERS serverStreamsPeak=$peakOpen connectionsPeak=$peakConnections " +
            "serverConnections=${server.connections.get()} stalled=$stalled " +
            "eventsPerSecPerRun(min=${"%.1f".format(rates.first())} p50=${"%.1f".format(rates[rates.size / 2])} max=${"%.1f".format(rates.last())}) $hub"
        println(line)
        assertWithMessage(line).that(stalled).isEqualTo(0)
        assertWithMessage(line).that(peakOpen).isEqualTo(WORKERS)
        assertWithMessage(line).that(rates.first()).isAtLeast(MIN_EVENTS_PER_SEC)
        assertWithMessage(line).that(peakConnections).isAtMost(1)
    }

    private companion object {
        const val WORKERS = 150
        /** A held stream writes an event, then waits this long: about a hundred a second, never more. */
        const val BEAT_MS = 10L
        const val SETTLE_MS = 4_000L
        const val PHASE_MS = 6_000L
        const val MIN_EVENTS_PER_SEC = 40.0
    }
}
