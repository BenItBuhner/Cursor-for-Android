package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0AgentDto
import com.cursorforandroid.data.api.dto.V0ConversationMessageDto
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ConversationState
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TranscriptEngine
import com.cursorforandroid.domain.UserMessage
import com.cursorforandroid.fixtures.BigProject
import com.cursorforandroid.ui.conversation.LoadNotices
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Bennett's running chat under "Rate limited by Cursor. Try again in 51 s." (v0.4.31, 2026-10-02): its stream
 * delivering, the card up after a pull to catch up. The pull read the run list's first page itself and failed whole on
 * its refusal — and the run list is one endpoint for every chat (`HostPause.endpoint`), its minute spent as often by
 * the other chats' reads as by this one's, so once refused, every pull inside the minute was refused on the phone and
 * said so. Here: a pull, or a reload, of a chat whose stream delivers says nothing of a rate limit, leaves the stream
 * alone and does not ask the refused endpoint again inside its wait; the record's delta still lands on Beta; and a pull
 * that fails for a reason the reader can act on says it under the transcript, taken down by the next pull that answers.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RunningChatRateLimitTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val now = 1_800_000_000_000L
    private val agentId = "bc-limited-0"
    private val run = "run-limited-0"

    @After
    fun tearDown() {
        rig?.let { r -> r.conversations.detach(agentId); r.close() }
        server.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun assistant(text: String) = "assistant" to """{"text":"$text"}"""

    /** One chat on a turn under way, streamed live, a line of the reply every beat; the account calls it running. */
    private fun runningChat() {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.liveRunStreams = true
        server.liveRunBeatMs = 150L
        server.liveRunGenerator = { _, tick -> listOf(assistant("Step $tick. ")) }
        server.runs[run] = RunDto(id = run, agentId = agentId, status = "RUNNING", createdAt = iso(now - 60_000L), updatedAt = iso(now - 1_000L))
        server.logs[run] = listOf("status" to """{"runId":"$run","status":"RUNNING"}""", assistant("Reading the repository. "))
        server.agents[agentId] = AgentDto(id = agentId, name = "Alexander Persian war video", status = "ACTIVE", createdAt = iso(now - 60_000L), updatedAt = iso(now - 1_000L), latestRunId = run, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = "Alexander Persian war video", status = "RUNNING")
        server.transcripts[agentId] = listOf(V0ConversationMessageDto("$run-u", "user_message", "Make the video"))
        server.composers[agentId] = FaultServer.Composer(agentId, "Alexander Persian war video", activityMs = now - 1_000L, running = true)
    }

    private val state: ConversationState get() = rig!!.conversations.state(agentId).value
    private fun said(): String = state.items.filterIsInstance<AssistantMessage>().joinToString("") { it.markdown }

    private suspend fun openStreaming(): FaultRig {
        val r = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.STABLE, http2 = true).also {
            it.now = now
            rig = it
        }
        r.agents.refresh()
        r.conversations.attach(agentId)
        r.awaitUntil(30_000) { state.let { !it.isLoading && it.isStreaming && it.activeRunId == run } && said().contains("Step") }
        return r
    }

    /** Neither the screen's failure, the transcript's, nor any notice docked over the composer. */
    private fun assertNothingSaid(context: String) {
        assertWithMessage("$context: error").that(state.error).isNull()
        assertWithMessage("$context: transcript error").that(state.transcriptError).isNull()
        assertWithMessage("$context: notices").that(LoadNotices.of(state)).isEmpty()
    }

    private fun refuseRunList(retryAfterS: Int) =
        server.outage(Route.ListRuns, Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = retryAfterS.toString()))

    @Test
    fun `pulls on a streaming chat whose run list is refused say nothing and ask the run list once inside its minute`() = runBlocking<Unit> {
        runningChat()
        val rig = openStreaming()
        refuseRunList(60)
        val from = server.seen.size
        val pulls = 12
        repeat(pulls) { i ->
            val answer = rig.conversations.catchUp(agentId)
            assertWithMessage("pull $i: $answer").that(answer.error).isNull()
            assertNothingSaid("pull $i")
            assertWithMessage("pull $i: still streaming").that(state.isStreaming).isTrue()
            delay(100)
        }
        // The stream went on delivering through every pull.
        val before = said().length
        rig.awaitUntil(5_000) { said().length > before }
        assertNothingSaid("after the pulls")

        val asked = server.seen.drop(from).groupingBy { it.route }.eachCount()
        println("BUDGET $pulls pulls inside the run list's minute: $asked")
        // The refusal is heard once; the rest of the minute is the phone's own to wait, not Cursor's to be asked.
        assertWithMessage("run list asked: $asked").that(asked[Route.ListRuns] ?: 0).isAtMost(1)
        assertWithMessage("transcript asked: $asked").that(asked[Route.Conversation] ?: 0).isEqualTo(0)
        // The live stream is not opened again by a pull on a run it is delivering.
        assertWithMessage("streams opened: $asked").that(asked[Route.Stream] ?: 0).isEqualTo(0)
        assertWithMessage("everything, per pull: $asked").that(asked.values.sum().toDouble() / pulls).isAtMost(MAX_REQUESTS_PER_REFUSED_PULL)
    }

    @Test
    fun `a streaming chat reloaded while its run list is refused says nothing`() = runBlocking<Unit> {
        runningChat()
        val rig = openStreaming()
        refuseRunList(60)
        repeat(3) {
            rig.conversations.reload(agentId)
            rig.conversations.awaitLoad(agentId)
            assertNothingSaid("reload $it")
        }
        assertThat(said()).contains("Step")
        assertThat(state.isStreaming).isTrue()
    }

    @Test
    fun `a pull refused for a while is read again by itself once the wait has passed, nothing said`() = runBlocking<Unit> {
        runningChat()
        val rig = openStreaming()
        // Past what the client sleeps through itself (ten seconds), so the refusal is the caller's to wait out.
        refuseRunList(12)
        val from = server.seen.size
        assertThat(rig.conversations.catchUp(agentId).error).isNull()
        server.clear(Route.ListRuns)
        assertNothingSaid("refused")
        rig.awaitUntil(25_000) { server.seen.drop(from).count { it.route == Route.ListRuns } >= 2 }
        val reread = server.seen.drop(from).filter { it.route == Route.ListRuns }
        assertWithMessage("the re-read waited out the twelve seconds: $reread").that(reread[1].atMillis - reread[0].atMillis).isAtLeast(12_000L)
        assertNothingSaid("read again")
    }

    @Test
    fun `a pull that fails for a reason the reader can act on says it under the transcript, and the next pull takes it down`() = runBlocking<Unit> {
        runningChat()
        val rig = openStreaming()
        server.outage(Route.ListRuns, Fault.Status(500, "internal_error", "Run index unavailable; try again shortly."))
        val failed = rig.conversations.catchUp(agentId)
        assertThat(failed.error).isEqualTo("Run index unavailable; try again shortly.")
        assertThat(state.transcriptError).isEqualTo("Run index unavailable; try again shortly.")
        assertThat(LoadNotices.of(state).map { it.title }).containsExactly("Couldn't refresh the transcript: Run index unavailable; try again shortly.")
        server.clear(Route.ListRuns)
        assertThat(rig.conversations.catchUp(agentId).error).isNull()
        assertNothingSaid("answered")
    }

    /** Beta: a worker's chat at rest, its record read, as in `PullToCatchUpTest`. */
    private val worker = BigProject.WORKERS.first()
    private val turns = BigProject.workerTurns(now - 10 * BigProject.TURN_SPACING_MS, turns = 6)

    private suspend fun betaAtRest(): FaultRig {
        server = FaultServer(rttMillis = 20L..60L).start()
        turns.forEach { turn ->
            server.runs[turn.runId] = RunDto(id = turn.runId, agentId = worker, status = "FINISHED", createdAt = BigProject.iso(turn.startedAt), updatedAt = BigProject.iso(turn.endedAt), durationMs = turn.durationMs, result = turn.narration.last())
            server.logs[turn.runId] = turn.log
        }
        val newest = turns.last()
        server.agents[worker] = AgentDto(id = worker, name = "Scanner", status = "IDLE", createdAt = BigProject.iso(turns.first().startedAt), updatedAt = BigProject.iso(newest.endedAt), latestRunId = newest.runId, url = "https://cursor.com/agents/$worker")
        server.v0[worker] = V0AgentDto(id = worker, name = "Scanner", status = "FINISHED")
        server.transcripts[worker] = BigProject.v0Transcript(turns)
        server.records[worker] = turns.flatMap { it.record }
        server.composers[BigProject.AGENT_ID] = FaultServer.Composer(BigProject.AGENT_ID, BigProject.AGENT_NAME, activityMs = now - 1_000L, project = true)
        server.composers[worker] = FaultServer.Composer(worker, "Scanner", activityMs = newest.endedAt, manager = BigProject.AGENT_ID)
        server.workers[BigProject.AGENT_ID] = listOf(worker to "MANAGER_SPAWN_KIND_CREATED")
        server.outage(Route.Live, Fault.Gateway(502))
        val r = FaultRig(server.baseUrl, folder.newFolder("rig-beta"), readTimeoutMs = 8_000L, extended = true, engine = TranscriptEngine.BETA).also {
            it.now = now
            rig = it
        }
        r.agents.refresh()
        r.conversations.attach(worker)
        r.awaitUntil(60_000) { workerShows(turns.last().prompt) && !workerState.isLoading && !workerState.isLoadingOlder && workerState.traceStatus.pending == 0 }
        return r
    }

    private val workerState: ConversationState get() = rig!!.conversations.state(worker).value
    private fun workerShows(text: String) = workerState.items.any { it is UserMessage && it.text == text }

    @Test
    fun `Beta - a pull while the run list is refused still lands the record's new turn, and says nothing`() = runBlocking<Unit> {
        val rig = betaAtRest()
        try {
            server.outage(Route.AccountList, Fault.Gateway(502))
            refuseRunList(60)
            val prompt = "From the desktop: the fee table for market 200, then report back."
            server.outage(Route.Stream, Fault.StreamCut(events = 1), path = "/run-elsewhere-1/")
            server.startTurnElsewhere(worker, prompt, "run-elsewhere-1")
            val answer = rig.conversations.catchUp(worker)
            assertThat(answer.error).isNull()
            assertThat(answer.newMessages).isEqualTo(1)
            assertThat(workerShows(prompt)).isTrue()
            assertThat(workerState.error).isNull()
            assertThat(workerState.transcriptError).isNull()
            assertThat(LoadNotices.of(workerState)).isEmpty()
        } finally {
            rig.conversations.detach(worker)
        }
    }

    @Test
    fun `Beta - a pull whose record read is refused says nothing over the chat`() = runBlocking<Unit> {
        val rig = betaAtRest()
        try {
            server.outage(Route.RecordState, Fault.Status(429, "resource_exhausted", "Too many requests", retryAfter = "60"))
            val shown = workerState.items
            val answer = rig.conversations.catchUp(worker)
            assertThat(answer.error).isNull()
            assertThat(workerState.items).isEqualTo(shown)
            assertThat(workerState.error).isNull()
            assertThat(workerState.transcriptError).isNull()
            assertThat(LoadNotices.of(workerState)).isEmpty()
        } finally {
            rig.conversations.detach(worker)
        }
    }

    private companion object {
        /**
         * A pull with the run list held is answered on the phone: the account list's word taken afresh and nothing
         * else. Before, each was a refusal said in a snackbar; a pull that reread the chat would be five or more.
         */
        const val MAX_REQUESTS_PER_REFUSED_PULL = 2.0
    }
}
