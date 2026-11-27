package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.AccountTokens
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Cursor's realtime voice call, as the desktop (3.32.4, `realtimeVoiceService` / `realtime-session.ts`) holds it:
 * the account mints a short-lived credential for xAI's realtime voice line, with the call's whole plan — the voice
 * agent's instructions, its tools, its greeting and the lines it answers with — and the app opens that line itself
 * (see `RealtimeLine`). The account also runs the call's read-only tools for a cloud agent. An interface so the call
 * can be driven against a scripted account.
 */
interface RealtimeVoiceApi {
    /** `AiService/MintRealtimeVoiceSecret {agent_id, language?}`: a credential and plan for one call with [agentId]. */
    suspend fun mint(agentId: String, language: String?): RealtimeVoiceSession

    /**
     * `BackgroundComposerService/ExecuteCloudAgentVoiceTool {bc_id, call_id, tool_name, arguments_json}`: one of the
     * call's read-only tools (`peek_changes`, `peek_runs`, `peek_agents`, `search_conversations`) run by the account
     * against the cloud agent [agentId]; returns the tool's output JSON as the voice line takes it.
     */
    suspend fun executeServerTool(agentId: String, callId: String, toolName: String, argumentsJson: String): String

    /** `AiService/SetRealtimeVoice {agent_id?, voice_id}`: the voice the account's calls speak in from now on. */
    suspend fun setVoice(agentId: String?, voiceId: String)
}

/** What `MintRealtimeVoiceSecret` hands out: the line's credential, the call's plan and its voice. */
data class RealtimeVoiceSession(
    val clientSecret: String,
    val model: String,
    /** The line's address; only xAI's realtime host is dialled (see [RealtimeVoiceSession.ALLOWED_HOST]). */
    val websocketUrl: String,
    val plan: RealtimeVoicePlan?,
    val voiceId: String?,
    /** How long a call nobody speaks into stays up, when the account says. */
    val idleSeconds: Int?,
    /** Whether the account runs the read-only tools (see [RealtimeVoiceApi.executeServerTool]). */
    val useServerVoiceTools: Boolean,
) {
    companion object {
        /** The desktop refuses any other host for the line ("Realtime voice host is not allowed."). */
        const val ALLOWED_HOST = "wss://api.x.ai/"
    }
}

/**
 * `aiserver.v1.RealtimeVoiceSessionPlan`, read: what the voice agent is told ([instructions], [tools] as the line's
 * `session.update` takes them, [greeting]), what a `send_task` answers ([underWay] or, behind a turn, [queued]),
 * the lines said when something goes wrong ([lines]), and the settings menu's choices.
 */
data class RealtimeVoicePlan(
    val instructions: String,
    val tools: JsonArray,
    val greeting: String,
    val underWay: Receipt,
    val queued: Receipt,
    val lines: ReplyLines,
    val speeds: List<Choice>,
    val languages: List<Choice>,
    val voices: List<Choice>,
    val defaultSpeed: Double?,
    val defaultLanguage: String?,
) {
    /** A tool output ([outputJson]) and the instructions the reply after it is made with. */
    data class Receipt(val outputJson: String, val instructions: String)

    /** One choice of a settings row: its id (a speed's value as text) and its label. */
    data class Choice(val id: String, val label: String)

    /** `RealtimeVoiceSessionPlan.ReplyLines`, the ones this client says; blank when the account sent none. */
    data class ReplyLines(
        val unknownToolErrorTemplate: String = "",
        val agentUnreachableAnswer: String = "",
        val toolFailedError: String = "",
        val sendTaskMissingRequestError: String = "",
        val sendTaskRequestTooLongError: String = "",
        val errorLine: String = "",
        val stoppedLine: String = "",
        val noReplyLine: String = "",
    )
}

class AccountRealtimeVoiceApi(
    private val rpc: ConnectJsonClient,
    /** The browser sign-in's own session: Cursor refuses a call minted with the API key's ([com.cursorforandroid.data.auth.UserSessionProvider]). */
    private val tokens: AccountTokens,
) : RealtimeVoiceApi {

    override suspend fun mint(agentId: String, language: String?): RealtimeVoiceSession {
        val response = rpc.unaryWithSession(
            AccountTranscriptionApi.SERVICE, "MintRealtimeVoiceSecret", tokens,
            MintRequestDto(agentId = agentId, language = language?.takeIf { it.isNotBlank() }),
            MintRequestDto.serializer(), MintResponseDto.serializer(), retryRefusals = false,
        )
        return RealtimeVoiceWire.session(response)
    }

    override suspend fun executeServerTool(agentId: String, callId: String, toolName: String, argumentsJson: String): String =
        rpc.unaryWithSession(
            BackgroundComposerApi.SERVICE, "ExecuteCloudAgentVoiceTool", tokens,
            ExecuteToolRequestDto(bcId = agentId, callId = callId, toolName = toolName, argumentsJson = argumentsJson),
            ExecuteToolRequestDto.serializer(), ExecuteToolResponseDto.serializer(), retryRefusals = false,
        ).outputJson.orEmpty()

    override suspend fun setVoice(agentId: String?, voiceId: String) {
        rpc.unaryWithSession(
            AccountTranscriptionApi.SERVICE, "SetRealtimeVoice", tokens,
            SetVoiceRequestDto(agentId = agentId, voiceId = voiceId),
            SetVoiceRequestDto.serializer(), JsonObject.serializer(),
        )
    }
}

/** Reading the mint's answer, apart from the network so it can be tested on what the account sends. */
internal object RealtimeVoiceWire {
    fun session(dto: MintResponseDto): RealtimeVoiceSession = RealtimeVoiceSession(
        clientSecret = dto.clientSecret.orEmpty(),
        model = dto.model.orEmpty(),
        websocketUrl = dto.websocketUrl.orEmpty(),
        plan = dto.sessionPlan?.let(::plan),
        voiceId = dto.voiceId?.takeIf { it.isNotBlank() },
        idleSeconds = dto.idleSeconds?.intOrNull ?: dto.idleSeconds?.contentOrNull?.toIntOrNull(),
        useServerVoiceTools = dto.useServerVoiceTools == true,
    )

    /** The plan, or null when it is not one the line can open with (no instructions, or tools that are not a list). */
    fun plan(dto: SessionPlanDto): RealtimeVoicePlan? {
        val instructions = dto.instructions?.takeIf { it.isNotBlank() } ?: return null
        val tools = runCatching { CursorJson.parseToJsonElement(dto.toolsJson.orEmpty()) as? JsonArray }.getOrNull() ?: return null
        val lines = dto.replyLines
        return RealtimeVoicePlan(
            instructions = instructions,
            tools = tools,
            greeting = dto.greetingInstructions.orEmpty(),
            underWay = RealtimeVoicePlan.Receipt(dto.underWayReceipt?.outputJson.orEmpty(), dto.underWayReceipt?.instructions.orEmpty()),
            queued = RealtimeVoicePlan.Receipt(dto.queuedReceipt?.outputJson.orEmpty(), dto.queuedReceipt?.instructions.orEmpty()),
            lines = RealtimeVoicePlan.ReplyLines(
                unknownToolErrorTemplate = lines?.unknownToolErrorTemplate.orEmpty(),
                agentUnreachableAnswer = lines?.agentUnreachableAnswer.orEmpty(),
                toolFailedError = lines?.toolFailedError.orEmpty(),
                sendTaskMissingRequestError = lines?.sendTaskMissingRequestError.orEmpty(),
                sendTaskRequestTooLongError = lines?.sendTaskRequestTooLongError.orEmpty(),
                errorLine = lines?.errorLine.orEmpty(),
                stoppedLine = lines?.stoppedLine.orEmpty(),
                noReplyLine = lines?.noReplyLine.orEmpty(),
            ),
            speeds = dto.speeds.mapNotNull { s ->
                val value = s.value?.doubleOrNull ?: s.value?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
                RealtimeVoicePlan.Choice(value.toString(), s.label.orEmpty().ifBlank { "${value}x" })
            },
            languages = dto.languages.mapNotNull { o -> o.id?.takeIf { it.isNotBlank() }?.let { RealtimeVoicePlan.Choice(it, o.label.orEmpty().ifBlank { it }) } },
            voices = dto.voiceOptions.mapNotNull { o -> o.id?.takeIf { it.isNotBlank() }?.let { RealtimeVoicePlan.Choice(it, o.label.orEmpty().ifBlank { it }) } },
            defaultSpeed = dto.defaultSpeed?.doubleOrNull ?: dto.defaultSpeed?.contentOrNull?.toDoubleOrNull(),
            defaultLanguage = dto.defaultLanguage?.takeIf { it.isNotBlank() },
        )
    }

    /** A tool's arguments as the line sent them, as an object; empty when they were not one. */
    fun arguments(json: String): JsonObject = runCatching { CursorJson.parseToJsonElement(json).jsonObject }.getOrDefault(JsonObject(emptyMap()))
}

@Serializable
internal data class MintRequestDto(val language: String? = null, val agentId: String? = null)

@Serializable
internal data class MintResponseDto(
    val clientSecret: String? = null,
    val model: String? = null,
    val websocketUrl: String? = null,
    val sessionPlan: SessionPlanDto? = null,
    val voiceId: String? = null,
    val idleSeconds: JsonPrimitive? = null,
    val useServerVoiceTools: Boolean? = null,
)

@Serializable
internal data class SessionPlanDto(
    val instructions: String? = null,
    val toolsJson: String? = null,
    val greetingInstructions: String? = null,
    val underWayReceipt: ReceiptDto? = null,
    val queuedReceipt: ReceiptDto? = null,
    val replyLines: ReplyLinesDto? = null,
    val speeds: List<SpeedOptionDto> = emptyList(),
    val languages: List<OptionDto> = emptyList(),
    val defaultSpeed: JsonPrimitive? = null,
    val defaultLanguage: String? = null,
    val voiceOptions: List<OptionDto> = emptyList(),
)

@Serializable
internal data class ReceiptDto(val outputJson: String? = null, val instructions: String? = null)

@Serializable
internal data class ReplyLinesDto(
    val unknownToolErrorTemplate: String? = null,
    val agentUnreachableAnswer: String? = null,
    val pauseLine: String? = null,
    val parkedRefusal: String? = null,
    val stoppedLine: String? = null,
    val errorLine: String? = null,
    val noReplyLine: String? = null,
    val unreadableLine: String? = null,
    val toolFailedError: String? = null,
    val sendTaskMissingRequestError: String? = null,
    val sendTaskRequestTooLongError: String? = null,
)

@Serializable
internal data class SpeedOptionDto(val value: JsonPrimitive? = null, val label: String? = null)

@Serializable
internal data class OptionDto(val id: String? = null, val label: String? = null)

@Serializable
internal data class ExecuteToolRequestDto(val bcId: String, val callId: String, val toolName: String, val argumentsJson: String)

@Serializable
internal data class ExecuteToolResponseDto(val outputJson: String? = null)

@Serializable
internal data class SetVoiceRequestDto(val agentId: String? = null, val voiceId: String)
