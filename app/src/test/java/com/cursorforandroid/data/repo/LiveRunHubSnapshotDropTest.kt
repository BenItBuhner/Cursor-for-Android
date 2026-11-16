package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.api.RunStreamEvent
import com.cursorforandroid.data.api.dto.SseToolCallDto
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.ActivityGroup
import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * After a stream dies mid-run the published snapshot keeps the story so far and says the hub is reconnecting.
 * Virtual time then lets the reconnect delay elapse; the next snapshot is the same story continued, not a second
 * copy of it and not a blank one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class LiveRunHubSnapshotDropTest {

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private lateinit var session: SessionManager
    private lateinit var agents: AgentRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferencesStore(context)
        val backend = CursorBackend(api, streamer, isDemo = true)
        session = SessionManager(SecureKeyStore(context), prefs, backend, backend)
        session.enterDemo()
        agents = AgentRepository(session, prefs, AttachmentStore(context))
    }

    @After
    fun tearDown() {
        // The hub's scope is the test's, cancelled with it. Nothing else is left running.
    }

    @Test
    fun `a dropped stream keeps its snapshot and continues it after the reconnect delay`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val hubScope = CoroutineScope(coroutineContext + SupervisorJob() + dispatcher)
        val hub = LiveRunHub(
            session,
            agents,
            nowProvider = { 1_800_000_000_000L },
            pollIntervalMs = 60_000L,
            releaseGraceMs = 60_000L,
            reconnectBaseMs = 1_000L,
            reconnectMaxMs = 1_000L,
            stallTimeoutMs = 0L,
            scope = hubScope,
        )
        api.addRunningAgent("bc-1", "Agent", "run-1")
        streamer.emit("run-1", RunStreamEvent.Status("run-1", RunStatus.RUNNING))
        streamer.emit("run-1", RunStreamEvent.Thinking("Reading the code."))
        streamer.emit("run-1", tool("c1", "read_file", "running", "README.md"))
        streamer.dropNextConnection("run-1", "upstream_error", "Run stream failed", afterEvents = 3)

        val seen = mutableListOf<LiveRunHub.Snapshot>()
        val subscription = launch(dispatcher) {
            hub.snapshots("bc-1", "run-1").collect { seen += it }
        }
        runCurrent()

        val dropped = hub.current("bc-1", "run-1")!!
        assertThat(dropped.reconnecting).isTrue()
        assertThat(dropped.finished).isFalse()
        assertThat(dropped.status).isEqualTo(RunStatus.RUNNING)
        val droppedWork = dropped.items.filterIsInstance<ActivityGroup>().single()
        assertThat(droppedWork.thoughts.map { it.text }).containsExactly("Reading the code.")
        assertThat(droppedWork.calls.single().status).isEqualTo("running")
        assertThat(streamer.resumes).containsExactly(null)
        assertThat(seen.last().reconnecting).isTrue()

        advanceTimeBy(1_000L)
        runCurrent()

        assertThat(streamer.resumes).containsExactly(null, "run-1#3").inOrder()
        val held = hub.current("bc-1", "run-1")!!
        assertThat(held.reconnecting).isTrue()
        assertThat(held.items.filterIsInstance<ActivityGroup>().single().thoughts.map { it.text })
            .containsExactly("Reading the code.")

        streamer.emit("run-1", tool("c1", "read_file", "completed", "README.md"))
        streamer.emit("run-1", RunStreamEvent.Assistant("Done."))
        runCurrent()

        val continued = hub.current("bc-1", "run-1")!!
        assertThat(continued.reconnecting).isFalse()
        assertThat(continued.finished).isFalse()
        val continuedWork = continued.items.filterIsInstance<ActivityGroup>().single()
        assertThat(continuedWork.thoughts.map { it.text }).containsExactly("Reading the code.")
        assertThat(continuedWork.thoughts.single().isStreaming).isFalse()
        assertThat(continuedWork.calls.single().status).isEqualTo("completed")
        assertThat(continued.items.filterIsInstance<AssistantMessage>().map { it.markdown }).containsExactly("Done.")
        assertThat(seen.count { it.reconnecting }).isAtLeast(1)
        assertThat(seen.last().reconnecting).isFalse()
        val withWork = seen.filter { snap -> snap.items.any { it is ActivityGroup } }
        assertThat(withWork).isNotEmpty()
        assertThat(withWork.map { snap -> snap.items.filterIsInstance<ActivityGroup>().single().thoughts.map { it.text } })
            .containsExactlyElementsIn(List(withWork.size) { listOf("Reading the code.") })
            .inOrder()

        subscription.cancel()
        hubScope.cancel()
    }

    private fun tool(id: String, name: String, status: String, path: String) = RunStreamEvent.ToolCall(
        SseToolCallDto(callId = id, name = name, status = status, args = buildJsonObject { put("path", JsonPrimitive(path)) }),
    )
}
