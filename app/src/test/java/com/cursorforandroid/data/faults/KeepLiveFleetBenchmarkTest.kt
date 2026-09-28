package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.Meter.Companion.mb
import com.cursorforandroid.data.repo.LiveSync
import com.cursorforandroid.data.repo.RefreshDepth
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TranscriptEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keep chats live at the scale of Bennett's peak: hundreds of agents working at once, each run stream held open by
 * the server as the API holds one, every agent writing a step each second and moving up the list as it does, the list
 * refreshed every few seconds. What the mode may cost is bounded by what it holds, never by the account: one HTTP/2
 * connection, a fixed number of run streams, threads and heap flat, a request rate that does not grow with the
 * number of agents working. And what it shows must be right: every held turn's story whole (each step once, in
 * order), no chat left "running" after its turn ended, nothing held once the app has been in the background a while.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class KeepLiveFleetBenchmarkTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private val rigs = ArrayList<FaultRig>()
    private val now = 1_800_000_000_000L
    private val uncaught = CopyOnWriteArrayList<Pair<String, Throwable>>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @After
    fun tearDown() {
        rigs.forEach { it.close() }
        if (::server.isInitialized) server.close()
        previousHandler?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    /** Run id per agent, and the steps each run's log has been written so far. */
    private val runOf = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val stepsOf = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun account(total: Int, projects: Int): List<String> {
        val coordinators = (0 until projects).map { "bc-project-$it" }
        val all = coordinators + (0 until total - projects).map { "bc-fleet-$it" }
        all.forEachIndexed { i, id ->
            val at = now - 100_000L - 1_000L * i
            val done = "run-done-$id"
            server.runs[done] = RunDto(id = done, agentId = id, status = "FINISHED", createdAt = iso(at - 60_000L), updatedAt = iso(at), durationMs = 60_000L, result = "Done.")
            server.logs[done] = listOf("assistant" to """{"text":"Done."}""", "result" to """{"runId":"$done","status":"FINISHED","text":"Done.","durationMs":60000}""")
            server.agents[id] = AgentDto(id = id, name = id, status = "IDLE", createdAt = iso(at - 60_000L), updatedAt = iso(at), latestRunId = done, url = "https://cursor.com/agents/$id")
            server.v0[id] = V0AgentDto(id = id, name = id, status = "FINISHED")
            server.transcripts[id] = listOf(V0ConversationMessageDto("$done-u", "user_message", "Start."), V0ConversationMessageDto("$done-a", "assistant_message", "Done."))
            server.composers[id] = FaultServer.Composer(id, id, activityMs = at, project = id in coordinators)
        }
        coordinators.forEachIndexed { p, id ->
            val workers = all.drop(projects).filterIndexed { i, _ -> i % projects == p }.take(12)
            server.workers[id] = workers.map { it to "MANAGER_SPAWN_KIND_CREATED" }
            workers.forEach { w -> server.composers[w] = server.composers.getValue(w).copy(manager = id) }
        }
        all.forEachIndexed { n, id -> startTurn(id, "run-live-$n") }
        return all
    }

    private fun startTurn(id: String, runId: String) {
        server.startTurnElsewhere(id, "Keep going ($runId).", runId)
        runOf[id] = runId
        stepsOf[id] = 0
    }

    /** One step of [id]'s turn: a marker the story check counts, padded to about a real step's size. */
    private fun step(id: String) {
        val runId = runOf[id] ?: return
        val n = (stepsOf[id] ?: 0) + 1
        stepsOf[id] = n
        server.appendRunEvents(runId, listOf("assistant" to """{"text":"<$runId:$n> ${PAD}"}"""))
    }

    private fun rig(): FaultRig =
        FaultRig(server.baseUrl, folder.newFolder("disk"), readTimeoutMs = 10_000L, extended = true, engine = TranscriptEngine.BETA, http2 = true).also {
            it.now = now
            rigs += it
        }

    private class Sample {
        var connections = 0
        var serverStreams = 0
        var serverStreamSum = 0L
        var samples = 0
        var hubStreaming = 0
        var appThreads = 0
        var allThreads = 0
        var chats = 0
        var held = 0
        override fun toString() =
            "connections=$connections serverRunStreams(peak=$serverStreams mean=${if (samples == 0) 0 else serverStreamSum / samples}) " +
                "hubStreaming=$hubStreaming appThreads=$appThreads allThreads=$allThreads chats=$chats held=$held"
    }

    private fun stat(stats: String, name: String): Int = Regex("\\b$name=(\\d+)").find(stats)!!.groupValues[1].toInt()

    private fun sample(rig: FaultRig, into: Sample) {
        into.connections = maxOf(into.connections, rig.client.connectionPool.connectionCount() + rig.accountClient.connectionPool.connectionCount())
        val open = server.liveRunOpen.get()
        into.serverStreams = maxOf(into.serverStreams, open)
        into.serverStreamSum += open
        into.samples++
        into.hubStreaming = maxOf(into.hubStreaming, stat(rig.hub.stats(), "streaming"))
        val threads = Thread.getAllStackTraces().keys
        into.appThreads = maxOf(into.appThreads, threads.count { Meter.isApp(it.name) })
        into.allThreads = maxOf(into.allThreads, threads.size)
        val conv = rig.conversations.stats()
        into.chats = maxOf(into.chats, stat(conv, "chats"))
        into.held = maxOf(into.held, stat(conv, "held"))
    }

    private data class Result(
        val total: Int,
        val foreground: Sample,
        val callsPerSecond: Double,
        val streamRequestsPerSecond: Double,
        val loadRequestsPerSecond: Double,
        val bytesInPerSecond: Long,
        val peakRetained: Long,
        val allocated: Long,
        val backgroundCalls: Int,
        val backgroundOpen: Int,
        val staleRunning: List<String>,
        val brokenStories: List<String>,
        val holdChanges: Int,
        val settleMs: Long,
    )

    private fun scenario(total: Int, seconds: Int): Result = runBlocking {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> uncaught += t.name to e }
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.clock = { now }
        server.liveRunStreams = true
        server.pageSize = 100
        val ids = account(total, projects = 6)
        val rig = rig()
        rig.agents.refresh()
        repeat(total / 100 + 1) { runCatching { rig.agents.loadMore() } }

        val foreground = MutableStateFlow(true)
        val sync = LiveSync(
            target = object : com.cursorforandroid.data.repo.LiveSync.Target {
                override fun hold(agentId: String) = rig.conversations.hold(agentId)
                override fun release(agentId: String) = rig.conversations.release(agentId)
                override suspend fun settled(agentId: String) = rig.conversations.settled(agentId)
            },
            scope = rig.scope,
            settleTimeoutMs = 10_000L,
            backgroundGraceMs = 1_000L,
        )
        val holdChanges = java.util.concurrent.atomic.AtomicInteger()
        var lastHeld = emptySet<String>()
        val heldWatch = rig.scope.launch {
            sync.heldIds.collect { held ->
                holdChanges.addAndGet((held - lastHeld).size)
                lastHeld = held
            }
        }
        sync.start(rig.agents.state.map { it.agents }.distinctUntilChanged(), MutableStateFlow(true), foreground)
        rig.awaitUntil(60_000) { sync.heldIds.value.size == LiveSync.MAX_HELD }
        delay(2_000)
        holdChanges.set(0)

        val fg = Sample()
        val meter = Meter(rig)
        val seenBefore = server.seen.size
        val streamsBefore = server.requests(FaultServer.Route.Stream).size
        val loadsBefore = server.seen.count { it.route == FaultServer.Route.GetAgent || it.route == FaultServer.Route.ListRuns || it.route == FaultServer.Route.RecordState || it.route == FaultServer.Route.Record }
        val random = java.util.Random(7)
        val startedAt = System.nanoTime()
        var tick = 0
        while (System.nanoTime() - startedAt < seconds * 1_000_000_000L) {
            tick++
            // Every agent works: a step each, and some move up the list as the account notices their activity.
            ids.forEach { step(it) }
            repeat(total / 10) {
                val id = ids[random.nextInt(ids.size)]
                val agent = server.agents.getValue(id)
                val at = now + 60_000L + tick * 1_000L + random.nextInt(1_000)
                server.agents[id] = agent.copy(updatedAt = iso(at))
                server.composers[id]?.let { server.composers[id] = it.copy(activityMs = at) }
            }
            // The sidebar's refresh, every few seconds; every other one reads every page loaded, as the monitor's does.
            if (tick % 3 == 0) runCatching { rig.agents.refresh(silent = true, depth = if (tick % 6 == 0) RefreshDepth.Full else RefreshDepth.Quick) }
            if (tick % 10 == 0) meter.checkpoint()
            repeat(4) { sample(rig, fg); delay(250) }
        }
        val elapsed = (System.nanoTime() - startedAt) / 1e9
        meter.checkpoint()
        val calls = meter.calls
        val bytesIn = meter.bytesIn
        val allocated = meter.allocatedBytes()
        val peakRetained = meter.peakRetained
        meter.close()
        val routes = server.seen.drop(seenBefore).groupingBy { it.route }.eachCount().entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }
        val streamRequests = server.requests(FaultServer.Route.Stream).size - streamsBefore
        val loads = server.seen.count { it.route == FaultServer.Route.GetAgent || it.route == FaultServer.Route.ListRuns || it.route == FaultServer.Route.RecordState || it.route == FaultServer.Route.Record } - loadsBefore

        val windowHoldChanges = holdChanges.get()
        // Every turn ends. The held chats' stories must be whole, and nothing may still say running once the list agrees.
        val heldAtEnd = sync.heldIds.value.toList()
        ids.forEach { step(it) }
        delay(1_500)
        val lastRuns = heldAtEnd.associateWith { runOf.getValue(it) }
        val lastSteps = heldAtEnd.associateWith { stepsOf.getValue(it) }
        ids.forEach { server.endTurn(it, durationMs = 5_000L) }
        val endedAt = System.nanoTime()
        runCatching { rig.agents.refresh() }
        val staleRunning = ArrayList<String>()
        runCatching {
            rig.awaitUntil(30_000) {
                heldAtEnd.all { id -> rig.conversations.state(id).value.let { !it.isStreaming && it.runStatus?.isActive != true } } &&
                    rig.agents.state.value.agents.none { it.isRunning }
            }
        }
        val settleMs = (System.nanoTime() - endedAt) / 1_000_000
        heldAtEnd.forEach { id ->
            val st = rig.conversations.state(id).value
            if (st.isStreaming || st.runStatus?.isActive == true) staleRunning += "$id chat ${st.runStatus} streaming=${st.isStreaming}"
        }
        rig.agents.state.value.agents.filter { it.isRunning }.forEach { staleRunning += "${it.id} row" }
        val broken = ArrayList<String>()
        heldAtEnd.forEach { id ->
            val runId = lastRuns.getValue(id)
            val text = rig.hub.current(id, runId)?.items?.filterIsInstance<AssistantMessage>()?.joinToString("") { it.markdown }
            if (text == null) { broken += "$id: no snapshot"; return@forEach }
            val markers = Regex("<$runId:(\\d+)>").findAll(text).map { it.groupValues[1].toInt() }.toList()
            if (markers != (1..lastSteps.getValue(id)).toList()) broken += "$id: ${markers.size} markers, expected ${lastSteps.getValue(id)} (first=${markers.firstOrNull()} last=${markers.lastOrNull()} dupes=${markers.size - markers.toSet().size})"
        }

        // New turns, then the app goes to the background: after its grace nothing is held, streamed or asked for.
        ids.forEachIndexed { n, id -> startTurn(id, "run-late-$n") }
        runCatching { rig.agents.refresh() }
        delay(2_000)
        foreground.value = false
        delay(3_000)
        val callsBefore = rig.calls.get()
        delay(5_000)
        val backgroundCalls = rig.calls.get() - callsBefore
        val backgroundOpen = server.liveRunOpen.get()
        heldWatch.cancel()

        assertThat(uncaught.map { (thread, e) -> "$thread: ${e.stackTraceToString().take(1_500)}" }).isEmpty()
        Result(
            total = total,
            foreground = fg,
            callsPerSecond = calls / elapsed,
            streamRequestsPerSecond = streamRequests / elapsed,
            loadRequestsPerSecond = loads / elapsed,
            bytesInPerSecond = (bytesIn / elapsed).toLong(),
            peakRetained = peakRetained,
            allocated = allocated,
            backgroundCalls = backgroundCalls,
            backgroundOpen = backgroundOpen,
            staleRunning = staleRunning,
            brokenStories = broken,
            holdChanges = windowHoldChanges,
            settleMs = settleMs,
        ).also {
            println(
                "KEEPLIVE total=$total ${seconds}s: ${it.foreground} calls/s=${"%.1f".format(it.callsPerSecond)} " +
                    "streamReq/s=${"%.2f".format(it.streamRequestsPerSecond)} loadReq/s=${"%.2f".format(it.loadRequestsPerSecond)} " +
                    "in/s=${it.bytesInPerSecond / 1024}KB peakRetained=${it.peakRetained.mb} allocated=${it.allocated.mb} holdChanges=${it.holdChanges} settleAfterEnd=${it.settleMs}ms " +
                    "background: calls=${it.backgroundCalls} openStreams=${it.backgroundOpen} stale=${it.staleRunning.size} broken=${it.brokenStories.size}",
            )
            println("KEEPLIVE   routes over ${seconds}s: $routes")
            println("KEEPLIVE   conversations: ${rig.conversations.stats()} · hub: ${rig.hub.stats()}")
            if (it.staleRunning.isNotEmpty()) println("KEEPLIVE   stale: ${it.staleRunning.take(10)}")
            if (it.brokenStories.isNotEmpty()) println("KEEPLIVE   broken: ${it.brokenStories.take(10)}")
        }
    }

    private fun assertBudgets(r: Result) {
        assertThat(r.brokenStories.take(10)).isEmpty()
        assertThat(r.staleRunning.take(10)).isEmpty()
        // One HTTP/2 connection per client (the API's and the account's), whatever the fleet.
        assertThat(r.foreground.connections).isAtMost(2)
        assertThat(r.foreground.held).isAtMost(LiveSync.MAX_HELD)
        assertThat(r.foreground.serverStreams).isAtMost(LiveSync.MAX_HELD + 4)
        assertThat(r.foreground.chats).isAtMost(24 + LiveSync.MAX_HELD + 4)
        assertThat(r.foreground.appThreads).isAtMost(80)
        assertThat(r.peakRetained).isAtMost(24L shl 20)
        // No turn ends in the window, so the held set stands still however the running rows trade places: each swap
        // used to cost a stream torn down and a chat loaded (ListRuns, RecordState, conversation, agent), 11 a second.
        assertThat(r.holdChanges).isAtMost(3)
        assertThat(r.loadRequestsPerSecond).isAtMost(1.0)
        // The list's own refreshes and run checks are most of this; keeping chats live adds about one stream a second.
        assertThat(r.callsPerSecond).isAtMost(12.0)
        assertThat(r.streamRequestsPerSecond).isAtMost(3.0)
        // Every held chat hears its turn's end within a few seconds of the list, not at its next look.
        assertThat(r.settleMs).isAtMost(15_000L)
        assertThat(r.backgroundOpen).isEqualTo(0)
        assertThat(r.backgroundCalls).isAtMost(2)
    }

    @Test
    fun `two hundred agents working, keep chats live`() = assertBudgets(scenario(total = 200, seconds = 30))

    @Test
    fun `five hundred agents working, keep chats live`() = assertBudgets(scenario(total = 500, seconds = 30))

    private companion object {
        val PAD = "x".repeat(200)
    }
}
