package com.cursorforandroid.data.repo

import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.RunStatus
import com.cursorforandroid.domain.SubagentChild
import com.cursorforandroid.domain.SubagentRows
import com.cursorforandroid.domain.TimelineItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn

/**
 * Where a cloud subagent stands, for the row its parent's transcript draws for it: a Project's worker, or a task
 * the agent ran in a VM of its own. The list's row says how the child's latest run stands, its name and its model;
 * while that run is going its live stream — the one [LiveRunHub] shares with the child's own screen — says the step
 * it announced and the action it is on, the line the desktop's row reads under the title. The stream is followed
 * only while the child runs: a finished run's log would be replayed from its start for nothing.
 */
class SubagentActivity(
    /** The list's live row for a chat, or null while the list does not hold it. */
    private val row: (agentId: String) -> Flow<Agent?>,
    /** The chat's row read from the server, for a child the list does not hold. */
    private val load: suspend (agentId: String) -> Agent?,
    /** The live snapshots of one run of a chat, streamed while [watched] and looked in on otherwise. */
    private val run: (agentId: String, runId: String, watched: StateFlow<Boolean>) -> Flow<Run>,
    private val models: StateFlow<List<ModelOption>>,
    private val streams: SubagentStreamGate = SubagentStreamGate(),
) {
    /** What a run's stream has said so far: its items, and whether it has seen the run end and how. */
    data class Run(val items: List<TimelineItem>, val finished: Boolean, val status: RunStatus)

    constructor(agents: AgentRepository, hub: LiveRunHub, models: StateFlow<List<ModelOption>>, streams: SubagentStreamGate = SubagentStreamGate()) : this(
        row = { id -> agents.state.map { state -> state.agents.firstOrNull { it.id == id } } },
        load = { id -> agents.loadDetail(id).getOrNull() },
        run = { agentId, runId, watched -> hub.snapshots(agentId, runId, watched = watched).map { Run(it.items, it.finished, it.status) } },
        models = models,
        streams = streams,
    )

    /** The child [agentId] as its row and its live run say it, as it moves; null until anything is known of it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun of(agentId: String): Flow<SubagentChild?> = channelFlow {
        // Shared within this collection: both the row and the stream below follow it, and the read is one request.
        val agent = agent(agentId).shareIn(this, SharingStarted.Eagerly, replay = 1)
        val live: Flow<SubagentChild?> = agent
            .map { a -> a?.takeIf { it.isRunning }?.latestRunId }
            .distinctUntilChanged()
            .flatMapLatest { runId ->
                if (runId == null) {
                    flowOf(null)
                } else {
                    followed(agentId, runId)
                        .map<Run, SubagentChild?> { SubagentRows.withRun(SubagentChild(), it.items, it.finished, it.status) }
                        .onStart { emit(null) }
                }
            }
            .distinctUntilChanged()
        combine(agent, live, models) { a, l, m -> merge(a?.let { SubagentRows.childOf(it, m) }, l) }.distinctUntilChanged().collect { send(it) }
    }

    /**
     * The child [agentId] as its row alone says it — how its latest run stands, its name and model — with no stream:
     * for a place that only counts the children still at work, where the step and action of each are not drawn.
     */
    fun listed(agentId: String): Flow<SubagentChild?> =
        combine(agent(agentId), models) { a, m -> a?.let { SubagentRows.childOf(it, m) } }.distinctUntilChanged()

    private fun agent(agentId: String): Flow<Agent?> {
        val listed = row(agentId).distinctUntilChanged()
        // A child the list does not hold is read once; the list's row, when it arrives, outranks the read.
        val read = flow<Agent?> {
            emit(null)
            if (row(agentId).first() == null) emit(load(agentId))
        }
        return combine(listed, read) { l, r -> l ?: r }.distinctUntilChanged()
    }

    /** [runId] of [agentId] for as long as it is collected, holding a place at [streams] throughout. */
    private fun followed(agentId: String, runId: String): Flow<Run> = flow {
        val ticket = streams.acquire()
        try {
            emitAll(run(agentId, runId, ticket.watched))
        } finally {
            streams.release(ticket)
        }
    }

    /** The list's word on the child, with its live run's over it: the run's status, step and action, and a question it waits on. */
    private fun merge(listed: SubagentChild?, live: SubagentChild?): SubagentChild? {
        if (live == null) return listed
        val base = listed ?: SubagentChild()
        val status = live.status ?: base.status
        return base.copy(
            status = status,
            step = live.step ?: base.step,
            action = live.action ?: base.action,
            waiting = live.waiting || (base.waiting && status == SubagentChild.Status.Running),
        )
    }
}
