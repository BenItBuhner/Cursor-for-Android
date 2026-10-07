package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.proto.AgentSchemas
import com.cursorforandroid.data.api.proto.ProtoWire
import com.cursorforandroid.domain.LocalAgentLine
import com.cursorforandroid.domain.LocalAgentLineKind
import com.cursorforandroid.domain.LocalSessionEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Check-in lines for a local agent: conversation-state blobs from `GET_SESSION_BLOBS`, then live ATTACH events.
 * User and assistant text only — not the full cloud transcript UI, and never a `bc-…` chat.
 */
object LocalAgentTranscript {

    private const val MAX_TURNS = 12

    suspend fun history(turnBlobIds: List<String>, blobs: Map<String, ByteArray>, fetch: suspend (List<String>) -> Map<String, ByteArray>): List<LocalAgentLine> {
        val held = HashMap(blobs)
        val recent = turnBlobIds.takeLast(MAX_TURNS)
        val missing = recent.filter { it !in held }
        if (missing.isNotEmpty()) held.putAll(fetch(missing))
        val lines = ArrayList<LocalAgentLine>()
        for (turnId in recent) {
            val turnBytes = held[turnId] ?: continue
            val turn = runCatching { ProtoWire.decode(turnBytes, AgentSchemas.CONVERSATION_TURN) }.getOrNull() ?: continue
            val agent = turn["agentConversationTurn"] as? JsonObject ?: continue
            val userId = (agent["userMessage"] as? JsonPrimitive)?.contentOrNull
            if (!userId.isNullOrEmpty()) {
                val userBytes = held[userId] ?: fetch(listOf(userId)).also { held.putAll(it) }[userId]
                val text = userBytes?.let { userText(it) }
                if (!text.isNullOrBlank()) lines += LocalAgentLine(LocalAgentLineKind.USER, text)
            }
            val stepIds = (agent["steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.isNotEmpty() } }.orEmpty()
            val need = stepIds.filter { it !in held }
            if (need.isNotEmpty()) held.putAll(fetch(need))
            for (stepId in stepIds) {
                val stepBytes = held[stepId] ?: continue
                val step = runCatching { ProtoWire.decode(stepBytes, AgentSchemas.CONVERSATION_STEP) }.getOrNull() ?: continue
                val assistant = (step["assistantMessage"] as? JsonObject)?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }
                if (!assistant.isNullOrBlank()) lines += LocalAgentLine(LocalAgentLineKind.ASSISTANT, assistant)
            }
        }
        return lines
    }

    fun apply(lines: List<LocalAgentLine>, event: LocalSessionEvent): List<LocalAgentLine> = when (event) {
        is LocalSessionEvent.Keepalive, is LocalSessionEvent.History -> lines
        is LocalSessionEvent.UserSaid -> lines + LocalAgentLine(LocalAgentLineKind.USER, event.text)
        is LocalSessionEvent.AssistantDelta -> appendAssistant(lines, event.text, if (event.notice) LocalAgentLineKind.NOTICE else LocalAgentLineKind.ASSISTANT)
        is LocalSessionEvent.Working -> replaceStatus(lines, "Working…")
        is LocalSessionEvent.Waiting -> replaceStatus(lines, "Waiting for you")
        is LocalSessionEvent.Settled -> replaceStatus(lines, event.detail?.takeIf { it.isNotBlank() } ?: "Settled")
    }

    private fun appendAssistant(lines: List<LocalAgentLine>, text: String, kind: LocalAgentLineKind): List<LocalAgentLine> {
        if (text.isEmpty()) return lines
        val last = lines.lastOrNull()
        return if (last != null && last.kind == kind) {
            lines.dropLast(1) + last.copy(text = last.text + text)
        } else {
            lines + LocalAgentLine(kind, text)
        }
    }

    private fun replaceStatus(lines: List<LocalAgentLine>, text: String): List<LocalAgentLine> {
        val without = if (lines.lastOrNull()?.kind == LocalAgentLineKind.STATUS) lines.dropLast(1) else lines
        return without + LocalAgentLine(LocalAgentLineKind.STATUS, text)
    }

    private fun userText(bytes: ByteArray): String? {
        val message = runCatching { ProtoWire.decode(bytes, AgentSchemas.USER_MESSAGE) }.getOrNull() ?: return utf8(bytes)
        return (message["text"] as? JsonPrimitive)?.contentOrNull
            ?: (message["richText"] as? JsonPrimitive)?.contentOrNull
            ?: utf8(bytes)
    }

    private fun utf8(bytes: ByteArray): String? =
        runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()?.takeIf { it.isNotBlank() && it.none { ch -> ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' } }

}
