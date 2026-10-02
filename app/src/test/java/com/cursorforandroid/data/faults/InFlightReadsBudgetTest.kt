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
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The run list (`GET /v1/agents/{id}/runs`) and the run record (`GET /v1/agents/{id}/runs/{runId}`) are one endpoint
 * each for every chat (`HostPause.endpoint`), twenty a minute between them all. Whoever asks for the same agent's
 * newest runs, or the same run's record, while a read of it is out joins that read rather than sending its own: a
 * turn's end has the live hub's settle, the notification's tracking, the chat's look for the next run and a pull ask
 * within moments of each other. A read is shared only while it is out — the next ask, after it landed, reads afresh.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InFlightReadsBudgetTest {

    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: FaultServer
    private var rig: FaultRig? = null
    private val now = 1_800_000_000_000L
    private val agentId = "bc-shared-0"
    private val run = "run-shared-0"

    @After
    fun tearDown() {
        rig?.let { r -> r.conversations.detach(agentId); r.close() }
        server.close()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun assistant(text: String) = "assistant" to """{"text":"$text"}"""

    private val state: ConversationState get() = rig!!.conversations.state(agentId).value
    private fun said(): String = state.items.filterIsInstance<AssistantMessage>().joinToString("") { it.markdown }

    /** One chat open on a turn under way, streamed live, a line of the reply every beat. */
    private suspend fun openStreaming(): FaultRig {
        server = FaultServer(rttMillis = 5L..20L, http2 = true).start()
        server.liveRunStreams = true
        server.liveRunBeatMs = 150L
        server.liveRunGenerator = { _, tick -> listOf(assistant("Step $tick. ")) }
        server.runs[run] = RunDto(id = run, agentId = agentId, status = "RUNNING", createdAt = iso(now - 60_000L), updatedAt = iso(now - 1_000L))
        server.logs[run] = listOf("status" to """{"runId":"$run","status":"RUNNING"}""", assistant("Reading the repository. "))
        server.agents[agentId] = AgentDto(id = agentId, name = "Shared reads", status = "ACTIVE", createdAt = iso(now - 60_000L), updatedAt = iso(now - 1_000L), latestRunId = run, url = "https://cursor.com/agents/$agentId")
        server.v0[agentId] = V0AgentDto(id = agentId, name = "Shared reads", status = "RUNNING")
        server.transcripts[agentId] = listOf(V0ConversationMessageDto("$run-u", "user_message", "Fix the flaky test"))
        server.composers[agentId] = FaultServer.Composer(agentId, "Shared reads", activityMs = now - 1_000L, running = true)
        val r = FaultRig(server.baseUrl, folder.newFolder("rig"), readTimeoutMs = 20_000L, extended = true, engine = TranscriptEngine.STABLE, http2 = true).also {
            it.now = now
            rig = it
        }
        r.agents.refresh()
        r.conversations.attach(agentId)
        r.awaitUntil(30_000) { state.let { !it.isLoading && it.isStreaming && it.activeRunId == run } && said().contains("Step") }
        return r
    }

    private fun budgetCount(what: String, asked: List<FaultServer.Seen>, max: Int) =
        assertWithMessage("budget exceeded, $what: ${asked.size} requests, over the budget of $max: $asked").that(asked.size).isAtMost(max)

    @Test
    fun `a reload while a pull's read of the run list is out joins it`() = runBlocking<Unit> {
        val rig = openStreaming()
        val held = Fault.Held()
        server.script(Route.ListRuns, held)
        val from = server.seen.size
        val pull = async { rig.conversations.catchUp(agentId) }
        rig.awaitUntil(10_000) { held.reached }
        rig.conversations.reload(agentId)
        // The reload's own read of the newest runs would land on the server now, behind the held one: none does.
        kotlinx.coroutines.delay(500)
        budgetCount("reads of the run list's first page while one is out", server.seen.drop(from).filter { it.route == Route.ListRuns }, 1)
        held.release()
        assertThat(pull.await().error).isNull()
        rig.conversations.awaitLoad(agentId)
        assertThat(state.error).isNull()
        assertThat(state.isStreaming).isTrue()
        // Shared only while it is out: the next ask reads afresh.
        val after = server.seen.size
        rig.conversations.catchUp(agentId)
        assertWithMessage("a pull after the shared read landed reads the run list itself").that(server.seen.drop(after).count { it.route == Route.ListRuns }).isEqualTo(1)
    }

    @Test
    fun `everyone asking for one run's record while it is out shares one read`() = runBlocking<Unit> {
        val rig = openStreaming()
        val held = Fault.Held()
        server.script(Route.GetRun, held, path = "/runs/$run")
        val from = server.seen.size
        val readers = (1..4).map { async { rig.agents.runRecord(agentId, run) } }
        rig.awaitUntil(10_000) { held.reached }
        kotlinx.coroutines.delay(500)
        budgetCount("reads of one run's record while one is out", server.seen.drop(from).filter { it.route == Route.GetRun && it.path.endsWith("/runs/$run") }, 1)
        held.release()
        val records = readers.awaitAll()
        assertThat(records.map { it.id }.toSet()).containsExactly(run)
        assertThat(records.map { it.status }.toSet()).containsExactly("RUNNING")
        val after = server.seen.size
        rig.agents.runRecord(agentId, run)
        assertWithMessage("a read after the shared one landed asks the server itself").that(server.seen.drop(after).count { it.route == Route.GetRun && it.path.endsWith("/runs/$run") }).isEqualTo(1)
    }
}
