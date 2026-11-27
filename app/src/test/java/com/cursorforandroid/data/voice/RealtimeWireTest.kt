package com.cursorforandroid.data.voice

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.MintResponseDto
import com.cursorforandroid.data.api.RealtimeVoiceWire
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The frames of Cursor's realtime voice line, and the mint's answer, as the desktop writes and reads them. */
class RealtimeWireTest {

    @Test
    fun `the line is dialled with the model named and the credential as the subprotocol`() {
        assertThat(RealtimeWire.address("wss://api.x.ai/v1/realtime", "grok-voice")).isEqualTo("wss://api.x.ai/v1/realtime?model=grok-voice")
        assertThat(RealtimeWire.address("wss://api.x.ai/v1/realtime?x=1", "a b")).isEqualTo("wss://api.x.ai/v1/realtime?x=1&model=a+b")
        assertThat(RealtimeWire.subprotocol("s3cret")).isEqualTo("xai-client-secret.s3cret")
    }

    @Test
    fun `the session update carries the voice, instructions, audio formats and transcription language`() {
        val frame = CursorJson.parseToJsonElement(RealtimeWire.sessionUpdate("eve", "Be brief.", 1.25, "fr", JsonArray(emptyList()))).jsonObject
        assertThat(frame["type"]!!.jsonPrimitive.content).isEqualTo("session.update")
        val session = frame["session"]!!.jsonObject
        assertThat(session["voice"]!!.jsonPrimitive.content).isEqualTo("eve")
        assertThat(session["resumption"]!!.jsonObject["enabled"]!!.jsonPrimitive.content).isEqualTo("true")
        val input = session["audio"]!!.jsonObject["input"]!!.jsonObject
        assertThat(input["format"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("audio/pcm")
        assertThat(input["transcription"]!!.jsonObject["model"]!!.jsonPrimitive.content).isEqualTo("grok-transcribe")
        assertThat(input["transcription"]!!.jsonObject["language_hint"]!!.jsonPrimitive.content).isEqualTo("fr")
        assertThat(session["audio"]!!.jsonObject["output"]!!.jsonObject["speed"]!!.jsonPrimitive.content).isEqualTo("1.25")

        val auto = CursorJson.parseToJsonElement(RealtimeWire.sessionUpdate(null, "x", 1.0, "auto", JsonArray(emptyList()))).jsonObject["session"]!!.jsonObject
        assertThat(auto["voice"]).isNull()
        assertThat(auto["audio"]!!.jsonObject["input"]!!.jsonObject["transcription"]!!.jsonObject["language_hint"]).isNull()
    }

    @Test
    fun `a landed job is an async update under work_landed, cut to the desktop's limit`() {
        val frame = CursorJson.parseToJsonElement(RealtimeWire.workLanded("work-landed-1", listOf("x".repeat(3_000)), 42)).jsonObject
        assertThat(frame["type"]!!.jsonPrimitive.content).isEqualTo("async_update.add")
        val update = frame["async_update"]!!.jsonObject
        assertThat(update["topic"]!!.jsonPrimitive.content).isEqualTo("work_landed")
        val entry = update["payload"]!!.jsonArray.single().jsonObject
        assertThat(entry["message"]!!.jsonObject["content"]!!.jsonPrimitive.content).hasLength(2_000)
        assertThat(entry["timestampMs"]!!.jsonPrimitive.content).isEqualTo("42")
    }

    @Test
    fun `the server's frames are read as the call needs them`() {
        assertThat(RealtimeWire.parse("""{"type":"session.created"}""")).isEqualTo(RealtimeWire.Event.SessionReady)
        assertThat(RealtimeWire.parse("""{"type":"response.output_audio.delta","item_id":"i","content_index":1,"delta":"AAA="}"""))
            .isEqualTo(RealtimeWire.Event.Audio("AAA=", "i", 1))
        assertThat(RealtimeWire.parse("""{"type":"response.audio.delta","item_id":"i","delta":"AAA="}""")).isEqualTo(RealtimeWire.Event.Audio("AAA=", "i", 0))
        assertThat(RealtimeWire.parse("""{"type":"response.function_call_arguments.done","call_id":"c","name":"send_task","arguments":"{}"}"""))
            .isEqualTo(RealtimeWire.Event.ToolCall("c", "send_task", "{}"))
        assertThat(RealtimeWire.parse("""{"type":"response.done","response":{"id":"r","status":"cancelled"}}""")).isEqualTo(RealtimeWire.Event.ResponseEnded("r", cancelled = true))
        assertThat(RealtimeWire.parse("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"u","transcript":"hi"}"""))
            .isEqualTo(RealtimeWire.Event.Transcript(true, "u", "hi"))
        assertThat(RealtimeWire.parse("""{"type":"error","error":{"message":"bad","code":"x","event_id":"e"}}""")).isEqualTo(RealtimeWire.Event.Error("bad", "x", "e"))
        assertThat(RealtimeWire.parse("not json")).isEqualTo(RealtimeWire.Event.Other)
    }

    @Test
    fun `the mint's answer is read with its plan, and a plan without instructions is no plan`() {
        val dto = CursorJson.decodeFromString(
            MintResponseDto.serializer(),
            """{"clientSecret":"s","model":"grok-voice","websocketUrl":"wss://api.x.ai/v1/realtime","voiceId":"eve","idleSeconds":300,"useServerVoiceTools":true,
              "sessionPlan":{"instructions":"Be Cursor.","toolsJson":"[{\"type\":\"function\",\"name\":\"send_task\"}]","greetingInstructions":"Greet.",
              "underWayReceipt":{"outputJson":"{\"receipt\":\"ok\"}","instructions":"Say sent."},"queuedReceipt":{"outputJson":"{}","instructions":"Say queued."},
              "replyLines":{"unknownToolErrorTemplate":"No {name}.","noReplyLine":"Nothing back."},
              "speeds":[{"value":1.25,"label":"1.25x"}],"languages":[{"id":"fr","label":"French"}],"defaultSpeed":1,"voiceOptions":[{"id":"eve","label":"Eve"}]}}""",
        )
        val session = RealtimeVoiceWire.session(dto)
        assertThat(session.idleSeconds).isEqualTo(300)
        assertThat(session.useServerVoiceTools).isTrue()
        val plan = session.plan!!
        assertThat(plan.instructions).isEqualTo("Be Cursor.")
        assertThat(plan.tools.single().jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("send_task")
        assertThat(plan.underWay.outputJson).isEqualTo("""{"receipt":"ok"}""")
        assertThat(plan.lines.noReplyLine).isEqualTo("Nothing back.")
        assertThat(plan.speeds.single().label).isEqualTo("1.25x")
        assertThat(plan.voices.single().id).isEqualTo("eve")

        val bare = CursorJson.decodeFromString(MintResponseDto.serializer(), """{"clientSecret":"s","sessionPlan":{"toolsJson":"[]"}}""")
        assertThat(RealtimeVoiceWire.session(bare).plan).isNull()
    }
}
