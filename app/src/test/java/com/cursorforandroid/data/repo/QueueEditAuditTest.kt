package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.AgentListCache
import com.cursorforandroid.data.local.AttachmentStore
import com.cursorforandroid.data.local.FollowUpStore
import com.cursorforandroid.data.local.JsonDiskCache
import com.cursorforandroid.data.local.PreferencesStore
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.FollowUpDraft
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Audit probe (prints `AUDIT queue-edit …`, asserts nothing): what "edit a queued message while the composer holds
 * text" does to the displaced draft that takes the edited message's place in line.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QueueEditAuditTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = 1_800_000_000_000L
    private lateinit var context: Context
    private lateinit var agents: AgentRepository
    private lateinit var hub: LiveRunHub
    private lateinit var conversations: ConversationRepository
    private lateinit var store: FollowUpStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = PreferencesStore(context)
        val session = SessionManager(SecureKeyStore(context), prefs, CursorBackend(api, streamer, isDemo = false), CursorBackend(api, streamer, isDemo = true))
        val disk = JsonDiskCache(folder.newFolder("cache"), dispatcher = Dispatchers.Unconfined)
        val attachments = AttachmentStore(context)
        agents = AgentRepository(session, prefs, attachments, AgentListCache(disk.child("agents")), scope, persistDelayMs = 10)
        hub = LiveRunHub(session, agents, nowProvider = { now }, pollIntervalMs = 50, releaseGraceMs = 50, reconnectBaseMs = 20, reconnectMaxMs = 40, scope = scope)
        conversations = ConversationRepository(session, agents, prefs, hub, attachments, isForeground = { true }, prefetchLimit = 0, scope = scope)
        store = FollowUpStore(context)
        AppClock.nowMillis = { now }
    }

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { store.clear() }
        AppClock.nowMillis = System::currentTimeMillis
    }

    private fun repository(persist: Boolean = false) = FollowUpRepository(
        conversations, agents, hub, mcpServers = { emptyList() }, store = store.takeIf { persist }, persist = { true },
        scope = scope, draftSaveDelayMs = 10, idleSettleMs = 20_000, retryBaseMs = 20,
    )

    private suspend fun awaitUntil(timeoutMs: Long = 20_000, condition: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!condition()) delay(10)
    }

    private fun QueuedFollowUp.describe() =
        "text='$text' held=$isHeld busyRefusals=$busyRefusals heldSince=${heldSinceMillis?.let { it - now }} notBefore=${notBeforeMillis?.let { it - now }} " +
            "needsConfirmation=$needsConfirmation warning=${warning?.take(24)} modelId=$modelId planMode=$planMode error=$error"

    @Test
    fun `displaced draft inherits a held message's wait and model`() = runBlocking<Unit> {
        api.addRunningAgent("bc-1", "Agent", "run-1")
        agents.refresh()
        val followUps = repository()
        val held = followUps.enqueue("bc-1", "Queued on GPT", modelId = "gpt-5", modelDisplayName = "GPT-5", planMode = true, refusedAsBusy = true)
        followUps.setDraftText("bc-1", "Fresh words typed on the chat's own model")
        followUps.takeForEdit("bc-1", held.id)
        val displaced = followUps.state("bc-1").value.queue.single()
        println("AUDIT queue-edit held  -> displaced ${displaced.describe()}")
        println("AUDIT queue-edit held  -> draft now text='${followUps.state("bc-1").value.draft.text}' model=${followUps.state("bc-1").value.draft.model} mode=${followUps.state("bc-1").value.draft.mode}")
    }

    @Test
    fun `displaced draft behind a failed head is never sent`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        val followUps = repository()
        api.failCreateRun = true
        val failed = followUps.enqueue("bc-1", "Fails once")
        awaitUntil { followUps.state("bc-1").value.queue.singleOrNull()?.error != null }
        api.failCreateRun = false
        val before = api.runRequests.size
        followUps.setDraftText("bc-1", "Fresh words")
        followUps.takeForEdit("bc-1", failed.id)
        val displaced = followUps.state("bc-1").value.queue.single()
        println("AUDIT queue-edit failed -> displaced ${displaced.describe()} dispatcherActive=${FollowUpRepositoryTestHelper.dispatcherActive(followUps, "bc-1")}")
        delay(3_000)
        println("AUDIT queue-edit failed -> after 3s: requests since=${api.runRequests.drop(before).map { it.prompt.text }} queue=${followUps.state("bc-1").value.queue.map { it.text }} dispatcherActive=${FollowUpRepositoryTestHelper.dispatcherActive(followUps, "bc-1")}")
    }

    @Test
    fun `a held message being retried shows its glyphs but takes no tap`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        api.busyCreateRun = true
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.createRunGate = gate
        val followUps = repository()
        val item = followUps.enqueue("bc-1", "Waiting on a slow server", refusedAsBusy = true)
        awaitUntil { followUps.state("bc-1").value.queue.singleOrNull()?.isSending == true }
        val inFlight = followUps.state("bc-1").value.queue.single()
        val ringShown = inFlight.isSending && !inFlight.isHeld
        println("AUDIT queue-edit retrying -> isSending=${inFlight.isSending} isHeld=${inFlight.isHeld} card shows ${if (ringShown) "spinner" else "trash/edit/up glyphs"}")
        followUps.remove("bc-1", item.id)
        val afterRemove = followUps.state("bc-1").value.queue.map { it.text }
        val taken = followUps.takeForEdit("bc-1", item.id)
        println("AUDIT queue-edit retrying -> after trash tap queue=$afterRemove; after edit tap taken=${taken?.text} draft='${followUps.state("bc-1").value.draft.text}'")
        api.busyCreateRun = false
        gate.complete(Unit)
        api.createRunGate = null
        awaitUntil { followUps.state("bc-1").value.queue.isEmpty() || followUps.state("bc-1").value.queue.single().busyRefusals > 1 }
        delay(500)
        println("AUDIT queue-edit retrying -> once answered: requests=${api.runRequests.map { it.prompt.text }} queue=${followUps.state("bc-1").value.queue.map { it.text }}")
    }

    @Test
    fun `displaced draft inherits may-have-been-sent`() = runBlocking<Unit> {
        api.addIdleAgent("bc-1", "Agent", "run-0")
        agents.refresh()
        val orphan = QueuedFollowUp(id = "queued-orphan", text = "Sent just before the process died", queuedAtMillis = now - 60_000, sendStartedAtMillis = now - 55_000)
        store.write("bc-1", FollowUpDraft.EMPTY, listOf(orphan))
        val followUps = repository(persist = true)
        followUps.state("bc-1").first { it.restored }
        awaitUntil { followUps.state("bc-1").value.queue.singleOrNull()?.needsConfirmation == true }
        followUps.setDraftText("bc-1", "A brand-new message")
        followUps.takeForEdit("bc-1", orphan.id)
        val displaced = followUps.state("bc-1").value.queue.single()
        println("AUDIT queue-edit orphan -> displaced ${displaced.describe()} sendStartedAt=${displaced.sendStartedAtMillis?.let { it - now }}")
        delay(1_500)
        println("AUDIT queue-edit orphan -> after 1.5s requests=${api.runRequests.map { it.prompt.text }}")
    }
}
