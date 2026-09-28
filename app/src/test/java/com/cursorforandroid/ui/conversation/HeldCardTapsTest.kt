package com.cursorforandroid.ui.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.FakeCursorApi
import com.cursorforandroid.data.FakeRunStreamer
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.data.repo.CursorBackend
import com.cursorforandroid.data.repo.FollowUpRepository
import com.cursorforandroid.domain.QueuedFollowUp
import com.cursorforandroid.util.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A waiting card's remove, edit and up arrow while its held message's retry is on the wire (COMP-1): the card reads
 * as waiting, but the request is out and cannot be called back. Each tap is refused and the snackbar says why —
 * never taken silently, the message then reaching the agent the reader thought they had deleted. Nothing is resent:
 * once the server has refused the attempt, the delete goes through and the message is never sent.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HeldCardTapsTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val api = FakeCursorApi()
    private val streamer = FakeRunStreamer()
    private lateinit var graph: AppGraph

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        api.addIdleAgent(AGENT, "Speed up the cold start", "run-0")
        graph = AppGraph(
            context,
            SecureKeyStore(context) { context.getSharedPreferences("held-card-taps", Context.MODE_PRIVATE) },
            demo = CursorBackend(api, streamer, isDemo = true),
        )
        graph.session.enterDemo()
        graph.agents.refresh()
    }

    @After
    fun tearDown() {
        api.createRunGate?.complete(Unit)
        graph.followUps.resetAll()
    }

    private fun head(): QueuedFollowUp? = graph.followUps.state(AGENT).value.queue.firstOrNull()

    private fun awaitUntil(condition: () -> Boolean) = runBlocking { withTimeout(20_000) { while (!condition()) delay(10) } }

    @Test
    fun `taps on a held card whose retry is out are refused and say why, and a delete after the refusal means it is never sent`() {
        val vm = ConversationViewModel(graph, AGENT)
        api.busyCreateRun = true
        val item = graph.followUps.enqueue(AGENT, "Now run the tests")
        awaitUntil { head()?.let { it.busyRefusals == 1 && !it.isSending } == true }
        val gate = CompletableDeferred<Unit>()
        api.createRunGate = gate
        awaitUntil { head()?.isSending == true }
        assertThat(head()!!.isHeld).isTrue()

        assertThat(vm.removeQueued(item.id)).isFalse()
        assertThat(vm.toastMessage.value).isEqualTo(FollowUpRepository.ON_ITS_WAY)
        vm.clearToast()
        assertThat(vm.editQueued(item.id)).isFalse()
        assertThat(vm.toastMessage.value).isEqualTo(FollowUpRepository.ON_ITS_WAY)
        assertThat(vm.draftText.value).isEmpty()
        vm.clearToast()
        assertThat(vm.steerQueued(item.id, turnUnderWay = false)).isFalse()
        assertThat(vm.toastMessage.value).isEqualTo(FollowUpRepository.ON_ITS_WAY)
        vm.clearToast()
        assertThat(graph.followUps.state(AGENT).value.queue.map { it.id }).containsExactly(item.id)

        // The server refuses the attempt: the card is the reader's again, and a delete now takes it for good.
        gate.complete(Unit)
        awaitUntil { head()?.let { it.busyRefusals == 2 && !it.isSending } == true }
        assertThat(vm.removeQueued(item.id)).isTrue()
        assertThat(vm.toastMessage.value).isNull()
        assertThat(graph.followUps.state(AGENT).value.queue).isEmpty()

        api.busyCreateRun = false
        runBlocking { delay(2_500) }
        assertThat(api.runRequests).hasSize(2)
        assertThat(api.runs.keys.none { it.startsWith("run-followup") }).isTrue()
    }

    @Test
    fun `a removal of a message already gone says nothing`() {
        val vm = ConversationViewModel(graph, AGENT)
        assertThat(vm.removeQueued("queued-gone")).isFalse()
        assertThat(vm.editQueued("queued-gone")).isFalse()
        assertThat(vm.toastMessage.value).isNull()
    }

    private companion object {
        const val AGENT = "bc-held-taps"
    }
}
