package com.cursorforandroid.data.voice

import com.cursorforandroid.data.api.CursorJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The frames of Cursor's realtime voice line (xAI's realtime API, an OpenAI-realtime dialect) as the desktop's
 * `realtime-session.ts` and `realtime-wire.ts` write and read them. Audio is 16-bit mono PCM at [SAMPLE_RATE] both
 * ways, base64 in the frame.
 */
object RealtimeWire {
    const val SAMPLE_RATE = 24_000
    const val TRANSCRIPTION_MODEL = "grok-transcribe"
    /** The topic a finished job's outcome is told to the voice agent under (`work_landed`). */
    const val WORK_LANDED = "work_landed"
    /** The longest text a landed job carries, as the desktop cuts it (`nZ`). */
    const val LANDED_CHAR_LIMIT = 2_000
    const val AUTO_LANGUAGE = "auto"

    /** The line's address: [url] with the model named, as `pGv` builds it. */
    fun address(url: String, model: String): String {
        val separator = if ('?' in url) '&' else '?'
        return "$url${separator}model=${java.net.URLEncoder.encode(model, "UTF-8")}"
    }

    /** The WebSocket subprotocol the credential travels in. */
    fun subprotocol(clientSecret: String): String = "xai-client-secret.$clientSecret"

    /** `session.update`, the call's opening: voice, instructions, server-side turn detection, audio and the tools. */
    fun sessionUpdate(voice: String?, instructions: String, speed: Double, language: String?, tools: JsonArray): String = obj {
        put("type", "session.update")
        putJsonObject("session") {
            if (!voice.isNullOrBlank()) put("voice", voice)
            putJsonObject("resumption") { put("enabled", true) }
            put("instructions", instructions)
            putJsonObject("turn_detection") { put("type", "server_vad") }
            putJsonObject("audio") {
                put("input", inputAudio(language))
                putJsonObject("output") {
                    putJsonObject("format") { put("type", "audio/pcm"); put("rate", SAMPLE_RATE) }
                    put("speed", speed)
                }
            }
            put("tools", tools)
        }
    }

    private fun inputAudio(language: String?): JsonObject = buildJsonObject {
        putJsonObject("format") { put("type", "audio/pcm"); put("rate", SAMPLE_RATE) }
        putJsonObject("transcription") {
            put("model", TRANSCRIPTION_MODEL)
            if (!language.isNullOrBlank() && language != AUTO_LANGUAGE) put("language_hint", language)
        }
    }

    fun appendAudio(base64Pcm: String): String = obj {
        put("type", "input_audio_buffer.append")
        put("audio", base64Pcm)
    }

    /** `response.create`, with [instructions] for this one reply (the session's, then what this reply is for). */
    fun responseCreate(instructions: String?): String = obj {
        put("type", "response.create")
        if (instructions != null) putJsonObject("response") { put("instructions", instructions) }
    }

    fun toolOutput(callId: String, output: String): String = obj {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", output)
        }
    }

    /** `async_update.add`: what came back from the agent, which the voice agent then says (`A3i.frame`). */
    fun workLanded(eventId: String, texts: List<String>, atMs: Long): String = obj {
        put("type", "async_update.add")
        put("event_id", eventId)
        putJsonObject("async_update") {
            put("topic", WORK_LANDED)
            put(
                "payload",
                buildJsonArray {
                    texts.forEach { text ->
                        add(buildJsonObject {
                            putJsonObject("message") { put("type", "text"); put("content", text.take(LANDED_CHAR_LIMIT)) }
                            put("timestampMs", atMs)
                        })
                    }
                },
            )
        }
    }

    /** What the caller heard of [itemId] before they cut in, so the voice agent knows where it was stopped. */
    fun truncate(itemId: String, contentIndex: Int, audioEndMs: Long): String = obj {
        put("type", "conversation.item.truncate")
        put("item_id", itemId)
        put("content_index", contentIndex)
        put("audio_end_ms", audioEndMs)
    }

    private inline fun obj(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
        CursorJson.encodeToString(JsonObject.serializer(), buildJsonObject(build))

    // ---- reading ------------------------------------------------------------------------------------------------

    /** What one frame from the line says, as far as the call acts on it. */
    sealed interface Event {
        data object SessionReady : Event
        data class ConversationCreated(val id: String?) : Event
        data object SpeechStarted : Event
        data object SpeechStopped : Event
        data class ResponseCreated(val id: String?) : Event
        data class ResponseEnded(val id: String?, val cancelled: Boolean) : Event
        data class Audio(val base64: String, val itemId: String, val contentIndex: Int) : Event
        data class AssistantDelta(val itemId: String, val delta: String) : Event
        data class Transcript(val fromUser: Boolean, val itemId: String, val text: String) : Event
        data class ToolCall(val callId: String, val name: String, val arguments: String) : Event
        data class Error(val message: String, val code: String?, val eventId: String?) : Event
        data object Other : Event
    }

    fun parse(frame: String): Event {
        val json = runCatching { CursorJson.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return Event.Other
        val type = json.string("type") ?: return Event.Other
        val responseId = json.string("response_id") ?: (json["response"] as? JsonObject)?.string("id")
        return when (type) {
            "session.created", "session.updated" -> Event.SessionReady
            "conversation.created" -> Event.ConversationCreated((json["conversation"] as? JsonObject)?.string("id"))
            "input_audio_buffer.speech_started" -> Event.SpeechStarted
            "input_audio_buffer.speech_stopped" -> Event.SpeechStopped
            "response.created" -> Event.ResponseCreated(responseId)
            "response.done", "response.cancelled" -> {
                val status = (json["response"] as? JsonObject)?.string("status")
                Event.ResponseEnded(responseId, cancelled = type == "response.cancelled" || status == "cancelled")
            }
            "response.audio.delta", "response.output_audio.delta" -> json.string("delta")?.let {
                Event.Audio(it, json.string("item_id").orEmpty(), (json["content_index"] as? JsonPrimitive)?.intOrNull ?: 0)
            } ?: Event.Other
            "response.audio_transcript.delta", "response.output_audio_transcript.delta" ->
                json.string("delta")?.takeIf { it.isNotEmpty() }?.let { Event.AssistantDelta(json.string("item_id").orEmpty(), it) } ?: Event.Other
            "response.audio_transcript.done", "response.output_audio_transcript.done" ->
                json.string("transcript")?.let { Event.Transcript(false, json.string("item_id").orEmpty(), it) } ?: Event.Other
            "conversation.item.input_audio_transcription.completed" ->
                json.string("transcript")?.let { Event.Transcript(true, json.string("item_id").orEmpty(), it) } ?: Event.Other
            "response.function_call_arguments.done" -> {
                val callId = json.string("call_id")
                val name = json.string("name")
                if (callId == null || name == null) Event.Other else Event.ToolCall(callId, name, json.string("arguments").orEmpty())
            }
            "error" -> {
                val error = json["error"] as? JsonObject
                Event.Error(error?.string("message") ?: json.string("message").orEmpty(), error?.string("code"), error?.string("event_id"))
            }
            else -> Event.Other
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** Reads a JSON value that should be text, for a tool's argument. */
    fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.contentOrNull
}
