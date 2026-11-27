package com.cursorforandroid.ui.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.Date

/**
 * Cursor's realtime voice call, driven against a scripted account and line: the mint, the line's opening, the
 * microphone up and the voice down, the tools the voice agent calls, and a job's result told back on the line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChatTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val account = ScriptedVoiceAccount()
    private val dialer = ScriptedDialer()
    private val mic = ScriptedMicrophone()
    private val speaker = ScriptedSpeaker()
    private val tasks = mutableListOf<String>()
    private var lines = listOf(WrittenLine("u1", true, "Fix the login bug"), WrittenLine("a1", false, "I fixed **the login bug**."))
    private var taskAnswer = VoiceChat.TaskSent.UnderWay

    private val host = object : VoiceChat.Host {
        override fun sendTask(request: String): VoiceChat.TaskSent {
            tasks += request
            return taskAnswer
        }

        override fun writtenLines(): List<WrittenLine> = lines
    }

    private fun chat() = VoiceChat(
        scope = scope.backgroundScope,
        agentId = "bc-1",
        api = account,
        dialer = dialer,
        newMicrophone = { mic },
        newSpeaker = { speaker },
        host = host,
        clock = { scope.testScheduler.currentTime },
        errorMessage = { it.message ?: "failed" },
        now = { Date(0) },
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.connected(): VoiceChat {
        val chat = chat()
        chat.start()
        runCurrent()
        dialer.open()
        runCurrent()
        dialer.ready()
        runCurrent()
        return chat
    }

    private fun toolCall(name: String, arguments: String, callId: String = "call-1") =
        dialer.push("""{"type":"response.function_call_arguments.done","call_id":"$callId","name":"$name","arguments":${kotlinx.serialization.json.JsonPrimitive(arguments)}}""")

    @Test
    fun `the call is minted for the chat and the line opened with the credential, then the session sent`() = scope.runTest {
        val chat = chat()
        chat.start()
        assertThat(chat.state.value.phase).isEqualTo(VoiceChatPhase.Connecting)
        runCurrent()
        assertThat(account.mints).containsExactly("bc-1" to null)
        assertThat(dialer.dialled).containsExactly("wss://api.x.ai/v1/realtime?model=grok-voice" to "secret-1")

        dialer.open()
        runCurrent()
        val session = dialer.frames("session.update").single()["session"]!!.jsonObject
        assertThat(session["voice"]!!.jsonPrimitive.content).isEqualTo("eve")
        assertThat(session["instructions"]!!.jsonPrimitive.content).startsWith("You are Cursor's voice.")
        assertThat(session["instructions"]!!.jsonPrimitive.content).contains("User: Fix the login bug")
        assertThat(session["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content).isEqualTo("send_task")
        assertThat(session["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content).isEqualTo("server_vad")
        assertThat(session["audio"]!!.jsonObject["input"]!!.jsonObject["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content).isEqualTo("24000")
        assertThat(chat.state.value.phase).isEqualTo(VoiceChatPhase.Connecting)

        dialer.ready()
        runCurrent()
        assertThat(chat.state.value.phase).isEqualTo(VoiceChatPhase.Listening())
        chat.end()
    }

    @Test
    fun `the microphone goes up the line once it is ready, and not while muted`() = scope.runTest {
        val chat = chat()
        chat.start()
        runCurrent()
        mic.onChunk!!(ByteArray(4) { 1 })
        assertThat(dialer.frames("input_audio_buffer.append")).isEmpty()

        dialer.open(); runCurrent(); dialer.ready(); runCurrent()
        mic.onChunk!!(ByteArray(4) { 1 })
        val append = dialer.frames("input_audio_buffer.append").single()
        assertThat(Base64.getDecoder().decode(append["audio"]!!.jsonPrimitive.content)).isEqualTo(ByteArray(4) { 1 })

        chat.toggleMute()
        runCurrent()
        assertThat(chat.state.value.muted).isTrue()
        assertThat(chat.state.value.phase).isEqualTo(VoiceChatPhase.Muted)
        val afterMute = dialer.frames("input_audio_buffer.append").size
        mic.onChunk!!(ByteArray(4) { 1 })
        assertThat(dialer.frames("input_audio_buffer.append")).hasSize(afterMute)
        chat.end()
    }

    @Test
    fun `a caller who says nothing is greeted, and one who speaks first is not`() = scope.runTest {
        val chat = connected()
        advanceTimeBy(VoiceChat.GreetingGraceMillis + 10)
        runCurrent()
        val greeting = dialer.frames("response.create").single()
        assertThat(greeting["response"]!!.jsonObject["instructions"]!!.jsonPrimitive.content).endsWith("\n\nGreet the caller.")
        chat.end()
    }

    @Test
    fun `the agent's voice is played, and the caller cutting in stops it and tells the line what was heard`() = scope.runTest {
        val chat = connected()
        dialer.push("""{"type":"response.created","response":{"id":"r1"}}""")
        dialer.push("""{"type":"response.output_audio_transcript.delta","item_id":"i1","delta":"All tests pass"}""")
        dialer.push("""{"type":"response.output_audio.delta","item_id":"i1","content_index":0,"delta":"${Base64.getEncoder().encodeToString(ByteArray(8))}"}""")
        runCurrent()
        assertThat(speaker.played).containsExactly("i1")
        assertThat(chat.state.value.phase).isEqualTo(VoiceChatPhase.Speaking("All tests pass"))

        dialer.push("""{"type":"input_audio_buffer.speech_started"}""")
        runCurrent()
        assertThat(speaker.interrupts).isEqualTo(1)
        val cut = dialer.frames("conversation.item.truncate").single()
        assertThat(cut["item_id"]!!.jsonPrimitive.content).isEqualTo("i1")
        assertThat(cut["audio_end_ms"]!!.jsonPrimitive.content).isEqualTo("640")
        assertThat(chat.state.value.phase).isEqualTo(VoiceChatPhase.Listening(heard = true))
        dialer.push("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"u2","transcript":"Run the tests"}""")
        runCurrent()
        assertThat(chat.state.value.turns).contains(VoiceTurn(fromUser = true, text = "Run the tests"))
        chat.end()
    }

    @Test
    fun `send_task hands the job to the agent, answers with the receipt, and the agent's reply lands on the line`() = scope.runTest {
        val chat = connected()
        toolCall("send_task", """{"request":"Run the test suite and report failures"}""")
        runCurrent()
        assertThat(tasks).containsExactly("Run the test suite and report failures")
        val output = dialer.frames("conversation.item.create").single()["item"]!!.jsonObject
        assertThat(output["type"]!!.jsonPrimitive.content).isEqualTo("function_call_output")
        assertThat(output["call_id"]!!.jsonPrimitive.content).isEqualTo("call-1")
        assertThat(output["output"]!!.jsonPrimitive.content).isEqualTo("""{"receipt":"under way"}""")
        assertThat(dialer.frames("response.create").single()["response"]!!.jsonObject["instructions"]!!.jsonPrimitive.content).endsWith("\n\nSay you sent it.")
        assertThat(chat.state.value.phase).isEqualTo(VoiceChatPhase.Working("Run the test suite and report failures"))

        // The job's message reaches the chat, the agent works, and its turn ends with a reply.
        lines = lines + WrittenLine("u2", true, "Run the test suite and report failures")
        chat.agent(running = true, lines = lines)
        assertThat(dialer.frames("async_update.add")).isEmpty()
        lines = lines + WrittenLine("a2", false, "All **412** tests pass.\n\n```\nok\n```")
        chat.agent(running = false, lines = lines)
        runCurrent()
        val landed = dialer.frames("async_update.add").single()["async_update"]!!.jsonObject
        assertThat(landed["topic"]!!.jsonPrimitive.content).isEqualTo("work_landed")
        val text = landed["payload"]!!.jsonArray.single().jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertThat(text).startsWith("All 412 tests pass.")
        assertThat(text).doesNotContain("```")
        assertThat(chat.state.value.phase).isEqualTo(VoiceChatPhase.Listening())
        chat.end()
    }

    @Test
    fun `a job that could not be sent is said to be unreachable, and none lands`() = scope.runTest {
        taskAnswer = VoiceChat.TaskSent.Refused
        val chat = connected()
        toolCall("send_task", """{"request":"Deploy it"}""")
        runCurrent()
        val output = dialer.frames("conversation.item.create").single()["item"]!!.jsonObject["output"]!!.jsonPrimitive.content
        assertThat(output).contains("\"relay\":\"unreachable\"")
        assertThat(output).contains("Unreachable.")
        assertThat(chat.state.value.phase).isEqualTo(VoiceChatPhase.Listening())
        chat.end()
    }

    @Test
    fun `the read-only tools go to the account, recall reads the chat, and an unknown tool is refused`() = scope.runTest {
        val chat = connected()
        toolCall("peek_changes", """{"file":"Login.kt"}""", callId = "c-peek")
        runCurrent()
        assertThat(account.serverTools).containsExactly("""bc-1/c-peek/peek_changes/{"file":"Login.kt"}""")
        assertThat(dialer.frames("conversation.item.create").last()["item"]!!.jsonObject["output"]!!.jsonPrimitive.content).isEqualTo("""{"result":"2 files changed"}""")

        toolCall("recall_text_messages", "{}", callId = "c-recall")
        runCurrent()
        val recall = dialer.frames("conversation.item.create").last()["item"]!!.jsonObject["output"]!!.jsonPrimitive.content
        assertThat(recall).contains("\"writer\":\"them\"")
        assertThat(recall).contains("I fixed the login bug.")

        toolCall("open_the_pod_bay_doors", "{}", callId = "c-x")
        runCurrent()
        assertThat(dialer.frames("conversation.item.create").last()["item"]!!.jsonObject["output"]!!.jsonPrimitive.content).contains("No tool open_the_pod_bay_doors.")
        assertThat(dialer.frames("response.create")).hasSize(3)
        chat.end()
    }

    @Test
    fun `end_the_call hangs up once the goodbye has been heard, and stay_silent says nothing`() = scope.runTest {
        val chat = connected()
        toolCall("stay_silent", "{}", callId = "c-quiet")
        runCurrent()
        assertThat(dialer.frames("response.create")).isEmpty()

        speaker.playing = true
        toolCall("end_the_call", "{}", callId = "c-bye")
        runCurrent()
        advanceTimeBy(1_000)
        assertThat(chat.state.value.active).isTrue()
        speaker.playing = false
        advanceTimeBy(500)
        runCurrent()
        assertThat(chat.state.value.phase).isEqualTo(VoiceChatPhase.Off)
        assertThat(dialer.closed).isEqualTo(1)
        assertThat(mic.stopped).isEqualTo(1)
        assertThat(speaker.released).isEqualTo(1)
    }

    @Test
    fun `a mint the account refuses, a line to another host, and a line that never opens each fail the call`() = scope.runTest {
        account.mintFailure = IOException("Realtime voice is not enabled for this account.")
        val refused = chat()
        refused.start()
        runCurrent()
        assertThat(refused.state.value.phase).isEqualTo(VoiceChatPhase.Failed("Realtime voice is not enabled for this account."))
        assertThat(dialer.dialled).isEmpty()

        account.mintFailure = null
        account.session = ScriptedVoiceAccount.session(url = "wss://example.com/realtime")
        val elsewhere = chat()
        elsewhere.start()
        runCurrent()
        assertThat(elsewhere.state.value.phase).isEqualTo(VoiceChatPhase.Failed("Realtime voice host is not allowed."))
        assertThat(dialer.dialled).isEmpty()

        account.session = ScriptedVoiceAccount.session()
        val dropped = chat()
        dropped.start()
        runCurrent()
        dialer.listener.onClosed(400, "Incorrect API key provided.")
        runCurrent()
        assertThat(dropped.state.value.phase).isEqualTo(VoiceChatPhase.Failed("Couldn't connect to Grok Voice: Incorrect API key provided."))
        assertThat(mic.stopped).isEqualTo(1)

        // A tap calls again.
        dropped.tap()
        runCurrent()
        assertThat(account.mints).hasSize(4)
        dropped.end()
    }

    @Test
    fun `a changed voice or speed is sent on the open line`() = scope.runTest {
        val chat = connected()
        chat.settings = VoiceCallSettings(voice = "rex", speed = 1.5f)
        runCurrent()
        val session = dialer.frames("session.update").last()["session"]!!.jsonObject
        assertThat(session["voice"]!!.jsonPrimitive.content).isEqualTo("rex")
        assertThat(session["audio"]!!.jsonObject["output"]!!.jsonObject["speed"]!!.jsonPrimitive.content).isEqualTo("1.5")
        assertThat(chat.voices.map { it.id }).containsExactly("eve", "rex").inOrder()
        chat.end()
    }
}
