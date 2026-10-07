package com.cursorforandroid.domain

/**
 * One computer enrolled for Oct 6 remote control of **local** agents — `aiserver.v1.SharedRemoteAgentHostTarget`
 * on `RemoteAgentHostPresenceService`. This is not a My Machines worker (`env.type: machine|pool`): the agent loop
 * stays on the desktop. Pairing is the phone as a controller (`TrustController`); approve happens in the Cursor
 * desktop app, not here.
 */
data class Computer(
    val targetId: String,
    val displayName: String,
    val agentHostVersion: String? = null,
    val presence: ComputerPresence = ComputerPresence.UNKNOWN,
    val pairing: ComputerPairing = ComputerPairing.UNKNOWN,
    val enrollmentGeneration: Int? = null,
)

enum class ComputerPresence(val number: Int) {
    UNKNOWN(0),
    ONLINE(1),
    OFFLINE(2),
    ;

    companion object {
        const val WIRE_PREFIX = "REMOTE_AGENT_HOST_TARGET_PRESENCE_"

        fun parse(raw: String?): ComputerPresence {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return UNKNOWN
            token.toIntOrNull()?.let { number -> return entries.firstOrNull { it.number == number } ?: UNKNOWN }
            val name = token.uppercase().removePrefix(WIRE_PREFIX)
            return entries.firstOrNull { it.name == name } ?: UNKNOWN
        }
    }
}

enum class ComputerPairing(val number: Int) {
    UNKNOWN(0),
    PAIRED(1),
    PENDING(2),
    UNPAIRED(3),
    REVOKED(4),
    ;

    val canInbox: Boolean get() = this == PAIRED
    val canRequest: Boolean get() = this == UNPAIRED || this == REVOKED || this == UNKNOWN

    companion object {
        const val WIRE_PREFIX = "REMOTE_AGENT_HOST_CONTROLLER_PAIRING_"

        fun parse(raw: String?): ComputerPairing {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return UNKNOWN
            token.toIntOrNull()?.let { number -> return entries.firstOrNull { it.number == number } ?: UNKNOWN }
            val name = token.uppercase().removePrefix(WIRE_PREFIX)
            return entries.firstOrNull { it.name == name } ?: UNKNOWN
        }
    }
}

enum class ControllerTrust(val number: Int) {
    UNKNOWN(0),
    PENDING(1),
    TRUSTED(2),
    REVOKED(3),
    REJECTED(4),
    NONE(5),
    ;

    val awaitingDesktop: Boolean get() = this == PENDING
    val paired: Boolean get() = this == TRUSTED

    companion object {
        const val WIRE_PREFIX = "CONTROLLER_TRUST_STATUS_"

        fun parse(raw: String?): ControllerTrust {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return UNKNOWN
            token.toIntOrNull()?.let { number -> return entries.firstOrNull { it.number == number } ?: UNKNOWN }
            val name = token.uppercase().removePrefix(WIRE_PREFIX)
            return entries.firstOrNull { it.name == name } ?: UNKNOWN
        }
    }
}

/** A pairing request this phone started, waiting on desktop approve. */
data class PairingChallenge(
    val status: ControllerTrust,
    val thumbprint: String,
    val verificationCode: String,
    val challengeId: String? = null,
)

enum class LocalAgentStatus(val number: Int) {
    UNKNOWN(0),
    IDLE(1),
    RUNNING(2),
    AWAITING_INPUT(3),
    ;

    companion object {
        const val WIRE_PREFIX = "AGENT_HOST_SESSION_STATUS_"

        fun parse(raw: String?): LocalAgentStatus {
            val token = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return UNKNOWN
            token.toIntOrNull()?.let { number -> return entries.firstOrNull { it.number == number } ?: UNKNOWN }
            val name = token.uppercase().removePrefix(WIRE_PREFIX)
            return entries.firstOrNull { it.name == name } ?: UNKNOWN
        }
    }
}

/**
 * One in-place local agent on a paired computer (`agent.v1.AgentHostSession` via `CallAgentHost` LIST_SESSIONS
 * or `StreamAgentHost` WATCH_SESSIONS). [sessionId] is the desktop's id, not a cloud `bc-…` composer.
 */
data class LocalAgentSession(
    val sessionId: String,
    val title: String,
    val status: LocalAgentStatus = LocalAgentStatus.UNKNOWN,
    val workspace: LocalWorkspace? = null,
    val runningTurnId: String? = null,
    val lastEventId: Long? = null,
)

data class LocalWorkspace(
    val displayPath: String,
    val workspacePaths: List<String> = emptyList(),
    val worktreeMainPath: String? = null,
)

/** One visible line of a local agent's transcript on the paired computer — never a cloud `bc-…` chat. */
data class LocalAgentLine(
    val kind: LocalAgentLineKind,
    val text: String,
)

enum class LocalAgentLineKind { USER, ASSISTANT, NOTICE, STATUS }

/**
 * A decoded `agent.v1.AgentHostSessionEvent` from `StreamAgentHost` ATTACH_SESSION. Keepalives carry no text;
 * [History] names conversation-state turn blobs for `GET_SESSION_BLOBS`.
 */
sealed interface LocalSessionEvent {
    val eventId: Long?

    data class Keepalive(override val eventId: Long? = null) : LocalSessionEvent
    data class History(val turnBlobIds: List<String>, override val eventId: Long?) : LocalSessionEvent
    data class AssistantDelta(val text: String, val notice: Boolean, override val eventId: Long?) : LocalSessionEvent
    data class UserSaid(val text: String, override val eventId: Long?) : LocalSessionEvent
    data class Working(override val eventId: Long?) : LocalSessionEvent
    data class Waiting(override val eventId: Long?) : LocalSessionEvent
    data class Settled(val detail: String?, override val eventId: Long?) : LocalSessionEvent
}
