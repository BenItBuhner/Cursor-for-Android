package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.AccountFollowup
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunFooter
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.LongProject
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A message queued on a busy Project coordinator lands in the transcript ahead of the reply it gets, live and after a
 * reopen, on both engines (Bennett, 0.4.8: his queued message drawn under the coordinator's answer to it, "Starting…"
 * under it).
 *
 * The account names a run for a message queued behind the turn under way, dated the moment it was queued
 * ([FaultServer.datesQueuedRunsAtQueue]). Either it starts that run once the turn is over and the coordinator answers
 * there, or — Bennett's frame — the coordinator takes the message into the turn under way between its steps and
 * answers it in that turn, and the named run starts afterwards with nothing to say. The message was filed as the named
 * run's prompt the moment the turn ended, below the answer; it belongs to the turn that took it, above the answer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QueuedDeliveryOrderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val agentId = LongProject.AGENT_ID
    private val firstAt = 1_800_000_000_000L - TURNS * LongProject.TURN_SPACING_MS
    private val turns = LongProject.turns(firstAt, turns = TURNS)
    private val live get() = turns.last()
    private var recorder: Job? = null
    private val frames = CopyOnWriteArrayList<ConversationState>()

    @Before
    fun setUp() {
        server = FaultServer(rttMillis = 100L..250L).start()
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = agentId, status = turn.status, createdAt = LongProject.iso(turn.startedAt), updatedAt = LongProject.iso(turn.endedAt), durationMs = turn.durationMs, result = LongProject.result(turn))
            server.logs[turn.runId] = turn.log
        }
        server.agents[agentId] = AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "ACTIVE", createdAt = LongProject.iso(firstAt), updatedAt = LongProject.iso(live.startedAt), latestRunId = live.runId, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = LongProject.AGENT_NAME, status = "RUNNING")
        server.transcripts[agentId] = LongProject.v0Transcript(turns)
        server.records[agentId] = turns.flatMap { it.record }
        server.outage(Route.Stream, Fault.StreamCut(events = live.log.size), path = "/${live.runId}/")
        server.namesQueuedRuns = true
        server.datesQueuedRunsAtQueue = true
        server.queueLagMs = 2_000L
    }

    @After
    fun tearDown() {
        recorder?.cancel()
        rig?.let { it.steering.detach(agentId); it.close() }
        server.close()
    }

    private fun rig(engine: TranscriptEngine, root: java.io.File): FaultRig = FaultRig(server.baseUrl, root, readTimeoutMs = 8_000L, extended = true, engine = engine, queuePollMs = 1_000L).also {
        it.now = 1_800_000_000_000L
        rig = it
    }

    private val state: ConversationState get() = rig!!.conversations.state(agentId).value

    private fun describe(item: TimelineItem): String = when (item) {
        is UserMessage -> "user(${item.text.take(28)})"
        is AssistantMessage -> "assistant(${item.markdown.take(28)})"
        is ActivityGroup -> "group(${item.id.take(36)})"
        is RunFooter -> "footer(${item.runId}:${item.status})"
        else -> "${item::class.simpleName}(${item.id.take(36)})"
    }

    private fun dump(label: String, s: ConversationState = state) {
        println("== $label: run=${s.runStatus} active=${s.activeRunId} streaming=${s.isStreaming}")
        s.items.takeLast(13).forEach { println("   ${describe(it)}") }
    }

    private suspend fun FaultRig.awaitUntilOr(timeoutMs: Long, label: String, condition: suspend () -> Boolean) {
        try { awaitUntil(timeoutMs, condition) } catch (t: Throwable) { dump("TIMEOUT waiting for $label"); throw t }
    }

    private suspend fun FaultRig.open() {
        server.composers[agentId] = FaultServer.Composer(agentId, LongProject.AGENT_NAME, live.startedAt, running = true, project = true)
        agents.refresh()
        awaitUntilOr(30_000, "the row placed") { agents.agent(agentId)?.isProjectRoot == true }
        conversations.attach(agentId)
        steering.attach(agentId)
        recorder?.cancel()
        recorder = scope.launch { conversations.state(agentId).collect { frames += it } }
    }

    private suspend fun FaultRig.sendMidTurn(text: String): String {
        val followupId = AccountFollowup.newId()
        val staged = conversations.queueAhead(agentId, text, followupId = followupId)
        conversations.sendQueuedVia(agentId, staged, followupId) {
            steering.sendFollowup(agentId, AccountFollowup(text = text, followupId = followupId), refresh = false).getOrThrow()
        }.getOrThrow()
        steering.refreshQueue(agentId)
        return followupId
    }

    private fun replyLog(runId: String, finished: Boolean): List<Pair<String, String>> =
        listOf("status" to """{"runId":"$runId","status":"RUNNING"}""", "assistant" to """{"text":"$REPLY"}""") +
            (if (finished) listOf("result" to """{"runId":"$runId","status":"FINISHED","text":"","durationMs":20000}""") else emptyList())

    /** The record's copy of the turn the message started — or, [steer], went into — its prompt and the coordinator's answer. */
    private fun recordTurn(text: String, reply: String, at: Long, steer: Boolean = false) = listOf(
        buildJsonObject { put("humanMessage", buildJsonObject { put("text", text); put("agentMode", "AGENT_MODE_PROJECT"); put("createdAt", at.toString()); if (steer) put("turnSteer", true) }) },
        buildJsonObject { put("text", reply) },
        buildJsonObject { put("text", ""); put("isMessageDone", true) },
    )

    /** Where the message and its answer stand in [s]: every frame that shows both must show the message first. */
    private fun order(s: ConversationState): Pair<Int, Int> {
        val message = s.items.indexOfFirst { it is UserMessage && it.text == MESSAGE }
        val reply = s.items.indexOfFirst { it is AssistantMessage && it.markdown.contains(REPLY) }
        return message to reply
    }

    private fun assertInOrder(label: String, s: ConversationState = state) {
        val (message, reply) = order(s)
        assertWithMessage("$label: the message is not shown: ${s.items.takeLast(10).map(::describe)}").that(message).isAtLeast(0)
        assertWithMessage("$label: the answer is not shown: ${s.items.takeLast(10).map(::describe)}").that(reply).isAtLeast(0)
        assertWithMessage("$label: the answer is drawn above the message it answers: ${s.items.takeLast(10).map(::describe)}").that(message).isLessThan(reply)
        assertWithMessage("$label: the message is shown twice").that(s.items.count { it is UserMessage && it.text == MESSAGE }).isEqualTo(1)
        assertWithMessage("$label: the answer is drawn twice: ${s.items.takeLast(12).map(::describe)}").that(s.items.count { it is AssistantMessage && it.markdown.contains(REPLY) }).isEqualTo(1)
    }

    private fun assertEveryFrameInOrder(label: String) {
        frames.forEachIndexed { i, f ->
            val message = f.items.indexOfFirst { it is UserMessage && it.text == MESSAGE }
            val reply = f.items.indexOfFirst { it is AssistantMessage && it.markdown.contains(REPLY) }
            if (message >= 0 && reply >= 0 && message > reply) {
                dump("$label frame $i (out of order)", f)
                assertWithMessage("$label frame $i: the answer above the message it answers").that(message).isLessThan(reply)
            }
        }
    }

    @Test
    fun `Beta - a queued message lands above the answer it gets, live and after a reopen`() = runBlocking<Unit> {
        deliveredInOrder(TranscriptEngine.BETA)
    }

    @Test
    fun `Stable - a queued message lands above the answer it gets, live and after a reopen`() = runBlocking<Unit> {
        deliveredInOrder(TranscriptEngine.STABLE)
    }

    @Test
    fun `Beta - a message queued early in a long turn, its record late, still lands above its answer`() = runBlocking<Unit> {
        deliveredInOrder(TranscriptEngine.BETA, turnGoesOnMs = 240_000L, recordLags = true)
    }

    @Test
    fun `Stable - a message queued early in a long turn, its record late, still lands above its answer`() = runBlocking<Unit> {
        deliveredInOrder(TranscriptEngine.STABLE, turnGoesOnMs = 240_000L, recordLags = true)
    }

    private suspend fun deliveredInOrder(engine: TranscriptEngine, turnGoesOnMs: Long = 30_000L, recordLags: Boolean = false) {
        val root = folder.newFolder("rig")
        val rig = rig(engine, root)
        rig.open()
        rig.awaitUntilOr(60_000, "the chat open on its turn") { state.let { !it.isLoading && it.isStreaming && it.activeRunId == live.runId } }
        val followupId = rig.sendMidTurn(MESSAGE)
        val named = server.pending.getValue(agentId).single { it.followupId == followupId }.runId!!
        val queuedAt = rig.now
        // The turn under way goes on for a while after the message was queued, and ends.
        rig.now += turnGoesOnMs
        server.logs[live.runId] = server.logs.getValue(live.runId) + ("assistant" to """{"text":"$LAST_WORDS"}""")
        rig.awaitUntilOr(30_000, "the turn's last words") { state.items.any { it is AssistantMessage && it.markdown.contains(LAST_WORDS) } }
        rig.now += 5_000
        server.endTurn(agentId, durationMs = rig.now - live.startedAt)
        // The account starts the run it named on the message; the coordinator answers on it.
        val next = server.deliverNext(agentId, log = replyLog(named, finished = false))!!
        server.outage(Route.Stream, Fault.StreamCut(events = 2), path = "/${next.id}/")
        if (!recordLags) server.records[agentId] = server.records.getValue(agentId) + recordTurn(MESSAGE, REPLY, queuedAt)
        rig.awaitUntilOr(45_000, "the answer drawn") { order(state).let { it.first >= 0 && it.second >= 0 } }
        delay(2_000)
        dump("$engine: the answer streaming")
        assertInOrder("$engine live, the answer streaming")
        // The answer's run ends, and a worker's report starts the run after it: "Starting…".
        rig.now += 20_000
        server.logs[named] = replyLog(named, finished = true)
        server.runs[named] = server.runs.getValue(named).copy(status = "FINISHED", durationMs = 20_000L, updatedAt = LongProject.iso(rig.now))
        rig.now += 3_000
        val report = "run-report-after"
        val reportAt = LongProject.iso(rig.now)
        server.runs[report] = RunDto(id = report, agentId = agentId, status = "CREATING", createdAt = reportAt, updatedAt = reportAt)
        server.logs[report] = listOf("status" to """{"runId":"$report","status":"CREATING"}""")
        server.outage(Route.Stream, Fault.StreamCut(events = 1), path = "/$report/")
        server.agents[agentId] = server.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = report, updatedAt = reportAt)
        server.transcripts[agentId] = server.transcripts.getValue(agentId) + V0ConversationMessageDto("$named-a0", "assistant_message", REPLY)
        if (recordLags) server.records[agentId] = server.records.getValue(agentId) + recordTurn(MESSAGE, REPLY, queuedAt)
        rig.conversations.revalidate(agentId, force = true)
        rig.awaitUntilOr(45_000, "the report's run seen") { rig.conversations.loadDiagnostics(agentId)?.runsLoaded?.let { it >= TURNS + 2 } == true && !state.isLoading }
        delay(3_000)
        dump("$engine: the next run starting")
        assertInOrder("$engine live, the next run starting")
        assertEveryFrameInOrder("$engine live")

        // Reopened: the same chat read again from what the device kept and the server has.
        recorder?.cancel()
        rig.steering.detach(agentId)
        rig.close()
        frames.clear()
        val after = rig(engine, root)
        after.open()
        after.awaitUntilOr(60_000, "the reopened chat loaded") { !state.isLoading && order(state).let { it.first >= 0 && it.second >= 0 } }
        delay(8_000)
        dump("$engine: reopened")
        assertInOrder("$engine after a reopen")
        assertEveryFrameInOrder("$engine reopened")
    }

    @Test
    fun `Beta - a queued message the turn under way takes lands above its answer, not under the run the account named`() = runBlocking<Unit> {
        takenIntoTheTurn(TranscriptEngine.BETA)
    }

    @Test
    fun `Stable - a queued message the turn under way takes lands above its answer, not under the run the account named`() = runBlocking<Unit> {
        takenIntoTheTurn(TranscriptEngine.STABLE)
    }

    /**
     * Bennett's frame: the account named a run for the message when it took it, and then the coordinator took the
     * message into the turn under way between its steps and answered it there ("Love it. … is building it now") — the
     * turn's own story, after a tool call. The turn ended, and the run the account had named started with nothing to
     * say yet: "Starting…". The message is the turn's, drawn after the story told before it went in and above the
     * answer, never ahead of the named run below it all.
     */
    private suspend fun takenIntoTheTurn(engine: TranscriptEngine) {
        val root = folder.newFolder("rig")
        val rig = rig(engine, root)
        rig.open()
        rig.awaitUntilOr(60_000, "the chat open on its turn") { state.let { !it.isLoading && it.isStreaming && it.activeRunId == live.runId } }
        val followupId = rig.sendMidTurn(MESSAGE)
        val named = server.pending.getValue(agentId).single { it.followupId == followupId }.runId!!
        rig.now += 20_000
        server.logs[live.runId] = server.logs.getValue(live.runId) + ("assistant" to """{"text":"$LAST_WORDS"}""")
        server.transcripts[agentId] = server.transcripts.getValue(agentId) + V0ConversationMessageDto("${live.runId}-last", "assistant_message", LAST_WORDS)
        rig.awaitUntilOr(30_000, "the turn's last words") { state.items.any { it is AssistantMessage && it.markdown.contains(LAST_WORDS) } }
        // The coordinator takes the message between its steps, works on it and answers it, all in the turn under way.
        rig.now += 5_000
        assertWithMessage("the turn took the message").that(server.takeIntoTurn(agentId, followupId)).isEqualTo(live.runId)
        server.logs[live.runId] = server.logs.getValue(live.runId) +
            ("tool_call" to """{"callId":"call-taken","name":"shell","status":"completed","args":{"command":"ls"},"result":{"success":{"stdout":"app"}}}""") +
            ("assistant" to """{"text":"$REPLY"}""")
        server.transcripts[agentId] = server.transcripts.getValue(agentId) + V0ConversationMessageDto("${live.runId}-reply", "assistant_message", REPLY)
        server.records[agentId] = server.records.getValue(agentId) + recordTurn(MESSAGE, REPLY, rig.now, steer = true)
        rig.awaitUntilOr(30_000, "the answer streamed") { state.items.any { it is AssistantMessage && it.markdown.contains(REPLY) } }
        // The turn ends; the run the account named starts, nothing streamed yet.
        rig.now += 10_000
        server.endTurn(agentId, durationMs = rig.now - live.startedAt)
        val startedAt = LongProject.iso(rig.now)
        server.runs[named] = RunDto(id = named, agentId = agentId, status = "CREATING", createdAt = startedAt, updatedAt = startedAt)
        server.logs[named] = listOf("status" to """{"runId":"$named","status":"CREATING"}""")
        server.outage(Route.Stream, Fault.StreamCut(events = 1), path = "/$named/")
        server.agents[agentId] = server.agents.getValue(agentId).copy(status = "ACTIVE", latestRunId = named, updatedAt = startedAt)
        rig.conversations.revalidate(agentId, force = true)
        rig.awaitUntilOr(45_000, "the message filed") { state.items.any { it is UserMessage && it.text == MESSAGE } && rig.conversations.loadDiagnostics(agentId)?.runsLoaded?.let { it >= TURNS + 1 } == true }
        delay(6_000)
        dump("$engine taken: the named run starting")
        assertInOrder("$engine taken, live")
        assertEveryFrameInOrder("$engine taken, live")

        recorder?.cancel()
        rig.steering.detach(agentId)
        rig.close()
        frames.clear()
        val after = rig(engine, root)
        after.open()
        after.awaitUntilOr(60_000, "the reopened chat loaded") { !state.isLoading && order(state).let { it.first >= 0 && it.second >= 0 } }
        delay(8_000)
        dump("$engine taken: reopened")
        assertInOrder("$engine taken, after a reopen")
        assertEveryFrameInOrder("$engine taken, reopened")
    }

    private companion object {
        const val TURNS = 8
        const val MESSAGE = "On top of that, make the keyboard shortcuts customizable in Settings."
        const val REPLY = "Love it. Make keyboard shortcuts customizable is building it now."
        const val LAST_WORDS = "HELL YES, making this the default is the right move."
    }
}
