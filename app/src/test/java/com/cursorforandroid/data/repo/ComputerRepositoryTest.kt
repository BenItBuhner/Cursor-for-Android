package com.cursorforandroid.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.ControllerIdentity
import com.cursorforandroid.data.api.RemoteAgentHostApi
import com.cursorforandroid.data.local.SecureKeyStore
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.Computer
import com.cursorforandroid.domain.ComputerPairing
import com.cursorforandroid.domain.ComputerPresence
import com.cursorforandroid.domain.ControllerTrust
import com.cursorforandroid.domain.LocalAgentLineKind
import com.cursorforandroid.domain.LocalAgentSession
import com.cursorforandroid.domain.LocalSessionEvent
import com.cursorforandroid.domain.LocalWorkspace
import com.cursorforandroid.domain.PairingChallenge
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Oct 6 computers stay behind [Capabilities.computerControl]; SDK-only never calls api2. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ComputerRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `SDK-only never lists computers or touches the host API`() = runBlocking<Unit> {
        val host = FakeHost()
        val repo = repo(host, Capabilities.DOCUMENTED)
        repo.refreshList()
        assertThat(host.calls).isEmpty()
        assertThat(repo.list.value.allowed).isFalse()
        assertThat(repo.list.value.computers).isEmpty()
        assertThat(repo.list.value.error).isEqualTo(ComputerRepository.NEEDS_EXTENDED_MODE)
        repo.refreshDetail("desk-1")
        assertThat(host.calls).isEmpty()
        assertThat(repo.detail("desk-1").value.error).isEqualTo(ComputerRepository.NEEDS_EXTENDED_MODE)
        repo.requestPairing("desk-1")
        assertThat(host.calls).isEmpty()
        assertThat(repo.reply("desk-1", "s1", "hi").isFailure).isTrue()
        repo.watchInbox("desk-1")
        repo.attach("desk-1", "sess-1")
        assertThat(host.calls).isEmpty()
    }

    @Test
    fun `the demo never lists computers`() = runBlocking<Unit> {
        val host = FakeHost()
        val repo = repo(host, Capabilities.EXTENDED, demo = true)
        repo.refreshList()
        assertThat(host.calls).isEmpty()
        assertThat(repo.list.value.error).isEqualTo(ComputerRepository.NOT_IN_DEMO)
    }

    @Test
    fun `Extended lists computers, pairs, and only loads the inbox once trusted and online`() = runBlocking<Unit> {
        val host = FakeHost()
        host.computers = listOf(
            Computer("desk-1", "Bennett's Mac", presence = ComputerPresence.ONLINE, pairing = ComputerPairing.UNPAIRED),
        )
        host.challenge = PairingChallenge(ControllerTrust.PENDING, "thumb", "1234-5678", "ch-1")
        host.sessions = listOf(LocalAgentSession("sess-1", "Fix the nav", status = com.cursorforandroid.domain.LocalAgentStatus.RUNNING))
        val repo = repo(host, Capabilities.EXTENDED)

        repo.refreshList()
        assertThat(repo.list.value.allowed).isTrue()
        assertThat(repo.list.value.computers.map { it.targetId }).containsExactly("desk-1")
        assertThat(host.calls.any { it.startsWith("list:") }).isTrue()

        repo.refreshDetail("desk-1")
        assertThat(host.calls).contains("trust:desk-1")
        assertThat(host.calls.none { it.startsWith("sessions:") }).isTrue()
        assertThat(repo.detail("desk-1").value.challenge?.verificationCode).isEqualTo("1234-5678")

        host.challenge = PairingChallenge(ControllerTrust.TRUSTED, "thumb", "1234-5678", "ch-1")
        repo.refreshDetail("desk-1")
        assertThat(host.calls).contains("sessions:desk-1")
        assertThat(repo.detail("desk-1").value.sessions.map { it.sessionId }).containsExactly("sess-1")

        assertThat(repo.reply("desk-1", "sess-1", "keep going").isSuccess).isTrue()
        assertThat(host.sent).containsExactly(Triple("desk-1", "sess-1", "keep going"))
        assertThat(repo.start("desk-1", "new task", listOf("/workspace")).isSuccess).isTrue()
        assertThat(host.created).hasSize(1)
        assertThat(host.created[0].third).containsExactly("/workspace")
        assertThat(repo.detail("desk-1").value.notice).isEqualTo(ComputerRepository.SENT)
    }

    @Test
    fun `a missing presence RPC is reported as the endpoint missing, not a fake pairing list`() = runBlocking<Unit> {
        val host = FakeHost()
        host.failList = ConnectRpcException(404, "unimplemented", "not found", path = "/aiserver.v1.RemoteAgentHostPresenceService/ListSharedTargets")
        val repo = repo(host, Capabilities.EXTENDED)
        repo.refreshList()
        assertThat(repo.list.value.computers).isEmpty()
        assertThat(repo.list.value.error).isEqualTo(ComputerRepository.ENDPOINT_MISSING)
    }

    @Test
    fun `Extended watch and attach update the inbox and transcript, and a missing stream is honest`() = runBlocking<Unit> {
        val host = FakeHost()
        host.sessions = listOf(LocalAgentSession("sess-1", "Fix the nav"))
        host.attachEvents = listOf(
            LocalSessionEvent.UserSaid("Fix the nav", 1),
            LocalSessionEvent.AssistantDelta("Looking at NavStack.kt", false, 2),
        )
        val repo = repo(host, Capabilities.EXTENDED)
        val watch = launch { repo.watchInbox("desk-1") }
        withTimeout(5_000) {
            while (repo.detail("desk-1").value.sessions.isEmpty()) kotlinx.coroutines.yield()
        }
        watch.cancel()
        assertThat(host.calls).contains("watch:desk-1")
        assertThat(repo.detail("desk-1").value.sessions.map { it.sessionId }).containsExactly("sess-1")

        val attached = launch { repo.attach("desk-1", "sess-1") }
        withTimeout(5_000) {
            while (repo.detail("desk-1").value.transcript.size < 2) kotlinx.coroutines.yield()
        }
        attached.cancel()
        assertThat(host.calls).contains("attach:desk-1:sess-1")
        assertThat(repo.detail("desk-1").value.transcript.map { it.kind to it.text }).containsExactly(
            LocalAgentLineKind.USER to "Fix the nav",
            LocalAgentLineKind.ASSISTANT to "Looking at NavStack.kt",
        ).inOrder()

        val missing = FakeHost()
        missing.watchFail = ConnectRpcException(404, "unimplemented", "not found", path = "/aiserver.v1.RemoteAgentHostPresenceService/StreamAgentHost")
        missing.attachFail = missing.watchFail
        val repoMissing = repo(missing, Capabilities.EXTENDED)
        repoMissing.watchInbox("desk-1")
        assertThat(repoMissing.detail("desk-1").value.watchError).isEqualTo(ComputerRepository.STREAM_MISSING)
        assertThat(repoMissing.detail("desk-1").value.sessions).isEmpty()
        repoMissing.attach("desk-1", "sess-1")
        assertThat(repoMissing.detail("desk-1").value.attachError).isEqualTo(ComputerRepository.ATTACH_MISSING)
    }

    private fun repo(host: FakeHost, capabilities: Capabilities, demo: Boolean = false) = ComputerRepository(
        api = { host },
        identity = ControllerIdentity(
            SecureKeyStore(context) { context.getSharedPreferences("ctrl-${host.hashCode()}-${capabilities.hashCode()}-$demo", Context.MODE_PRIVATE) },
            "Pixel 9",
        ),
        capabilities = { capabilities },
        isDemo = { demo },
    )

    private class FakeHost : RemoteAgentHostApi {
        var computers: List<Computer> = emptyList()
        var challenge = PairingChallenge(ControllerTrust.NONE, "thumb", "0000-0000")
        var sessions: List<LocalAgentSession> = emptyList()
        var workspaces: List<LocalWorkspace> = emptyList()
        var failList: Throwable? = null
        var watchFail: Throwable? = null
        var attachFail: Throwable? = null
        var attachEvents: List<LocalSessionEvent> = emptyList()
        val calls = mutableListOf<String>()
        val sent = mutableListOf<Triple<String, String, String>>()
        val created = mutableListOf<Triple<String, String, List<String>>>()

        override suspend fun listComputers(controllerThumbprint: String): List<Computer> {
            calls += "list:$controllerThumbprint"
            failList?.let { throw it }
            return computers
        }

        override suspend fun trustController(targetId: String, publicKeyJwk: String, label: String): PairingChallenge {
            calls += "pair:$targetId:$label"
            return challenge
        }

        override suspend fun controllerTrust(targetId: String, publicKeyJwk: String): PairingChallenge {
            calls += "trust:$targetId"
            return challenge
        }

        override suspend fun listSessions(targetId: String): List<LocalAgentSession> {
            calls += "sessions:$targetId"
            return sessions
        }

        override suspend fun listWorkspaces(targetId: String): List<LocalWorkspace> {
            calls += "workspaces:$targetId"
            return workspaces
        }

        override suspend fun sendMessage(targetId: String, sessionId: String, text: String): String? {
            calls += "send:$targetId:$sessionId"
            sent += Triple(targetId, sessionId, text)
            return "turn-1"
        }

        override suspend fun createSession(targetId: String, clientInstanceId: String, workspacePaths: List<String>): String {
            calls += "create:$targetId"
            val id = "sess-new"
            created += Triple(targetId, clientInstanceId, workspacePaths)
            return id
        }

        override suspend fun watchSessions(targetId: String, onSessions: (List<LocalAgentSession>) -> Boolean) {
            calls += "watch:$targetId"
            watchFail?.let { throw it }
            onSessions(sessions)
            awaitCancellation()
        }

        override suspend fun attachSession(
            targetId: String,
            sessionId: String,
            lastEventId: Long?,
            clientInstanceId: String,
            onEvent: (LocalSessionEvent) -> Boolean,
        ) {
            calls += "attach:$targetId:$sessionId"
            attachFail?.let { throw it }
            attachEvents.forEach { onEvent(it) }
            awaitCancellation()
        }

        override suspend fun getSessionBlobs(targetId: String, sessionId: String, blobIds: List<String>): Map<String, ByteArray> {
            calls += "blobs:$targetId:$sessionId"
            return emptyMap()
        }
    }
}
