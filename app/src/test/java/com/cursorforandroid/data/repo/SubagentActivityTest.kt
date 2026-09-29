package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.domain.ThinkingBlock
import com.cursorforandroid.domain.ToolCall
import com.cursorforandroid.domain.ToolKind
import com.cursorforandroid.fixtures.LiveModelCatalog
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A cloud subagent's row follows it live: the list's row for how its latest run stands, and while that run goes, its
 * stream for the step it announced and the action it is on — the line under the row's title, moving as the child works.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentActivityTest {

    private fun agent(status: RunStatus, runId: String = "run-1", modelId: String? = "composer-2.5") = Agent(
        id = "bc-w1", name = "Usage events aggregation", lifecycle = AgentLifecycle.ACTIVE, runStatus = status, envType = EnvType.CLOUD, envName = null,
        url = "https://cursor.com/agents/bc-w1", createdAtMillis = 1L, updatedAtMillis = 2L, latestRunId = runId, repoUrl = null, startingRef = null, modelId = modelId,
    )

    private fun call(id: String, kind: ToolKind, summary: String, name: String = kind.name.lowercase()) = ToolCall(id, name, kind, ToolCall.STATUS_RUNNING, summary)

    @Test
    fun `the action line moves with the child's stream, and its end settles the row`() = runTest(UnconfinedTestDispatcher()) {
        val row = MutableStateFlow<Agent?>(agent(RunStatus.RUNNING))
        val stream = MutableStateFlow(SubagentActivity.Run(emptyList(), finished = false, status = RunStatus.RUNNING))
        val followed = mutableListOf<String>()
        val activity = SubagentActivity(
            row = { row },
            load = { error("the list holds it") },
            run = { _, runId, _ -> followed += runId; stream },
            models = MutableStateFlow(LiveModelCatalog.models),
        )
        val seen = mutableListOf<SubagentChild?>()
        val job = launch { activity.of("bc-w1").collect { seen += it } }

        // The list's row: running, on Composer 2.5 with Fast, nothing said yet.
        assertThat(seen.last()!!.status).isEqualTo(SubagentChild.Status.Running)
        assertThat(seen.last()!!.model?.label).isEqualTo("Composer 2.5")
        assertThat(seen.last()!!.model?.fast).isTrue()
        assertThat(seen.last()!!.action).isNull()
        assertThat(followed).containsExactly("run-1")

        stream.value = SubagentActivity.Run(listOf(ActivityGroup("g1", listOf(call("e1", ToolKind.Edit, "Chart.kt")))), finished = false, status = RunStatus.RUNNING)
        assertThat(seen.last()!!.action).isEqualTo("Editing Chart.kt")
        stream.value = SubagentActivity.Run(listOf(ActivityGroup("g1", listOf(call("e1", ToolKind.Edit, "Chart.kt"), call("w1", ToolKind.WebSearch, "compose shimmer", "web_search")))), finished = false, status = RunStatus.RUNNING)
        assertThat(seen.last()!!.action).isEqualTo("Searching web compose shimmer")
        stream.value = SubagentActivity.Run(listOf(ActivityGroup("g1", listOf(call("u1", ToolKind.Other, "Wiring the hover state", "update_current_step"), ThinkingBlock("…", isStreaming = true)))), finished = false, status = RunStatus.RUNNING)
        assertThat(seen.last()!!.step).isEqualTo("Wiring the hover state")
        assertThat(seen.last()!!.action).isEqualTo("Thinking")

        // The run ends: the list's row says so, and the stream is let go.
        row.value = agent(RunStatus.FINISHED)
        assertThat(seen.last()!!.status).isEqualTo(SubagentChild.Status.Succeeded)
        // A new turn (a steer, a queued message): the new run is followed.
        row.value = agent(RunStatus.RUNNING, runId = "run-2")
        assertThat(followed).containsExactly("run-1", "run-2").inOrder()
        job.cancel()
    }

    @Test
    fun `a child the list does not hold is read once, and a finished one is never streamed`() = runTest(UnconfinedTestDispatcher()) {
        var loads = 0
        val activity = SubagentActivity(
            row = { flowOf(null) },
            load = { loads++; agent(RunStatus.ERROR) },
            run = { _, _, _ -> error("a finished run is not streamed") },
            models = MutableStateFlow(emptyList<ModelOption>()),
        )
        val seen = mutableListOf<SubagentChild?>()
        val job = launch { activity.of("bc-w1").collect { seen += it } }
        assertThat(seen.last()!!.status).isEqualTo(SubagentChild.Status.Failed)
        assertThat(seen.last()!!.name).isEqualTo("Usage events aggregation")
        assertThat(loads).isEqualTo(1)
        job.cancel()
    }

    @Test
    fun `followers past the gate's cap are looked in on until a place frees, and every place is given back`() = runTest(UnconfinedTestDispatcher()) {
        val gate = SubagentStreamGate(maxStreams = 2)
        val rows = (1..3).associate { i -> "bc-w$i" to MutableStateFlow<Agent?>(agent(RunStatus.RUNNING).copy(id = "bc-w$i")) }
        val watched = mutableMapOf<String, kotlinx.coroutines.flow.StateFlow<Boolean>>()
        val activity = SubagentActivity(
            row = { id -> rows.getValue(id) },
            load = { error("the list holds it") },
            run = { agentId, _, w -> watched[agentId] = w; MutableStateFlow(SubagentActivity.Run(emptyList(), finished = false, status = RunStatus.RUNNING)) },
            models = MutableStateFlow(LiveModelCatalog.models),
            streams = gate,
        )
        val jobs = rows.keys.associateWith { id -> launch { activity.of(id).collect { } } }
        assertThat(watched.mapValues { it.value.value }).containsExactly("bc-w1", true, "bc-w2", true, "bc-w3", false)
        assertThat(gate.counts()).isEqualTo(2 to 1)

        // The first row leaves the screen: the one that waited takes its place, and the second keeps its own.
        jobs.getValue("bc-w1").cancel()
        assertThat(watched.getValue("bc-w3").value).isTrue()
        assertThat(watched.getValue("bc-w2").value).isTrue()
        assertThat(gate.counts()).isEqualTo(2 to 0)

        // A run that ends lets go of its stream, and of its place with it.
        rows.getValue("bc-w2").value = agent(RunStatus.FINISHED).copy(id = "bc-w2")
        assertThat(gate.counts()).isEqualTo(1 to 0)
        jobs.getValue("bc-w2").cancel()
        jobs.getValue("bc-w3").cancel()
        assertThat(gate.counts()).isEqualTo(0 to 0)
    }

    @Test
    fun `a child read off its list row alone is never streamed`() = runTest(UnconfinedTestDispatcher()) {
        val row = MutableStateFlow<Agent?>(agent(RunStatus.RUNNING))
        val gate = SubagentStreamGate()
        val activity = SubagentActivity(
            row = { row },
            load = { error("the list holds it") },
            run = { _, _, _ -> error("a listed child is not streamed") },
            models = MutableStateFlow(LiveModelCatalog.models),
            streams = gate,
        )
        val seen = mutableListOf<SubagentChild?>()
        val job = launch { activity.listed("bc-w1").collect { seen += it } }
        assertThat(seen.last()!!.status).isEqualTo(SubagentChild.Status.Running)
        assertThat(seen.last()!!.model?.label).isEqualTo("Composer 2.5")
        row.value = agent(RunStatus.FINISHED)
        assertThat(seen.last()!!.status).isEqualTo(SubagentChild.Status.Succeeded)
        assertThat(gate.counts()).isEqualTo(0 to 0)
        job.cancel()
    }

    @Test
    fun `a child on standby is looked in on without a place, and taken up on the same stream`() = runTest(UnconfinedTestDispatcher()) {
        val gate = SubagentStreamGate(maxStreams = 1)
        var subscriptions = 0
        var watched: kotlinx.coroutines.flow.StateFlow<Boolean>? = null
        val activity = SubagentActivity(
            row = { MutableStateFlow(agent(RunStatus.RUNNING)) },
            load = { error("the list holds it") },
            run = { _, _, w -> subscriptions++; watched = w; MutableStateFlow(SubagentActivity.Run(emptyList(), finished = false, status = RunStatus.RUNNING)) },
            models = MutableStateFlow(LiveModelCatalog.models),
            streams = gate,
        )
        val wanted = MutableStateFlow(false)
        val job = launch { activity.of("bc-w1", wanted).collect { } }
        assertThat(watched!!.value).isFalse()
        assertThat(gate.counts()).isEqualTo(0 to 0)

        wanted.value = true
        assertThat(watched!!.value).isTrue()
        assertThat(gate.counts()).isEqualTo(1 to 0)

        wanted.value = false
        assertThat(watched!!.value).isFalse()
        assertThat(gate.counts()).isEqualTo(0 to 0)

        wanted.value = true
        job.cancel()
        assertThat(gate.counts()).isEqualTo(0 to 0)
        assertThat(subscriptions).isEqualTo(1)
    }
}
