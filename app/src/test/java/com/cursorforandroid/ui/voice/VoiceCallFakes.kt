package com.cursorforandroid.ui.voice

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.RealtimeVoiceApi
import com.cursorforandroid.data.api.RealtimeVoicePlan
import com.cursorforandroid.data.api.RealtimeVoiceSession
import com.cursorforandroid.data.voice.CallMicrophone
import com.cursorforandroid.data.voice.CallSpeaker
import com.cursorforandroid.data.voice.RealtimeDialer
import com.cursorforandroid.data.voice.RealtimeLine
import com.cursorforandroid.data.voice.RealtimeLineListener
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The account's side of a call, scripted: what it mints, what its server tools answer, and what it was asked. */
internal class ScriptedVoiceAccount(
    var session: RealtimeVoiceSession = session(),
    var mintFailure: Exception? = null,
    var serverToolOutput: String = """{"result":"2 files changed"}""",
) : RealtimeVoiceApi {
    val mints = mutableListOf<Pair<String, String?>>()
    val serverTools = mutableListOf<String>()
    val voicesSet = mutableListOf<String>()

    override suspend fun mint(agentId: String, language: String?): RealtimeVoiceSession {
        mints += agentId to language
        mintFailure?.let { throw it }
        return session
    }

    override suspend fun executeServerTool(agentId: String, callId: String, toolName: String, argumentsJson: String): String {
        serverTools += "$agentId/$callId/$toolName/$argumentsJson"
        return serverToolOutput
    }

    override suspend fun setVoice(agentId: String?, voiceId: String) {
        voicesSet += voiceId
    }

    companion object {
        val TOOLS: JsonArray = CursorJson.parseToJsonElement(
            """[{"type":"function","name":"send_task","description":"Send a job","parameters":{"type":"object","properties":{"request":{"type":"string"}},"required":["request"]}}]""",
        ).jsonArray

        fun plan() = RealtimeVoicePlan(
            instructions = "You are Cursor's voice.",
            tools = TOOLS,
            greeting = "Greet the caller.",
            underWay = RealtimeVoicePlan.Receipt("""{"receipt":"under way"}""", "Say you sent it."),
            queued = RealtimeVoicePlan.Receipt("""{"receipt":"queued","queued":true}""", "Say it is queued."),
            lines = RealtimeVoicePlan.ReplyLines(unknownToolErrorTemplate = "No tool {name}.", agentUnreachableAnswer = "Unreachable.", toolFailedError = "Tool failed."),
            speeds = emptyList(),
            languages = emptyList(),
            voices = listOf(RealtimeVoicePlan.Choice("eve", "Eve"), RealtimeVoicePlan.Choice("rex", "Rex")),
            defaultSpeed = 1.0,
            defaultLanguage = "auto",
        )

        fun session(plan: RealtimeVoicePlan? = plan(), url: String = "wss://api.x.ai/v1/realtime", serverTools: Boolean = true) = RealtimeVoiceSession(
            clientSecret = "secret-1",
            model = "grok-voice",
            websocketUrl = url,
            plan = plan,
            voiceId = "eve",
            idleSeconds = null,
            useServerVoiceTools = serverTools,
        )
    }
}

/** The voice line, scripted: what the call sent up it, and the server's frames pushed down. */
internal class ScriptedDialer : RealtimeDialer {
    val dialled = mutableListOf<Pair<String, String>>()
    val sent = mutableListOf<String>()
    var closed = 0
    lateinit var listener: RealtimeLineListener

    override fun dial(address: String, clientSecret: String, listener: RealtimeLineListener): RealtimeLine {
        dialled += address to clientSecret
        this.listener = listener
        return object : RealtimeLine {
            override fun send(frame: String): Boolean {
                sent += frame
                return true
            }

            override fun close() {
                closed++
            }
        }
    }

    fun open() = listener.onOpen()
    fun push(frame: String) = listener.onFrame(frame)
    fun ready() = push("""{"type":"session.updated","session":{}}""")

    /** The frames sent, parsed, of [type]. */
    fun frames(type: String): List<JsonObject> = sent.map { CursorJson.parseToJsonElement(it).jsonObject }.filter { it["type"]?.jsonPrimitive?.content == type }
}

internal class ScriptedMicrophone : CallMicrophone {
    var onChunk: ((ByteArray) -> Unit)? = null
    var stopped = 0
    var failsToStart = false

    override fun start(onChunk: (ByteArray) -> Unit) {
        if (failsToStart) error("busy")
        this.onChunk = onChunk
    }

    override fun level(): Float = 0.3f
    override fun stop() {
        stopped++
    }
}

internal class ScriptedSpeaker : CallSpeaker {
    val played = mutableListOf<String>()
    var playing = false
    var released = 0
    var interrupts = 0

    override fun play(pcm: ByteArray, itemId: String, contentIndex: Int) {
        played += itemId
        playing = true
    }

    override fun interrupt(): CallSpeaker.Heard? {
        interrupts++
        val was = playing
        playing = false
        return if (was) CallSpeaker.Heard(played.last(), 0, 640) else null
    }

    override fun busy(): Boolean = playing
    override fun level(): Float = if (playing) 0.6f else 0f
    override fun release() {
        released++
    }
}
