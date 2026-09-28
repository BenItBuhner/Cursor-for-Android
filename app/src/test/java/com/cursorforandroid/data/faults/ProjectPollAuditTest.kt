package com.cursorforandroid.data.faults

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.api.ProjectLineageApi
import com.cursorforandroid.data.api.RootScan
import com.cursorforandroid.data.faults.FaultServer.Composer
import com.cursorforandroid.data.faults.FaultServer.Fault
import com.cursorforandroid.data.faults.FaultServer.Route
import com.cursorforandroid.data.repo.ProjectRepository
import com.cursorforandroid.data.repo.RootScanRecord
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Audit harness: what one open Project costs on the wire. The Project's poll ([ProjectRepository.attach]) runs at a
 * tenth of production's interval (2 s for 20 s) against a clock moved ten times faster than wall time, so 30 s of
 * wall time is five simulated minutes of a Project on screen. Prints requests per route; asserts nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProjectPollAuditTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: FaultServer
    private lateinit var rig: FaultRig
    private val projectId = "bc-proj-audit"

    @Before
    fun setUp() {
        server = FaultServer().start()
        rig = FaultRig(server.baseUrl, folder.newFolder("rig"), extended = true)
        val now = 1_800_000_000_000L
        fun created(ms: Long) = Instant.ofEpochMilli(ms).toString()
        server.addIdleAgent(projectId, "Audit Project", "run-$projectId", createdAt = created(now - 3_600_000L))
        server.composers[projectId] = Composer(projectId, "Audit Project", now - 3_600_000L, project = true)
        server.workers[projectId] = (1..20).map { "$projectId-w$it" to "MANAGER_SPAWN_KIND_CREATED" }
        (1..20).forEach { w ->
            val wid = "$projectId-w$w"
            val at = now - 3_600_000L - w * 60_000L
            server.addIdleAgent(wid, "Worker $w", "run-$wid", createdAt = created(at))
            server.composers[wid] = Composer(wid, "Worker $w", at, manager = projectId)
        }
        (1..2).forEach { s ->
            val sid = "$projectId-side$s"
            server.addIdleAgent(sid, "Side $s", "run-$sid", createdAt = created(now - 3_500_000L))
            server.composers[sid] = Composer(sid, "Side $s", now - 3_500_000L, sideChatOf = projectId)
        }
        repeat(40) { i ->
            val id = "bc-own-${i + 1}"
            val at = now - i * 6 * 3_600_000L - 30_000L
            server.addIdleAgent(id, "Own ${i + 1}", "run-$id", createdAt = created(at))
            server.composers[id] = Composer(id, "Own ${i + 1}", at)
        }
        server.pageSize = 50
        rig.now = now + 60_000L
    }

    @After
    fun tearDown() {
        rig.close()
        server.close()
    }

    private fun probe(): ProjectRepository {
        val lineage = object : ProjectLineageApi by rig.projectApi {
            override suspend fun record(id: String): ComposerSnapshot? = rig.accountAgents.record(id)
            override suspend fun scanRoots(maxPages: Int): RootScan = rig.accountAgents.scanRoots(maxPages)
            override suspend fun scanRoots(maxPages: Int, stopBelowActivityMillis: Long?): RootScan = rig.accountAgents.scanRoots(maxPages, stopBelowActivityMillis)
        }
        return ProjectRepository(rig.session, rig.agents, lineage, actions = rig.projectApi, store = rig.projectApi, scope = rig.scope, pollIntervalMs = 20_000L / SCALE, capabilities = { rig.capabilities }, retryDelaysMs = listOf(500L))
    }

    private fun counts(from: Int): Map<Route, Int> = server.seen.drop(from).groupingBy { it.route }.eachCount().toSortedMap()

    private suspend fun window(label: String, wallMs: Long, body: suspend () -> Unit = {}) {
        val from = server.seen.size
        val listEmissions = AtomicInteger()
        val watcher = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.agents.state.collect { listEmissions.incrementAndGet() } }
        val clock = rig.scope.launch { while (true) { delay(100); rig.now += 100L * SCALE } }
        val started = System.nanoTime()
        body()
        val left = wallMs - (System.nanoTime() - started) / 1_000_000
        if (left > 0) delay(left)
        clock.cancel(); watcher.cancel()
        val c = counts(from)
        val simulatedMin = wallMs * SCALE / 60_000.0
        println("AUDIT[$label] wall=${wallMs}ms simulated=${"%.1f".format(simulatedMin)}min total=${c.values.sum()} perSimMin=${"%.1f".format(c.values.sum() / simulatedMin)} listEmissions=${listEmissions.get() - 1} routes=$c")
    }

    @Test
    fun `an open Project's poll on the wire`() = runBlocking<Unit> {
        rig.agents.refresh()
        rig.awaitUntil(60_000) { rig.projects.lastRootScan.value?.status == RootScanRecord.Status.Done }
        delay(3_000)
        window("baseline, nothing open", 30_000)
        val repo = probe()
        val viewEmissions = AtomicInteger()
        val viewJob = rig.scope.launch { repo.view(projectId).collect { viewEmissions.incrementAndGet() } }
        window("project attached, idle account", 30_000) { repo.attach(projectId) }
        println("AUDIT view emissions over attached window: ${viewEmissions.get()}")
        val refusal = Fault.Status(429, "rate_limited", "Too many requests from this key.", retryAfter = "1")
        server.outage(Route.Workers, refusal)
        server.outage(Route.Children, refusal)
        server.outage(Route.ListAgents, refusal)
        window("project attached, 429 on Workers+Children+ListAgents", 30_000)
        server.clear(Route.Workers); server.clear(Route.Children); server.clear(Route.ListAgents)
        repo.detach(projectId)
        viewJob.cancel()
    }

    private companion object {
        const val SCALE = 10L
    }
}
