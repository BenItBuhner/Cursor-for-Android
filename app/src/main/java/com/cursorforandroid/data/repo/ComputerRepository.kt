package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.ConnectRpcException
import com.cursorforandroid.data.api.ControllerIdentity
import com.cursorforandroid.data.api.RemoteAgentHostApi
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.domain.Capabilities
import com.cursorforandroid.domain.Computer
import com.cursorforandroid.domain.ComputerPairing
import com.cursorforandroid.domain.ComputerPresence
import com.cursorforandroid.domain.ControllerTrust
import com.cursorforandroid.domain.LocalAgentSession
import com.cursorforandroid.domain.LocalWorkspace
import com.cursorforandroid.domain.PairingChallenge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/**
 * Oct 6 phone-to-computer remote control, gated on [Capabilities.computerControl]. With the surface off this
 * repository never touches api2: the screen shows [NEEDS_EXTENDED_MODE] and the documented My Machines path stays
 * on the New Chat device picker. Local agent ids are desktop session ids, never opened as cloud `bc-…` chats.
 */
class ComputerRepository(
    private val api: () -> RemoteAgentHostApi,
    private val identity: ControllerIdentity,
    private val capabilities: suspend () -> Capabilities,
    private val isDemo: () -> Boolean = { false },
) {
    data class ListState(
        val allowed: Boolean = false,
        val computers: List<Computer> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    data class DetailState(
        val allowed: Boolean = false,
        val computer: Computer? = null,
        val challenge: PairingChallenge? = null,
        val sessions: List<LocalAgentSession> = emptyList(),
        val workspaces: List<LocalWorkspace> = emptyList(),
        val loading: Boolean = false,
        val sending: Boolean = false,
        val error: String? = null,
        val notice: String? = null,
    )

    private val listFlow = MutableStateFlow(ListState())
    val list: StateFlow<ListState> = listFlow.asStateFlow()

    private val details = mutableMapOf<String, MutableStateFlow<DetailState>>()

    fun detail(targetId: String): StateFlow<DetailState> = detailFlow(targetId).asStateFlow()

    fun reset() {
        listFlow.value = ListState()
        details.values.forEach { it.value = DetailState() }
    }

    suspend fun refreshList() {
        if (isDemo()) {
            listFlow.value = ListState(allowed = false, error = NOT_IN_DEMO)
            return
        }
        if (!capabilities().computerControl) {
            listFlow.value = ListState(allowed = false, error = NEEDS_EXTENDED_MODE)
            return
        }
        listFlow.update { it.copy(allowed = true, loading = true, error = null) }
        try {
            val me = identity.loadOrCreate()
            val computers = api().listComputers(me.thumbprint)
            listFlow.update { it.copy(computers = computers, loading = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            listFlow.update { it.copy(loading = false, error = explain(e)) }
        }
    }

    suspend fun refreshDetail(targetId: String) {
        val flow = detailFlow(targetId)
        if (isDemo()) {
            flow.value = DetailState(allowed = false, error = NOT_IN_DEMO)
            return
        }
        if (!capabilities().computerControl) {
            flow.value = DetailState(allowed = false, error = NEEDS_EXTENDED_MODE)
            return
        }
        flow.update { it.copy(allowed = true, loading = true, error = null, notice = null) }
        try {
            val me = identity.loadOrCreate()
            val host = api()
            val listed = listFlow.value.computers.firstOrNull { it.targetId == targetId }
                ?: host.listComputers(me.thumbprint).firstOrNull { it.targetId == targetId }
            val computer = listed ?: Computer(targetId = targetId, displayName = "Computer")
            val challenge = host.controllerTrust(targetId, me.publicKeyJwk)
            var sessions = emptyList<LocalAgentSession>()
            var workspaces = emptyList<LocalWorkspace>()
            if (challenge.status.paired && computer.presence == ComputerPresence.ONLINE) {
                sessions = host.listSessions(targetId)
                workspaces = runCatching { host.listWorkspaces(targetId) }.getOrDefault(emptyList())
            }
            flow.update {
                it.copy(
                    computer = computer.copy(
                        pairing = when (challenge.status) {
                            ControllerTrust.TRUSTED -> ComputerPairing.PAIRED
                            ControllerTrust.PENDING -> ComputerPairing.PENDING
                            ControllerTrust.REVOKED -> ComputerPairing.REVOKED
                            ControllerTrust.NONE, ControllerTrust.REJECTED, ControllerTrust.UNKNOWN ->
                                computer.pairing.takeUnless { p -> p == ComputerPairing.PAIRED } ?: ComputerPairing.UNPAIRED
                        },
                    ),
                    challenge = challenge,
                    sessions = sessions,
                    workspaces = workspaces,
                    loading = false,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            flow.update { it.copy(loading = false, error = explain(e)) }
        }
    }

    /** Ask the desktop to approve this phone. The user confirms in Cursor on the computer, not here. */
    suspend fun requestPairing(targetId: String) {
        val flow = detailFlow(targetId)
        if (!capabilities().computerControl) {
            flow.update { it.copy(allowed = false, error = NEEDS_EXTENDED_MODE) }
            return
        }
        flow.update { it.copy(loading = true, error = null, notice = null) }
        try {
            val me = identity.loadOrCreate()
            val challenge = api().trustController(targetId, me.publicKeyJwk, me.label)
            flow.update { it.copy(challenge = challenge, loading = false) }
            refreshDetail(targetId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            flow.update { it.copy(loading = false, error = explain(e)) }
        }
    }

    suspend fun reply(targetId: String, sessionId: String, text: String): Result<Unit> = send(targetId) { host, _ ->
        host.sendMessage(targetId, sessionId, text.trim())
        refreshDetail(targetId)
    }

    suspend fun start(targetId: String, text: String, workspacePaths: List<String>): Result<Unit> = send(targetId) { host, me ->
        val sessionId = host.createSession(targetId, me.clientInstanceId, workspacePaths)
        host.sendMessage(targetId, sessionId, text.trim())
        refreshDetail(targetId)
    }

    private suspend fun send(targetId: String, block: suspend (RemoteAgentHostApi, ControllerIdentity.Public) -> Unit): Result<Unit> {
        val flow = detailFlow(targetId)
        if (!capabilities().computerControl) return Result.failure(IllegalStateException(NEEDS_EXTENDED_MODE))
        flow.update { it.copy(sending = true, error = null, notice = null) }
        return try {
            val me = identity.loadOrCreate()
            block(api(), me)
            flow.update { it.copy(sending = false, notice = SENT) }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val message = explain(e)
            flow.update { it.copy(sending = false, error = message) }
            Result.failure(IllegalStateException(message, e))
        }
    }

    private fun detailFlow(targetId: String): MutableStateFlow<DetailState> =
        details.getOrPut(targetId) { MutableStateFlow(DetailState()) }

    private fun explain(error: Throwable): String = when (error) {
        is SessionUnavailableException ->
            if (error.code == SessionUnavailableException.EXTENDED_MODE_OFF) NEEDS_EXTENDED_MODE
            else error.message ?: NO_SESSION
        is ConnectRpcException -> when {
            error.code == "unimplemented" || error.httpCode == 404 -> ENDPOINT_MISSING
            error.isUnreadableAnswer -> ENDPOINT_CHANGED
            else -> error.message ?: ENDPOINT_CHANGED
        }
        is IOException -> error.message ?: "Couldn't reach Cursor."
        else -> error.message ?: ENDPOINT_CHANGED
    }

    companion object {
        const val NEEDS_EXTENDED_MODE =
            "Needs Extended mode: listing computers and pairing with desktop Cursor uses Cursor's private Remote Control API, which is not part of the documented Cloud Agents API."
        const val NOT_IN_DEMO = "The demo has no computers to pair."
        const val NO_SESSION = "Not signed in."
        const val ENDPOINT_MISSING =
            "This account's Cursor service does not offer computer pairing yet (the Oct 6 Remote Control RPC is missing)."
        const val ENDPOINT_CHANGED = "Cursor's computer-pairing API changed underneath this build."
        const val SENT = "Sent to the computer."
    }
}
