package com.cursorforandroid.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.faults.FaultRig
import com.cursorforandroid.data.faults.FaultServer
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * SCALE-7: opening interest in many running cloud subagents at once must not open an SSE per child. Compares an
 * uncapped direct [LiveRunHub.snapshots] fan-out to the capped [SubagentStreamGate] + [SubagentActivity] path.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SubagentStreamCapBenchmarkTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig

    @After
    fun tearDown() {
        if (::rig.isInitialized) rig.close()
        if (::server.isInitialized) server.close()
    }

    @Test
    fun `many running subagents cap concurrent hub streams`() = runBlocking {
        val children = 120
        server = FaultServer(rttMillis = 40L..120L, http2 = true).start().also {
            it.liveRunStreams = true
            it.holdRunningStreamsMs = 60_000L
        }
        val coordinator = "bc-coord"
        val now = 1_800_000_000_000L
        fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
        server.composers[coordinator] = FaultServer.Composer(coordinator, "Coordinator", activityMs = now, project = true, running = true)
        repeat(children) { i ->
            val id = "bc-child-$i"
            server.agents[id] = AgentDto(id = id, name = "Worker $i", status = "ACTIVE", createdAt = iso(now), updatedAt = iso(now), latestRunId = "run-$id", url = "https://cursor.com/agents/$id")
            server.v0[id] = V0AgentDto(id = id, name = "Worker $i", status = "RUNNING")
            server.composers[id] = FaultServer.Composer(id, "Worker $i", activityMs = now - i, manager = coordinator, running = true)
            server.startTurnElsewhere(id, "Task $i", "run-$id")
        }
        server.workers[coordinator] = (0 until children).map { "bc-child-$it" to "MANAGER_SPAWN_KIND_CREATED" }
        rig = FaultRig(server.baseUrl, folder.newFolder("disk"), readTimeoutMs = 8_000L, extended = true, engine = TranscriptEngine.BETA, http2 = true).also { it.now = now }
        rig.agents.refresh()

        var uncappedPeak = 0
        val uncapped = (0 until children).map { i ->
            val id = "bc-child-$i"
            launch {
                rig.hub.snapshots(id, "run-$id").collect { }
            }
        }
        repeat(20) {
            delay(100)
            uncappedPeak = maxOf(uncappedPeak, hubStreaming(rig.hub.stats()))
        }
        uncapped.forEach { it.cancel() }
        delay(400)

        val gate = SubagentStreamGate()
        val activity = SubagentActivity(rig.agents, rig.hub, MutableStateFlow(LiveModelCatalog.models), gate)
        var cappedPeak = 0
        val capped = (0 until children).map { i ->
            val id = "bc-child-$i"
            gate.track(id)
            launch { activity.of(id).collect { } }
        }
        repeat(20) {
            delay(100)
            cappedPeak = maxOf(cappedPeak, hubStreaming(rig.hub.stats()))
        }
        capped.forEach { it.cancel() }

        val perAgentReads = server.seen.count { "/agents/" in it.path }
        println(
            "SCALE subagent_stream_cap children=$children uncappedStreamingPeak=$uncappedPeak cappedStreamingPeak=$cappedPeak " +
                "grants=${gate.grantedCount()} perAgentReads=$perAgentReads",
        )
        assertThat(cappedPeak).isAtMost(SubagentStreamGate.MAX_STREAMS)
        assertThat(gate.grantedCount()).isAtMost(SubagentStreamGate.MAX_STREAMS)
        assertThat(uncappedPeak).isGreaterThan(SubagentStreamGate.MAX_STREAMS)
    }

    private fun hubStreaming(stats: String): Int =
        Regex("streaming=(\\d+)").find(stats)?.groupValues?.get(1)?.toInt() ?: 0
}
