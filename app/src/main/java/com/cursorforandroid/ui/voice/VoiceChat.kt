package com.cursorforandroid.ui.voice

import com.cursorforandroid.data.api.RealtimeVoiceApi
import com.cursorforandroid.data.api.RealtimeVoicePlan
import com.cursorforandroid.data.api.RealtimeVoiceSession
import com.cursorforandroid.data.auth.SessionUnavailableException
import com.cursorforandroid.data.api.RealtimeVoiceWire
import com.cursorforandroid.data.voice.CallMicrophone
import com.cursorforandroid.data.voice.CallSpeaker
import com.cursorforandroid.data.voice.RealtimeDialer
import com.cursorforandroid.data.voice.RealtimeLine
import com.cursorforandroid.data.voice.RealtimeLineListener
import com.cursorforandroid.data.voice.RealtimeWire
import com.cursorforandroid.domain.SpokenText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.Date

/** Where a voice chat stands. Exactly one of these at a time. */
sealed interface VoiceChatPhase {
    /** No call. */
    data object Off : VoiceChatPhase

    /** The account is minting the call and the line is being opened. */
    data object Connecting : VoiceChatPhase

    /** The line is open and the voice agent is waiting for the caller; [heard] while they are speaking. */
    data class Listening(val heard: Boolean = false) : VoiceChatPhase

    /** The caller has finished and the voice agent is making its reply. */
    data object Thinking : VoiceChatPhase

    /** A job the voice agent sent ([said]) is with the agent, and the line is quiet until it lands. */
    data class Working(val said: String) : VoiceChatPhase

    /** The voice agent is speaking; [text] is what it has said of this reply so far. */
    data class Speaking(val text: String) : VoiceChatPhase

    /** The caller is muted and the line is quiet. */
    data object Muted : VoiceChatPhase

    /**
     * Something did not work. [opensSettings]: the microphone permission is off for good, and the tap opens the
     * app's settings page; otherwise a tap calls again.
     */
    data class Failed(
        val message: String,
        val resumes: Boolean = false,
        val opensSettings: Boolean = false,
        /** The account session calls need is not kept: the tap connects the Cursor account in the browser. */
        val connectsAccount: Boolean = false,
    ) : VoiceChatPhase
}

/** One turn of a call's transcript: what [fromUser] said, or what the voice agent said. */
data class VoiceTurn(val fromUser: Boolean, val text: String)

/**
 * A voice chat as the screen draws it: its [phase], the line's [level] (0 to 1: the caller's voice, or the agent's
 * while it speaks), what the caller last said, whether they are [muted] (the call goes on and the agent still works
 * and answers, but nothing of theirs is heard), and the call's [turns] so far, for its transcript.
 */
data class VoiceChatState(
    val phase: VoiceChatPhase = VoiceChatPhase.Off,
    val level: Float = 0f,
    val lastSaid: String? = null,
    val muted: Boolean = false,
    val turns: List<VoiceTurn> = emptyList(),
) {
    val active: Boolean get() = phase != VoiceChatPhase.Off
}

/**
 * Cursor's voice call with one chat's agent, held as the desktop holds it (Cursor 3.32.4, `realtime-session.ts`
 * and the call `controller`): the account mints the call ([RealtimeVoiceApi.mint]) and the app opens a realtime
 * line to xAI's voice agent ([RealtimeDialer]), streaming the microphone up and playing the agent's voice as it
 * comes. The voice agent talks with the caller itself and works through the tools the account's plan gives it:
 * `send_task` hands a job to the chat's agent as a follow-up marked as sent by the call ([Host.sendTask]), and when
 * that agent's turn ends its reply is told back on the line (`work_landed`) for the voice agent to say. The read-only
 * tools are run by the account; `recall_text_messages` reads the chat here; `end_the_call` hangs up.
 *
 * Runs on [scope]'s (main) dispatcher; the line's and the microphone's threads hand their work to it.
 */
class VoiceChat(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val api: RealtimeVoiceApi,
    private val dialer: RealtimeDialer,
    private val newMicrophone: () -> CallMicrophone,
    private val newSpeaker: () -> CallSpeaker,
    private val host: Host,
    private val clock: () -> Long,
    /** A failed mint or line in a few words. */
    private val errorMessage: (Throwable) -> String,
    private val now: () -> Date = { Date() },
) {
    /** The chat's side of the call. */
    interface Host {
        /** Hands [request] to the agent as a follow-up sent by the call; how it went. */
        fun sendTask(request: String): TaskSent

        /** The written chat, oldest first, for the voice agent's context and `recall_text_messages`. */
        fun writtenLines(): List<WrittenLine>
    }

    /** What became of a `send_task`. */
    enum class TaskSent { UnderWay, Queued, Refused }

    /** The call's voice, speed and language; set before [start] and whenever the menu changes them. */
    var settings: VoiceCallSettings = VoiceCallSettings()
        set(value) {
            val changed = field != value
            field = value
            if (changed) resendSession()
        }

    private val _state = MutableStateFlow(VoiceChatState())
    val state: StateFlow<VoiceChatState> = _state.asStateFlow()

    /** Called once for each phase the call enters, for the haptics. */
    var onPhase: (VoiceChatPhase) -> Unit = {}

    /** The voices the account's plan offers, once the call is minted. */
    var voices: List<RealtimeVoicePlan.Choice> = emptyList()
        private set

    private var session: RealtimeVoiceSession? = null
    private var plan: RealtimeVoicePlan? = null
    private var instructions: String = ""
    @Volatile private var line: RealtimeLine? = null
    @Volatile private var muted = false
    @Volatile private var lineReady = false
    private var microphone: CallMicrophone? = null
    private var speaker: CallSpeaker? = null
    private var connecting: Job? = null
    private var ticker: Job? = null
    private var greeting: Job? = null
    private var hangingUp: Job? = null
    /** Counts the calls dialled, so a word from a line that has been left is told from one that still counts. */
    private var generation = 0

    private var callerSpeaking = false
    private var responding = false
    private var sawResponse = false
    private var callerSpoke = false
    private var assistantText = StringBuilder()
    private var lastCallerAt = 0L
    private var landedSeq = 0

    /** The jobs sent to the agent that have not landed yet, newest last. */
    private val openJobs = mutableListOf<String>()
    private var agentRunning = false
    private var sawRunningSinceSend = false
    private var latestLines: List<WrittenLine> = emptyList()

    private val phase: VoiceChatPhase get() = _state.value.phase

    /** Starts the call; does nothing while one is under way. */
    fun start() {
        if (_state.value.active && phase !is VoiceChatPhase.Failed) return
        dial()
    }

    /**
     * After a [VoiceChatPhase.Failed.connectsAccount]: [connect] opens the browser sign-in and returns once the
     * account session is kept, and the call then starts again. Hanging up meanwhile abandons it.
     */
    fun connectAccount(connect: suspend () -> Unit) {
        if ((phase as? VoiceChatPhase.Failed)?.connectsAccount != true) return
        leave()
        enter(VoiceChatPhase.Connecting)
        val call = generation
        connecting = scope.launch {
            try {
                connect()
                if (call == generation) {
                    connecting = null
                    dial()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (call == generation) fail(errorMessage(e))
            }
        }
    }

    private fun dial() {
        leave()
        val turns = _state.value.turns
        _state.value = VoiceChatState(muted = muted, turns = turns)
        enter(VoiceChatPhase.Connecting)
        val call = ++generation
        connecting = scope.launch {
            try {
                val minted = api.mint(agentId, settings.languageTag)
                if (call != generation) return@launch
                val mintedPlan = minted.plan
                when {
                    !minted.websocketUrl.startsWith(RealtimeVoiceSession.ALLOWED_HOST) -> fail("Realtime voice host is not allowed.")
                    minted.clientSecret.isBlank() || minted.model.isBlank() -> fail("Cursor did not hand out a voice line for this chat.")
                    mintedPlan == null -> fail("Cursor's voice plan for this call could not be read.")
                    else -> open(call, minted, mintedPlan)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionUnavailableException) {
                if (call != generation) return@launch
                if (e.code == SessionUnavailableException.NO_ACCOUNT_SESSION) {
                    leave()
                    enter(VoiceChatPhase.Failed(e.message ?: "Connect your Cursor account for calls.", connectsAccount = true))
                } else {
                    fail(errorMessage(e))
                }
            } catch (e: Exception) {
                if (call == generation) fail(errorMessage(e))
            }
        }
    }

    private fun open(call: Int, minted: RealtimeVoiceSession, plan: RealtimeVoicePlan) {
        session = minted
        this.plan = plan
        voices = plan.voices
        latestLines = host.writtenLines()
        instructions = VoiceCallPrompt.instructions(plan.instructions, latestLines, now())
        val nextSpeaker = try { newSpeaker() } catch (e: Exception) { return fail("The call's audio could not be opened.") }
        speaker = nextSpeaker
        val nextMic = newMicrophone()
        try {
            nextMic.start { chunk -> onMicrophone(call, chunk) }
        } catch (e: Exception) {
            nextSpeaker.release()
            speaker = null
            return fail("Couldn't open the microphone. Another app may be using it.")
        }
        microphone = nextMic
        lastCallerAt = clock()
        line = dialer.dial(
            RealtimeWire.address(minted.websocketUrl, minted.model),
            minted.clientSecret,
            object : RealtimeLineListener {
                override fun onOpen() {
                    scope.launch { if (call == generation) sendSession() }
                }

                override fun onFrame(frame: String) {
                    val event = RealtimeWire.parse(frame)
                    if (event is RealtimeWire.Event.Audio) {
                        // Straight to the speaker from the socket's thread: a frame's audio never waits on the screen.
                        if (call == generation && !callerSpeaking) speaker?.play(decode(event.base64), event.itemId, event.contentIndex)
                    }
                    scope.launch { if (call == generation) onEvent(event) }
                }

                override fun onClosed(code: Int, message: String?) {
                    scope.launch { if (call == generation) lineClosed(code, message) }
                }
            },
        )
        ticker = scope.launch {
            while (isActive) {
                delay(TickMillis)
                tick()
            }
        }
    }

    private fun sendSession() {
        val plan = plan ?: return
        val voice = settings.voice ?: session?.voiceId
        send(RealtimeWire.sessionUpdate(voice, instructions, speed(plan), settings.languageTag, plan.tools))
    }

    private fun resendSession() {
        if (lineReady) sendSession()
    }

    private fun speed(plan: RealtimeVoicePlan): Double = settings.speed.toDouble().takeIf { it > 0 } ?: plan.defaultSpeed ?: 1.0

    private fun onMicrophone(call: Int, chunk: ByteArray) {
        if (call != generation || !lineReady || muted) return
        line?.send(RealtimeWire.appendAudio(Base64.getEncoder().encodeToString(chunk)))
    }

    private fun onEvent(event: RealtimeWire.Event) {
        when (event) {
            RealtimeWire.Event.SessionReady -> if (!lineReady) {
                lineReady = true
                armGreeting()
                refresh()
            }
            is RealtimeWire.Event.ConversationCreated, RealtimeWire.Event.Other, is RealtimeWire.Event.Audio -> Unit
            RealtimeWire.Event.SpeechStarted -> {
                callerSpeaking = true
                callerSpoke = true
                lastCallerAt = clock()
                greeting?.cancel()
                speaker?.interrupt()?.let { heard -> send(RealtimeWire.truncate(heard.itemId, heard.contentIndex, heard.audioEndMs)) }
                refresh()
            }
            RealtimeWire.Event.SpeechStopped -> {
                callerSpeaking = false
                lastCallerAt = clock()
                refresh()
            }
            is RealtimeWire.Event.ResponseCreated -> {
                responding = true
                sawResponse = true
                assistantText = StringBuilder()
                greeting?.cancel()
                refresh()
            }
            is RealtimeWire.Event.ResponseEnded -> {
                responding = false
                refresh()
            }
            is RealtimeWire.Event.AssistantDelta -> {
                assistantText.append(event.delta)
                refresh()
            }
            is RealtimeWire.Event.Transcript -> {
                val text = event.text.trim()
                if (text.isNotEmpty()) {
                    _state.update { it.copy(turns = it.turns + VoiceTurn(event.fromUser, text), lastSaid = if (event.fromUser) text else it.lastSaid) }
                }
            }
            is RealtimeWire.Event.ToolCall -> tool(event)
            is RealtimeWire.Event.Error -> Unit // The line goes on; a fatal one closes it, which [lineClosed] says.
        }
    }

    private fun armGreeting() {
        val plan = plan ?: return
        if (plan.greeting.isBlank()) return
        greeting = scope.launch {
            delay(GreetingGraceMillis)
            if (!callerSpoke && !sawResponse && lineReady) send(RealtimeWire.responseCreate(reply(plan.greeting)))
        }
    }

    /** The session's instructions, then what this one reply is for, as the desktop's `replyInstructions` makes them. */
    private fun reply(instructions: String): String = "${this.instructions}\n\n$instructions"

    // ---- tools ---------------------------------------------------------------------------------------------------

    private fun tool(call: RealtimeWire.Event.ToolCall) {
        val plan = plan ?: return
        lastCallerAt = clock()
        when (call.name) {
            SendTask -> sendTask(call, plan)
            EndTheCall -> {
                answer(call.callId, "{}", respond = false)
                hangUpAfterSpeech()
            }
            StaySilent -> answer(call.callId, buildJsonObject { put("plan", "silent") }.toString(), respond = false)
            RecallTextMessages -> answer(call.callId, recall(), respond = true)
            in ServerReadTools -> serverTool(call, plan)
            else -> answer(call.callId, error(plan.lines.unknownToolErrorTemplate.ifBlank { "There is no tool called {name}." }.replace("{name}", call.name)), respond = true)
        }
    }

    private fun sendTask(call: RealtimeWire.Event.ToolCall, plan: RealtimeVoicePlan) {
        val request = RealtimeWire.text(RealtimeVoiceWire.arguments(call.arguments)["request"])?.trim().orEmpty()
        when {
            request.isEmpty() -> return answer(call.callId, error(plan.lines.sendTaskMissingRequestError.ifBlank { "send_task needs a request." }), respond = true)
            request.length > RealtimeWire.LANDED_CHAR_LIMIT -> return answer(call.callId, error(plan.lines.sendTaskRequestTooLongError.ifBlank { "The request is too long." }), respond = true)
        }
        if (openJobs.isEmpty()) sawRunningSinceSend = false
        when (host.sendTask(request)) {
            TaskSent.UnderWay, TaskSent.Queued -> {
                val receipt = if (agentRunning) plan.queued else plan.underWay
                openJobs += request
                answer(call.callId, receipt.outputJson.ifBlank { "{\"receipt\":\"sent\"}" }, respond = true, instructions = receipt.instructions.takeIf { it.isNotBlank() })
            }
            TaskSent.Refused -> {
                val output = buildJsonObject {
                    put("relay", "unreachable")
                    put("spoken", plan.lines.agentUnreachableAnswer.ifBlank { "The agent could not be reached." })
                    put("errorClass", "SendFailed")
                }
                answer(call.callId, output.toString(), respond = true)
            }
        }
        refresh()
    }

    private fun recall(): String {
        val messages = buildJsonArray {
            host.writtenLines().filter { it.text.isNotBlank() }.takeLast(RecallMessages).forEach { line ->
                add(buildJsonObject {
                    put("id", line.id)
                    put("writer", if (line.fromUser) "them" else "you")
                    put("text", SpokenText.of(line.text, RealtimeWire.LANDED_CHAR_LIMIT).ifBlank { line.text.take(RealtimeWire.LANDED_CHAR_LIMIT) })
                })
            }
        }
        return JsonObject(mapOf("messages" to messages)).toString()
    }

    private fun serverTool(call: RealtimeWire.Event.ToolCall, plan: RealtimeVoicePlan) {
        val minted = session
        val turn = generation
        if (minted?.useServerVoiceTools != true) return answer(call.callId, error(plan.lines.toolFailedError.ifBlank { "That lookup is not available on this call." }), respond = true)
        scope.launch {
            val output = try {
                api.executeServerTool(agentId, call.callId, call.name, call.arguments.ifBlank { "{}" }).ifBlank { "{}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error(plan.lines.toolFailedError.ifBlank { "That lookup failed." })
            }
            if (turn == generation) answer(call.callId, output, respond = true)
        }
    }

    private fun error(message: String): String = JsonObject(mapOf("error" to JsonPrimitive(message))).toString()

    private fun answer(callId: String, output: String, respond: Boolean, instructions: String? = null) {
        send(RealtimeWire.toolOutput(callId, output))
        if (respond) send(RealtimeWire.responseCreate(instructions?.let(::reply)))
    }

    private fun hangUpAfterSpeech() {
        hangingUp?.cancel()
        hangingUp = scope.launch {
            val until = clock() + GoodbyeMillis
            delay(TickMillis * 3)
            while (isActive && (responding || speaker?.busy() == true) && clock() < until) delay(TickMillis)
            end()
        }
    }

    // ---- the agent -----------------------------------------------------------------------------------------------

    /**
     * Where the chat's agent stands: whether it is on a turn ([running]) and the written chat ([lines], oldest first).
     * Told on every change. When a job the call sent has been taken up and the agent's turn has ended, its reply —
     * the agent's lines written since the job went — is told on the line for the voice agent to say.
     */
    fun agent(running: Boolean, lines: List<WrittenLine>) {
        agentRunning = running
        latestLines = lines
        val job = openJobs.lastOrNull() ?: return
        // The job's own message in the chat is where its answer starts; until it is there, the turn is not its turn.
        val at = lines.indexOfLast { it.fromUser && it.text.trim() == job }
        if (at < 0) return
        if (running) {
            sawRunningSinceSend = true
            refresh()
            return
        }
        val replies = lines.drop(at + 1).filter { !it.fromUser && it.text.isNotBlank() }
        if (replies.isEmpty() && !sawRunningSinceSend) return
        val said = replies.joinToString("\n\n") { SpokenText.of(it.text, RealtimeWire.LANDED_CHAR_LIMIT) }.trim()
        val text = said.ifBlank { plan?.lines?.noReplyLine.orEmpty().ifBlank { "The work finished without a written reply. Say that." } }
        openJobs.clear()
        sawRunningSinceSend = false
        landed(text)
        refresh()
    }

    private fun landed(text: String) {
        if (!lineReady) return
        val at = System.currentTimeMillis()
        send(RealtimeWire.workLanded("work-landed-$at-${landedSeq++}", listOf(text.take(RealtimeWire.LANDED_CHAR_LIMIT)), at))
    }

    // ---- the caller ----------------------------------------------------------------------------------------------

    /** The shortcut of the moment: after a failure, calls again; while the agent speaks, lets the caller in. */
    fun tap() {
        when (val now = phase) {
            is VoiceChatPhase.Failed -> if (!now.opensSettings && !now.connectsAccount) start()
            is VoiceChatPhase.Speaking -> speaker?.interrupt()?.let { heard -> send(RealtimeWire.truncate(heard.itemId, heard.contentIndex, heard.audioEndMs)) }
            VoiceChatPhase.Muted -> toggleMute()
            else -> Unit
        }
    }

    /** Mutes the caller, or unmutes them: the line stays up and the agent goes on, but nothing of theirs goes up it. */
    fun toggleMute() {
        if (phase == VoiceChatPhase.Off || phase is VoiceChatPhase.Failed) return
        muted = !muted
        if (muted && lineReady) {
            // The desktop's trailing silence, so a word cut off by the mute is not left hanging as speech.
            send(RealtimeWire.appendAudio(Base64.getEncoder().encodeToString(ByteArray(RealtimeWire.SAMPLE_RATE))))
            callerSpeaking = false
        }
        _state.update { it.copy(muted = muted) }
        refresh()
    }

    /** The microphone permission was refused; [permanently] when Android will no longer ask. */
    fun permissionDenied(permanently: Boolean) {
        leave()
        enter(
            VoiceChatPhase.Failed(
                if (permanently) "Microphone access is off. Tap to open Settings." else "Voice chat needs microphone access.",
                opensSettings = permanently,
            ),
        )
    }

    /** Ends the call from any phase. */
    fun end() {
        if (!_state.value.active) return
        leave()
        enter(VoiceChatPhase.Off)
    }

    /** Ends the call for good: the call's owner is gone. */
    fun release() = end()

    // ---- the phase -----------------------------------------------------------------------------------------------

    private fun tick() {
        val speaking = speaker?.busy() == true
        val level = when {
            speaking -> speaker?.level() ?: 0f
            muted -> 0f
            else -> microphone?.level() ?: 0f
        }
        _state.update { it.copy(level = level) }
        refresh()
        val idle = (session?.idleSeconds?.takeIf { it > 0 }?.times(1000L)) ?: IdleEndMillis
        if (openJobs.isEmpty() && !speaking && !responding && clock() - lastCallerAt >= idle) end()
    }

    private fun refresh() {
        val current = phase
        if (current == VoiceChatPhase.Off || current is VoiceChatPhase.Failed) return
        val next = when {
            !lineReady -> VoiceChatPhase.Connecting
            speaker?.busy() == true -> VoiceChatPhase.Speaking(assistantText.toString().trim())
            callerSpeaking && !muted -> VoiceChatPhase.Listening(heard = true)
            responding -> VoiceChatPhase.Thinking
            openJobs.isNotEmpty() -> VoiceChatPhase.Working(openJobs.last())
            muted -> VoiceChatPhase.Muted
            else -> VoiceChatPhase.Listening(heard = false)
        }
        if (next == current) return
        // Speaking carries the words so far; a change of words alone is not a new phase for the haptics.
        if (next is VoiceChatPhase.Speaking && current is VoiceChatPhase.Speaking) {
            _state.update { it.copy(phase = next) }
            return
        }
        enter(next)
    }

    private fun lineClosed(code: Int, message: String?) {
        if (phase == VoiceChatPhase.Off) return
        val wasReady = lineReady
        line = null
        lineReady = false
        if (code == 1000 && hangingUp != null) return end()
        val said = message?.takeIf { it.isNotBlank() && it != "hang_up" }
        fail(
            when {
                !wasReady && said != null -> "Couldn't connect to Grok Voice: $said"
                !wasReady -> "Couldn't connect to Grok Voice. Your network may block api.x.ai."
                said != null -> "The call dropped: $said"
                else -> "The call dropped."
            },
        )
    }

    private fun fail(message: String) {
        leave()
        enter(VoiceChatPhase.Failed(message))
    }

    private fun send(frame: String) {
        line?.send(frame)
    }

    /** Closes the line, the microphone and the speaker, whichever way the call is left. */
    private fun leave() {
        generation++
        connecting?.cancel(); connecting = null
        ticker?.cancel(); ticker = null
        greeting?.cancel(); greeting = null
        hangingUp?.cancel(); hangingUp = null
        val open = line
        line = null
        lineReady = false
        open?.let { runCatching { it.close() } }
        microphone?.let { runCatching { it.stop() } }
        microphone = null
        speaker?.let { runCatching { it.release() } }
        speaker = null
        callerSpeaking = false
        responding = false
        sawResponse = false
        callerSpoke = false
        openJobs.clear()
    }

    private fun enter(next: VoiceChatPhase) {
        _state.update { it.copy(phase = next, level = if (next is VoiceChatPhase.Listening || next is VoiceChatPhase.Speaking) it.level else 0f) }
        onPhase(next)
    }

    private fun decode(base64: String): ByteArray = runCatching { Base64.getDecoder().decode(base64) }.getOrDefault(ByteArray(0))

    companion object {
        internal const val TickMillis = 80L
        /** How long the line waits for the caller to speak first before the voice agent greets them. */
        internal const val GreetingGraceMillis = 1_200L
        /** How long a goodbye may still be heard before the call hangs up. */
        internal const val GoodbyeMillis = 15_000L
        /** A call nobody speaks into ends itself after about ten minutes, unless the account says otherwise. */
        internal const val IdleEndMillis = 600_000L
        internal const val RecallMessages = 8

        const val SendTask = "send_task"
        const val EndTheCall = "end_the_call"
        const val StaySilent = "stay_silent"
        const val RecallTextMessages = "recall_text_messages"
        /** The read-only tools the account runs for a cloud agent (the desktop's `x2u`). */
        val ServerReadTools = setOf("peek_changes", "peek_runs", "peek_agents", "search_conversations")
    }
}
