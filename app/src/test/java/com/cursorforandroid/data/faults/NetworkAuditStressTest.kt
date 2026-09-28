package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.CursorApiFactory
import com.cursorforandroid.data.api.RetryInterceptor
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.SseRunStreamer
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.LiveSync
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.fixtures.BigProject
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Networking audit harness: counts what the app's real clients ask of a [FaultServer] at 300–900 ms RTT under the
 * weather a phone meets — a server that closes run streams on a timer, `429` bursts, a flap that kills every stream
 * at once, a screen that stops, chats kept live and then backgrounded. Each scenario prints a `NETAUDIT` line with
 * its numbers; the assertions pin the behaviour measured, so a fix that changes it shows up here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NetworkAuditStressTest {

    @get:Rule val folder = TemporaryFolder()

    private val closers = ArrayList<AutoCloseable>()
    private val now = 1_800_000_000_000L

    @After
    fun tearDown() {
        closers.asReversed().forEach { runCatching { it.close() } }
    }

    private fun server(http2: Boolean = false): FaultServer = FaultServer(rttMillis = 300L..900L, http2 = http2).start().also { closers += it }

    private fun baseClient(http2: Boolean = false): OkHttpClient = CursorApiFactory.okHttp { "fault-key" }.newBuilder()
        .dns(Dns.SYSTEM)
        .apply { if (http2) protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)) }
        .build()
        .also { client -> closers += AutoCloseable { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() } }

    private fun streamer(server: FaultServer, client: OkHttpClient, waits: MutableList<Long>? = null, scale: Long = 1): SseRunStreamer =
        SseRunStreamer(
            CursorApiFactory.sseClient(client),
            apiKeyProvider = { "fault-key" },
            urlFor = { agentId, runId -> "${server.baseUrl}v1/agents/$agentId/runs/$runId/stream" },
            waiter = { ms -> waits?.add(ms); delay(ms / scale) },
        )

    private fun runningAgent(server: FaultServer, id: String, runId: String) {
        server.addRunningAgent(id, id, runId)
        server.logs[runId] = listOf(
            "status" to """{"runId":"$runId","status":"RUNNING"}""",
            "thinking" to """{"text":"Reading the code first."}""",
            "assistant" to """{"text":"Working on it."}""",
        )
    }

    // ---- server closes the stream on a timer -----------------------------------------------------------------------

    @Test
    fun `server closing a healthy stream every cycle ends the pass after five connections`(): Unit = runBlocking {
        val server = server()
        runningAgent(server, "bc-a", "run-a")
        // Scaled 10x: the server holds each connection 2 s (a proxy's 20 s idle close) and closes without `done`.
        server.holdRunningStreamsMs = 2_000L
        val waits = CopyOnWriteArrayList<Long>()
        val started = System.nanoTime()
        val events = withTimeout(60_000) { streamer(server, baseClient(), waits, scale = 10).stream("bc-a", "run-a", null).toList() }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        val requests = server.requests(Route.Stream)
        val error = events.filterIsInstance<RunStreamEvent.Error>().single()
        val gaps = requests.zipWithNext { a, b -> b.atMillis - a.atMillis }
        println(
            "NETAUDIT sse-close-every-cycle: connections=${requests.size} backoffsMs=$waits error=${error.code} resumeIds=${requests.map { it.lastEventId }} " +
                "gapsBetweenOpensMs=$gaps took=${tookMs}ms (scaled 1:10); production at a 20 s close: open ${5 * 20}s, dark ${waits.sum() / 1000}s inside the streamer, " +
                "then GetRun + hub reconnectDelay",
        )
        // Every connection was healthy (it delivered, then kept alive until the server's close), yet the attempt
        // counter carried over: 1 + 2 + 4 + 8 s of dark air, then the pass is given up as `stream_unavailable`.
        assertThat(requests).hasSize(5)
        assertThat(waits).containsExactly(1_000L, 2_000L, 4_000L, 8_000L).inOrder()
        assertThat(error.code).isEqualTo("stream_unavailable")
        assertThat(requests.drop(1).map { it.lastEventId }.toSet()).containsExactly("run-a#3")
    }

    // ---- 429 on the stream ----------------------------------------------------------------------------------------

    @Test
    fun `stream 429 bursts honour Retry-After inside the streamer only`(): Unit = runBlocking {
        val server = server()
        runningAgent(server, "bc-a", "run-a")
        repeat(5) { server.script(Route.Stream, Fault.Status(429, "rate_limited", "Slow down.", retryAfter = "30")) }
        val waits = CopyOnWriteArrayList<Long>()
        val events = withTimeout(60_000) { streamer(server, baseClient(), waits, scale = 1_000).stream("bc-a", "run-a", null).toList() }
        val error = events.filterIsInstance<RunStreamEvent.Error>().single()
        println("NETAUDIT stream-429: requests=${server.requests(Route.Stream).size} waitsMs=$waits error=${error.code} (the hub then waits reconnectDelay(1)=1 s, not the 30 s asked)")
        assertThat(waits).containsExactly(30_000L, 30_000L, 30_000L, 30_000L).inOrder()
        assertThat(error.code).isEqualTo("stream_unavailable")
    }

    // ---- 429 on REST ----------------------------------------------------------------------------------------------

    @Test
    fun `REST 429 with Retry-After 30 is retried inside the window and streams skip the pause`(): Unit = runBlocking {
        val server = server()
        server.addIdleAgent("bc-a", "a", "run-a")
        runningAgent(server, "bc-b", "run-b")
        val virtual = AtomicLong(0)
        val sentAt = CopyOnWriteArrayList<Pair<String, Long>>()
        val client = baseClient().newBuilder()
            .apply { interceptors().removeAll { it is RetryInterceptor } }
            .addInterceptor(RetryInterceptor(now = { virtual.get() }, sleeper = { ms, _ -> virtual.addAndGet(ms) }, random = { 1.0 }))
            .addNetworkInterceptor { chain -> sentAt += chain.request().url.encodedPath to virtual.get(); chain.proceed(chain.request()) }
            .build()
        repeat(3) { server.script(Route.GetAgent, Fault.Status(429, "rate_limited", "Slow down.", retryAfter = "30")) }
        val code = client.newCall(Request.Builder().url("${server.baseUrl}v1/agents/bc-a").build()).execute().use { it.code }
        // Right after, with the host's pause still standing: a stream request goes out at once, a GET waits.
        val streamAt = virtual.get()
        client.newCall(Request.Builder().url("${server.baseUrl}v1/agents/bc-b/runs/run-b/stream").header("Accept", "text/event-stream").build()).execute().use { it.code }
        val streamSentAt = sentAt.last { it.first.endsWith("/stream") }.second
        val restAttempts = sentAt.filter { it.first == "/v1/agents/bc-a" }.map { it.second }
        println("NETAUDIT rest-429: finalCode=$code attemptsAtVirtualMs=$restAttempts (server asked for 30000 ms each) streamSentAt=$streamSentAt pauseStoodUntil≥${streamAt}")
        assertThat(code).isEqualTo(429)
        assertThat(restAttempts).containsExactly(0L, 10_000L, 20_000L).inOrder()
        assertThat(streamSentAt).isEqualTo(streamAt)
    }

    // ---- a flap kills every stream at once ------------------------------------------------------------------------

    @Test
    fun `a flap that kills ten streams at once reconnects them in lockstep`(): Unit = runBlocking {
        val server = server(http2 = true)
        server.liveRunStreams = true
        val ids = (0 until 10).map { "bc-f$it" }
        ids.forEach { runningAgent(server, it, "run-$it") }
        // OkHttp stops tracking a synchronous call once `execute()` has the headers, so `Dispatcher.cancelAll` cannot
        // reach a stream being read: the calls are kept here to kill them together, as a dead socket does.
        val live = CopyOnWriteArrayList<okhttp3.Call>()
        val client = baseClient(http2 = true).newBuilder().eventListener(object : okhttp3.EventListener() {
            override fun callStart(call: okhttp3.Call) { live += call }
        }).build()
        val sse = CursorApiFactory.sseClient(client)
        val streamer = SseRunStreamer(sse, apiKeyProvider = { "fault-key" }, urlFor = { a, r -> "${server.baseUrl}v1/agents/$a/runs/$r/stream" })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s -> closers += AutoCloseable { s.cancel() } }
        val errors = CopyOnWriteArrayList<String>()
        ids.forEach { id -> scope.launch { streamer.stream(id, "run-$id", null).collect { if (it is RunStreamEvent.Error) errors += "$id:${it.code}" } } }
        withTimeout(30_000) { while (server.liveRunOpen.get() < 10) delay(25) }
        val before = server.requests(Route.Stream).size
        // The network drops for 5 s: every stream on the shared connection dies in the same instant, and the host
        // answers 503 until the phone is back.
        server.outage(Route.Stream, Fault.Gateway(503))
        val flapAt = server.nowMillis()
        live.forEach { it.cancel() }
        live.clear()
        delay(5_000)
        server.clear(Route.Stream)
        withTimeout(30_000) { while (server.liveRunOpen.get() < 10) delay(25) }
        val recoveredAfter = server.nowMillis() - flapAt
        val after = server.requests(Route.Stream).drop(before).map { it.atMillis - flapAt }.sorted()
        val waves = ArrayList<MutableList<Long>>()
        after.forEach { t -> if (waves.isEmpty() || t - waves.last().last() > 400) waves += mutableListOf(t) else waves.last() += t }
        println(
            "NETAUDIT flap-10-streams: reconnectRequests=${after.size} waves=${waves.map { "t+${it.first()}ms×${it.size} spread=${it.last() - it.first()}ms" }} " +
                "recoveredAfter=${recoveredAfter}ms connections=${server.connections.get()} errors=$errors",
        )
        assertThat(waves.first()).hasSize(10)
        assertThat(waves.first().last() - waves.first().first()).isLessThan(200L)
    }

    // ---- the screen stops; the queue poll does not ----------------------------------------------------------------

    @Test
    fun `steering keeps polling the queue and goal after the chat screen stops`(): Unit = runBlocking {
        val server = server()
        val worker = BigProject.WORKERS.first()
        val turns = BigProject.workerTurns(now - 10 * BigProject.TURN_SPACING_MS, turns = 6)
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = worker, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = turn.narration.last())
            server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[worker] = AgentDto(id = worker, name = "Scanner", status = "IDLE", createdAt = BigProject.iso(turns.first().startedAt), updatedAt = BigProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$worker")
        server.v0[worker] = V0AgentDto(id = worker, name = "Scanner", status = "FINISHED")
        server.transcripts[worker] = BigProject.v0Transcript(turns)
        server.records[worker] = turns.flatMap { it.record }
        server.composers[worker] = FaultServer.Composer(worker, "Scanner", activityMs = newest.endedAt)
        // Scaled 10x: the queue is read every 1 s here, 10 s in the app.
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 8_000L, extended = true, engine = TranscriptEngine.BETA, queuePollMs = 1_000L).also { it.now = now; closers += it }
        rig.agents.refresh()
        rig.conversations.attach(worker)
        rig.steering.attach(worker)
        rig.awaitUntil(60_000) { rig.conversations.state(worker).value.let { !it.isLoading && it.runStatus?.isActive != true } }
        delay(2_000)
        // The screen stops (app backgrounded, or another chat pushed on top): ConversationScreen's LifecycleStartEffect pauses the chat; the ViewModel, and its steering attach, live on.
        rig.conversations.pause(worker)
        delay(1_500)
        val from = server.seen.size
        val bytesBefore = server.bytesByRoute.toMap()
        val windowMs = 10_000L
        delay(windowMs)
        val asked = server.seen.drop(from).groupingBy { it.route }.eachCount()
        val goalBytes = (server.bytesByRoute[Route.RecordState] ?: 0L) - (bytesBefore[Route.RecordState] ?: 0L)
        val queueBytes = (server.bytesByRoute[Route.QueueList] ?: 0L) - (bytesBefore[Route.QueueList] ?: 0L)
        val goals = asked[Route.RecordState] ?: 0
        val lists = asked[Route.QueueList] ?: 0
        println(
            "NETAUDIT steering-after-stop: over ${windowMs}ms paused (scaled 1:10): $asked goalBytes=$goalBytes (${if (goals == 0) 0 else goalBytes / goals}/read) queueBytes=$queueBytes " +
                "cycle≈${if (lists == 0) 0 else windowMs / lists}ms (1000 ms poll + round trips) → production (10 s poll + the same round trips) per paused chat per 30 min: " +
                "ListPendingFollowups≈${1_800_000 / (10_000 + windowMs / maxOf(lists, 1) - 1_000)} goalReads≈${1_800_000 / (10_000 + windowMs / maxOf(lists, 1) - 1_000) / 3}",
        )
        assertThat(lists).isAtLeast(4)
        assertThat(goals).isAtLeast(1)
        rig.steering.detach(worker)
        val afterDetach = server.seen.size
        delay(2_500)
        assertThat(server.seen.drop(afterDetach).count { it.route == Route.QueueList || it.route == Route.RecordState }).isEqualTo(0)
    }

    // ---- ten chats kept live, then the app goes to the background -------------------------------------------------

    @Test
    fun `ten chats kept live at phone RTT, then backgrounded`(): Unit = runBlocking {
        val server = server(http2 = true)
        server.clock = { now }
        server.liveRunStreams = true
        val ids = (0 until 10).map { "bc-k$it" }
        ids.forEachIndexed { i, id ->
            val at = now - 100_000L - 1_000L * i
            val done = "run-done-$id"
            server.runs[done] = RunDto(id = done, agentId = id, status = "FINISHED", createdAt = iso(at - 60_000L), updatedAt = iso(at), durationMs = 60_000L, result = "Done.")
            server.logs[done] = listOf("assistant" to """{"text":"Done."}""", "result" to """{"runId":"$done","status":"FINISHED","text":"Done.","durationMs":60000}""")
            server.agents[id] = AgentDto(id = id, name = id, status = "IDLE", createdAt = iso(at - 60_000L), updatedAt = iso(at), latestRunId = done, url = "https://cursor.com/agents/$id")
            server.v0[id] = V0AgentDto(id = id, name = id, status = "FINISHED")
            server.transcripts[id] = listOf(V0ConversationMessageDto("$done-u", "user_message", "Start."), V0ConversationMessageDto("$done-a", "assistant_message", "Done."))
            server.composers[id] = FaultServer.Composer(id, id, activityMs = at)
            server.startTurnElsewhere(id, "Keep going ($id).", "run-live-$i")
        }
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 10_000L, extended = true, engine = TranscriptEngine.BETA, http2 = true).also { it.now = now; closers += it }
        rig.agents.refresh()
        val foreground = MutableStateFlow(true)
        val sync = LiveSync(
            target = object : LiveSync.Target {
                override fun hold(agentId: String) = rig.conversations.hold(agentId)
                override fun release(agentId: String) = rig.conversations.release(agentId)
                override suspend fun settled(agentId: String) = rig.conversations.settled(agentId)
            },
            scope = rig.scope,
            settleTimeoutMs = 10_000L,
            backgroundGraceMs = 1_000L,
        )
        sync.start(rig.agents.state.map { it.agents }.distinctUntilChanged(), MutableStateFlow(true), foreground)
        rig.awaitUntil(90_000) { sync.heldIds.value.size == 10 }
        delay(3_000)

        val seconds = 30
        val from = server.seen.size
        val callsBefore = rig.calls.get()
        val inBefore = rig.bytesIn.get()
        val openSamples = ArrayList<Int>()
        var peakConnections = 0
        var peakAppThreads = 0
        val startedAt = System.nanoTime()
        var tick = 0
        while (System.nanoTime() - startedAt < seconds * 1_000_000_000L) {
            tick++
            ids.forEachIndexed { i, id -> server.appendRunEvents("run-live-$i", listOf("assistant" to """{"text":"<$id:$tick> step"}""")) }
            if (tick % 10 == 0) runCatching { rig.agents.refresh(silent = true) }
            repeat(4) {
                openSamples += server.liveRunOpen.get()
                peakConnections = maxOf(peakConnections, rig.client.connectionPool.connectionCount() + rig.accountClient.connectionPool.connectionCount())
                peakAppThreads = maxOf(peakAppThreads, Thread.getAllStackTraces().keys.count { Meter.isApp(it.name) })
                delay(250)
            }
        }
        val elapsed = (System.nanoTime() - startedAt) / 1e9
        val fg = server.seen.drop(from).groupingBy { it.route }.eachCount()
        val fgCalls = rig.calls.get() - callsBefore
        val fgBytes = rig.bytesIn.get() - inBefore
        println(
            "NETAUDIT keeplive-10 foreground ${seconds}s: calls=$fgCalls (${"%.2f".format(fgCalls / elapsed)}/s) in=${fgBytes / 1024}KB routes=$fg " +
                "openRunStreams(mean=${openSamples.average().let { "%.1f".format(it) }} peak=${openSamples.max()}) connections(peak)=$peakConnections appThreads(peak)=$peakAppThreads hub=${rig.hub.stats()}",
        )
        val getRuns = server.seen.drop(from).filter { it.route == Route.GetRun }
        println("NETAUDIT keeplive-10 GetRun by run: ${getRuns.groupBy { it.path.substringAfter("/runs/") }.mapValues { (_, v) -> v.map { (it.atMillis - getRuns.first().atMillis) / 100 / 10.0 } }}")
        println("NETAUDIT keeplive-10 Stream by run: ${server.seen.drop(from).filter { it.route == Route.Stream }.groupBy { it.path.substringAfter("/runs/").substringBefore("/") }.mapValues { it.value.size }}")

        foreground.value = false
        delay(3_000)
        val bgFrom = server.seen.size
        val bgCallsBefore = rig.calls.get()
        delay(10_000)
        val bg = server.seen.drop(bgFrom).groupingBy { it.route }.eachCount()
        println("NETAUDIT keeplive-10 background (after 1 s grace) 10s: calls=${rig.calls.get() - bgCallsBefore} routes=$bg openRunStreams=${server.liveRunOpen.get()} held=${sync.heldIds.value.size} hub=${rig.hub.stats()}")
        assertThat(sync.heldIds.value).isEmpty()
        assertThat(server.liveRunOpen.get()).isEqualTo(0)
    }

    // ---- leaving a streaming chat ---------------------------------------------------------------------------------

    @Test
    fun `leaving a streaming chat frees its stream and threads`(): Unit = runBlocking {
        val server = server(http2 = true)
        server.liveRunStreams = true
        runningAgent(server, "bc-a", "run-a")
        val rig = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 10_000L, http2 = true).also { it.now = now; closers += it }
        rig.agents.refresh()
        val threadsBefore = Thread.getAllStackTraces().keys.count { Meter.isApp(it.name) }
        repeat(5) {
            rig.conversations.attach("bc-a")
            rig.awaitUntil(30_000) { server.liveRunOpen.get() >= 1 }
            rig.conversations.detach("bc-a")
            rig.awaitUntil(10_000) { server.liveRunOpen.get() == 0 }
        }
        delay(1_000)
        val threadsAfter = Thread.getAllStackTraces().keys.count { Meter.isApp(it.name) }
        println("NETAUDIT leave-chat x5: openRunStreams=${server.liveRunOpen.get()} streamRequests=${server.requests(Route.Stream).size} appThreads before=$threadsBefore after=$threadsAfter hub=${rig.hub.stats()} conversations=${rig.conversations.stats()}")
        assertThat(server.liveRunOpen.get()).isEqualTo(0)
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
}
