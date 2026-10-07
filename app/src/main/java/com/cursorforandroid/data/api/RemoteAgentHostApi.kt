package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.proto.AgentHostSchemas
import com.cursorforandroid.data.api.proto.ProtoWire
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.Computer
import com.cursorforandroid.domain.ComputerPairing
import com.cursorforandroid.domain.ComputerPresence
import com.cursorforandroid.domain.ControllerTrust
import com.cursorforandroid.domain.LocalAgentSession
import com.cursorforandroid.domain.LocalAgentStatus
import com.cursorforandroid.domain.LocalSessionEvent
import com.cursorforandroid.domain.LocalWorkspace
import com.cursorforandroid.domain.PairingChallenge
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID

/**
 * The phone side of Oct 6 remote control: `aiserver.v1.RemoteAgentHostPresenceService` on api2. Documented Cloud
 * Agents v1 has none of this — SDK-only never constructs the implementation.
 *
 * Inner agent-host calls wrap protobuf `data` of `agent.v1.AgentHostService`: unary `CallAgentHost` for list / send /
 * create / workspaces / blobs, and server-streaming `StreamAgentHost` for `WATCH_SESSIONS` and `ATTACH_SESSION`.
 * The Connect JSON body uses the same session token and headers as every other account RPC; no iOS client identity
 * is faked.
 */
interface RemoteAgentHostApi {
    suspend fun listComputers(controllerThumbprint: String): List<Computer>

    suspend fun trustController(targetId: String, publicKeyJwk: String, label: String): PairingChallenge

    suspend fun controllerTrust(targetId: String, publicKeyJwk: String): PairingChallenge

    suspend fun listSessions(targetId: String): List<LocalAgentSession>

    suspend fun listWorkspaces(targetId: String): List<LocalWorkspace>

    suspend fun sendMessage(targetId: String, sessionId: String, text: String): String?

    suspend fun createSession(targetId: String, clientInstanceId: String, workspacePaths: List<String>): String

    /**
     * Live inbox. [onSessions] is the current session list after each snapshot/add/update/delete; returning false
     * closes the stream. Keepalives are swallowed. A missing RPC throws [ConnectRpcException] (404 / unimplemented).
     */
    suspend fun watchSessions(targetId: String, onSessions: (List<LocalAgentSession>) -> Boolean)

    /**
     * Live transcript of one local session. [onEvent] returning false closes the stream. [lastEventId] resumes after
     * a drop; omit it on the first attach so the host sends `conversation_state`.
     */
    suspend fun attachSession(
        targetId: String,
        sessionId: String,
        lastEventId: Long?,
        clientInstanceId: String,
        onEvent: (LocalSessionEvent) -> Boolean,
    )

    /** Historical turn / step blobs named by an ATTACH `conversation_state`. Keys are the proto3-JSON blob ids. */
    suspend fun getSessionBlobs(targetId: String, sessionId: String, blobIds: List<String>): Map<String, ByteArray>
}

class ConnectRemoteAgentHostApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : RemoteAgentHostApi {

    override suspend fun listComputers(controllerThumbprint: String): List<Computer> {
        val out = ArrayList<Computer>()
        var pageToken: String? = null
        do {
            val response = call(
                "ListSharedTargets",
                ListSharedTargetsDto(pageSize = PAGE_SIZE, pageToken = pageToken, controllerThumbprint = controllerThumbprint),
                ListSharedTargetsDto.serializer(),
                ListSharedTargetsResponseDto.serializer(),
            )
            response.targets.orEmpty().mapNotNull(::computerOf).forEach(out::add)
            pageToken = response.nextPageToken?.trim()?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return out
    }

    override suspend fun trustController(targetId: String, publicKeyJwk: String, label: String): PairingChallenge {
        val response = call(
            "TrustController",
            TrustControllerDto(publicKeyJwk = publicKeyJwk, label = label, targetId = targetId),
            TrustControllerDto.serializer(),
            TrustStatusDto.serializer(),
        )
        return challengeOf(response, publicKeyJwk)
    }

    override suspend fun controllerTrust(targetId: String, publicKeyJwk: String): PairingChallenge {
        val response = call(
            "GetControllerTrustStatus",
            GetTrustDto(publicKeyJwk = publicKeyJwk, targetId = targetId),
            GetTrustDto.serializer(),
            TrustStatusDto.serializer(),
        )
        return challengeOf(response, publicKeyJwk)
    }

    override suspend fun listSessions(targetId: String): List<LocalAgentSession> {
        val decoded = callAgentHost(targetId, METHOD_LIST_SESSIONS, ByteArray(0), AgentHostSchemas.LIST_SESSIONS_RESPONSE)
        val sessions = decoded["sessions"] as? JsonArray ?: return emptyList()
        return sessions.mapNotNull { el -> sessionOf(el as? JsonObject) }
    }

    override suspend fun listWorkspaces(targetId: String): List<LocalWorkspace> {
        val decoded = callAgentHost(targetId, METHOD_LIST_WORKSPACES, ByteArray(0), AgentHostSchemas.LIST_WORKSPACES_RESPONSE)
        val workspaces = decoded["workspaces"] as? JsonArray ?: return emptyList()
        return workspaces.mapNotNull { el -> workspaceOf(el as? JsonObject) }
    }

    override suspend fun sendMessage(targetId: String, sessionId: String, text: String): String? {
        val payload = ProtoWire.encode(
            buildJsonObject {
                put("sessionId", sessionId)
                put("text", text)
            },
            AgentHostSchemas.SEND_MESSAGE_REQUEST,
        )
        val decoded = callAgentHost(targetId, METHOD_SEND_MESSAGE, payload, AgentHostSchemas.SEND_MESSAGE_RESPONSE)
        return decoded.str("turnId")
    }

    override suspend fun createSession(targetId: String, clientInstanceId: String, workspacePaths: List<String>): String {
        val sessionId = UUID.randomUUID().toString()
        val payload = ProtoWire.encode(
            buildJsonObject {
                put("sessionId", sessionId)
                put("clientInstanceId", clientInstanceId)
                if (workspacePaths.isNotEmpty()) {
                    put(
                        "sessionOptions",
                        buildJsonObject {
                            put("workspacePaths", JsonArray(workspacePaths.map { JsonPrimitive(it) }))
                        },
                    )
                }
            },
            AgentHostSchemas.CREATE_SESSION_REQUEST,
        )
        callAgentHost(targetId, METHOD_CREATE_SESSION, payload, AgentHostSchemas.CREATE_SESSION_RESPONSE)
        return sessionId
    }

    override suspend fun watchSessions(targetId: String, onSessions: (List<LocalAgentSession>) -> Boolean) {
        val current = LinkedHashMap<String, LocalAgentSession>()
        streamAgentHost(targetId, METHOD_WATCH_SESSIONS, ByteArray(0), AgentHostSchemas.WATCH_EVENT) { event ->
            if (event.bool("keepalive") && event["snapshot"] == null && event["added"] == null &&
                event["updated"] == null && event["deleted"] == null
            ) {
                return@streamAgentHost true
            }
            (event["snapshot"] as? JsonObject)?.let { snap ->
                current.clear()
                (snap["sessions"] as? JsonArray)?.forEach { el ->
                    val session = sessionOf(el as? JsonObject) ?: return@forEach
                    current[session.sessionId] = session
                }
            }
            (event["added"] as? JsonObject)?.let { added ->
                sessionOf(added["session"] as? JsonObject)?.let { current[it.sessionId] = it }
            }
            (event["updated"] as? JsonObject)?.let { updated ->
                sessionOf(updated["session"] as? JsonObject)?.let { current[it.sessionId] = it }
            }
            (event["deleted"] as? JsonObject)?.str("sessionId")?.let { current.remove(it) }
            onSessions(current.values.toList())
        }
    }

    override suspend fun attachSession(
        targetId: String,
        sessionId: String,
        lastEventId: Long?,
        clientInstanceId: String,
        onEvent: (LocalSessionEvent) -> Boolean,
    ) {
        val payload = ProtoWire.encode(
            buildJsonObject {
                put("sessionId", sessionId)
                if (lastEventId != null && lastEventId > 0L) put("lastEventId", lastEventId.toString())
                put("clientInstanceId", clientInstanceId)
            },
            AgentHostSchemas.ATTACH_REQUEST,
        )
        streamAgentHost(targetId, METHOD_ATTACH_SESSION, payload, AgentHostSchemas.SESSION_EVENT) { event ->
            onEvent(sessionEventOf(event))
        }
    }

    override suspend fun getSessionBlobs(targetId: String, sessionId: String, blobIds: List<String>): Map<String, ByteArray> {
        if (blobIds.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, ByteArray>()
        var pending = blobIds.distinct().filter { it.isNotEmpty() }
        var rounds = 0
        while (pending.isNotEmpty() && rounds < BLOBS_ROUNDS) {
            rounds++
            val payload = ProtoWire.encode(
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("blobIds", JsonArray(pending.map { JsonPrimitive(it) }))
                },
                AgentHostSchemas.GET_BLOBS_REQUEST,
            )
            val decoded = callAgentHost(targetId, METHOD_GET_SESSION_BLOBS, payload, AgentHostSchemas.GET_BLOBS_RESPONSE)
            (decoded["blobs"] as? JsonArray)?.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val id = obj.str("blobId") ?: return@forEach
                val data = obj.str("data")?.let { encoded -> runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() } ?: return@forEach
                out[id] = data
            }
            pending = (decoded["remainingBlobIds"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.isNotEmpty() && id !in out } }
                .orEmpty()
        }
        return out
    }

    private suspend fun callAgentHost(
        targetId: String,
        method: String,
        data: ByteArray,
        schema: com.cursorforandroid.data.api.proto.ProtoWire.Schema,
    ): JsonObject {
        val response = call(
            "CallAgentHost",
            CallAgentHostDto(
                targetId = targetId,
                method = method,
                data = data.takeIf { it.isNotEmpty() }?.let { Base64.getEncoder().encodeToString(it) },
            ),
            CallAgentHostDto.serializer(),
            CallAgentHostResponseDto.serializer(),
        )
        val bytes = response.data?.takeIf { it.isNotEmpty() }?.let { encoded ->
            runCatching { Base64.getDecoder().decode(encoded) }.getOrElse {
                throw ConnectRpcException(200, ConnectRpcException.UNREADABLE_ANSWER, "Cursor's CallAgentHost answer for $method could not be read.", path = ConnectRpc.path(SERVICE, "CallAgentHost"))
            }
        } ?: ByteArray(0)
        return if (bytes.isEmpty()) JsonObject(emptyMap()) else ProtoWire.decode(bytes, schema)
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    private suspend fun streamAgentHost(
        targetId: String,
        method: String,
        data: ByteArray,
        schema: com.cursorforandroid.data.api.proto.ProtoWire.Schema,
        onPayload: (JsonObject) -> Boolean,
    ) {
        val body = CallAgentHostDto(
            targetId = targetId,
            method = method,
            data = data.takeIf { it.isNotEmpty() }?.let { Base64.getEncoder().encodeToString(it) },
        )
        rpc.serverStreamWithSession(
            SERVICE,
            "StreamAgentHost",
            tokens,
            body,
            CallAgentHostDto.serializer(),
            retryRefusals = false,
            lane = ApiThrottle.Lane.WATCH,
            silenceMs = ConversationStateReader.WATCH_SILENCE_MS,
        ) { message ->
            val encoded = (message["data"] as? JsonPrimitive)?.contentOrNull
            if (encoded.isNullOrEmpty()) return@serverStreamWithSession true
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse {
                throw ConnectRpcException(
                    200,
                    ConnectRpcException.UNREADABLE_ANSWER,
                    "Cursor's StreamAgentHost answer for $method could not be read.",
                    path = ConnectRpc.path(SERVICE, "StreamAgentHost"),
                )
            }
            if (bytes.isEmpty()) return@serverStreamWithSession true
            val decoded = ProtoWire.decode(bytes, schema)
            onPayload(decoded)
        }
    }

    private fun sessionOf(obj: JsonObject?): LocalAgentSession? {
        if (obj == null) return null
        val id = obj.str("sessionId") ?: return null
        return LocalAgentSession(
            sessionId = id,
            title = obj.str("title") ?: "Untitled agent",
            status = LocalAgentStatus.parse(obj.raw("status")),
            workspace = workspaceOf(obj["workspace"] as? JsonObject),
            runningTurnId = obj.str("runningTurnId"),
            lastEventId = obj.u64("lastEventId"),
        )
    }

    private fun sessionEventOf(event: JsonObject): LocalSessionEvent {
        val eventId = event.u64("eventId")
        if (event.bool("keepalive")) return LocalSessionEvent.Keepalive(eventId)
        val state = event["conversationState"] as? JsonObject
        if (state != null) {
            val turns = (state["turns"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.isNotEmpty() } }.orEmpty()
            return LocalSessionEvent.History(turns, eventId)
        }
        val interaction = event["interactionUpdate"] as? JsonObject
        if (interaction != null) {
            val delta = interaction["textDelta"] as? JsonObject
            val text = delta?.str("text")
            if (!text.isNullOrEmpty()) {
                return LocalSessionEvent.AssistantDelta(text, notice = delta.bool("isServerNotice"), eventId)
            }
            val user = (interaction["userMessageAppended"] as? JsonObject)?.get("userMessage") as? JsonObject
            val said = user?.str("text") ?: user?.str("richText")
            if (!said.isNullOrEmpty()) return LocalSessionEvent.UserSaid(said, eventId)
            return LocalSessionEvent.Keepalive(eventId)
        }
        if (event["turnStarted"] is JsonObject) return LocalSessionEvent.Working(eventId)
        if (event["turnAwaitingInput"] is JsonObject) return LocalSessionEvent.Waiting(eventId)
        val settled = event["turnSettled"] as? JsonObject
        if (settled != null) return LocalSessionEvent.Settled(settled.str("detail"), eventId)
        return LocalSessionEvent.Keepalive(eventId)
    }

    private fun computerOf(dto: SharedTargetDto): Computer? {
        val id = dto.targetId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Computer(
            targetId = id,
            displayName = dto.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "Computer",
            agentHostVersion = dto.agentHostVersion?.trim()?.takeIf { it.isNotEmpty() },
            presence = ComputerPresence.parse(dto.presence?.contentOrNull),
            pairing = ComputerPairing.parse(dto.controllerPairing?.contentOrNull),
            enrollmentGeneration = dto.enrollmentGeneration,
        )
    }

    private fun challengeOf(dto: TrustStatusDto, publicKeyJwk: String): PairingChallenge {
        val thumbprint = dto.thumbprint?.trim()?.takeIf { it.isNotEmpty() }
            ?: ControllerIdentity.thumbprint(dummyFromPublic(publicKeyJwk))
        val code = dto.verificationCode?.trim()?.takeIf { it.isNotEmpty() }
            ?: ControllerIdentity.verificationCode(thumbprint)
        return PairingChallenge(
            status = ControllerTrust.parse(dto.status?.contentOrNull),
            thumbprint = thumbprint,
            verificationCode = code,
            challengeId = dto.challengeId?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Last-resort thumbprint when the server omitted it: parse the public JWK we sent. [Stored.d] is unused for
     * the hash, so a placeholder is enough.
     */
    private fun dummyFromPublic(publicKeyJwk: String): ControllerIdentity.Stored {
        val obj = CursorJson.parseToJsonElement(publicKeyJwk).jsonObject
        return ControllerIdentity.Stored(
            kty = obj["kty"]?.jsonPrimitive?.content ?: "EC",
            crv = obj["crv"]?.jsonPrimitive?.content ?: "P-256",
            x = obj["x"]?.jsonPrimitive?.content.orEmpty(),
            y = obj["y"]?.jsonPrimitive?.content.orEmpty(),
            d = "unused",
            clientInstanceId = "unused",
        )
    }

    private fun workspaceOf(obj: JsonObject?): LocalWorkspace? {
        if (obj == null) return null
        val paths = (obj["workspacePaths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { p -> p.isNotEmpty() } }.orEmpty()
        val display = obj.str("displayPath") ?: obj.str("worktreeMainPath") ?: paths.firstOrNull() ?: return null
        return LocalWorkspace(
            displayPath = display,
            workspacePaths = paths,
            worktreeMainPath = obj.str("worktreeMainPath"),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.u64(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }

    private fun JsonObject.raw(key: String): String? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.intOrNull?.toString() ?: primitive.contentOrNull
    }

    @Serializable
    private data class ListSharedTargetsDto(
        val pageSize: Int,
        val pageToken: String? = null,
        val controllerThumbprint: String,
    )

    @Serializable
    private data class ListSharedTargetsResponseDto(
        val targets: List<SharedTargetDto>? = null,
        val nextPageToken: String? = null,
    )

    @Serializable
    private data class SharedTargetDto(
        val targetId: String? = null,
        val displayName: String? = null,
        val agentHostVersion: String? = null,
        val presence: JsonPrimitive? = null,
        val enrollmentGeneration: Int? = null,
        val controllerPairing: JsonPrimitive? = null,
    )

    @Serializable
    private data class TrustControllerDto(val publicKeyJwk: String, val label: String, val targetId: String)

    @Serializable
    private data class GetTrustDto(val publicKeyJwk: String, val targetId: String)

    @Serializable
    private data class TrustStatusDto(
        val status: JsonPrimitive? = null,
        val thumbprint: String? = null,
        val challengeId: String? = null,
        val verificationCode: String? = null,
    )

    @Serializable
    private data class CallAgentHostDto(val targetId: String, val method: String, val data: String? = null)

    @Serializable
    private data class CallAgentHostResponseDto(val data: String? = null)

    companion object {
        const val SERVICE = "aiserver.v1.RemoteAgentHostPresenceService"
        const val METHOD_LIST_SESSIONS = "REMOTE_AGENT_HOST_CONTROLLER_METHOD_LIST_SESSIONS"
        const val METHOD_WATCH_SESSIONS = "REMOTE_AGENT_HOST_CONTROLLER_METHOD_WATCH_SESSIONS"
        const val METHOD_ATTACH_SESSION = "REMOTE_AGENT_HOST_CONTROLLER_METHOD_ATTACH_SESSION"
        const val METHOD_GET_SESSION_BLOBS = "REMOTE_AGENT_HOST_CONTROLLER_METHOD_GET_SESSION_BLOBS"
        const val METHOD_SEND_MESSAGE = "REMOTE_AGENT_HOST_CONTROLLER_METHOD_SEND_MESSAGE"
        const val METHOD_CREATE_SESSION = "REMOTE_AGENT_HOST_CONTROLLER_METHOD_CREATE_SESSION"
        const val METHOD_LIST_WORKSPACES = "REMOTE_AGENT_HOST_CONTROLLER_METHOD_LIST_WORKSPACES"
        private const val PAGE_SIZE = 100
        private const val BLOBS_ROUNDS = 8
    }
}
