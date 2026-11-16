package com.cursorforandroid.ui.voice

import android.content.Context
import android.os.SystemClock
import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.MediaRecorderCapture
import com.cursorforandroid.data.media.Speaker
import com.cursorforandroid.data.media.TextToSpeechSpeaker
import com.cursorforandroid.ui.components.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one voice chat the app holds, outside any screen: a call is bound to one chat ([Line]) and lives in the app's
 * scope, so it goes on with the screen off, with the app in the background (where [VoiceCallService] keeps the
 * microphone and the notification), and while the user reads another chat (which shows the call's strip to come
 * back to). Starting a call on another chat ends the one under way, as a phone does.
 *
 * What the call says and hears goes through the bound chat's [Host] — its view model, which sends the follow-up and
 * tells the call where the agent's turn stands. A host that goes (its screen left the back stack) puts the call on
 * hold rather than letting it listen with nowhere to send; the chat's screen coming back picks it up again.
 */
class VoiceCalls(
    private val newChat: (scope: CoroutineScope, send: (String) -> Boolean) -> VoiceChat,
    /** Called with true when a call starts and false when it ends, for the foreground service. */
    private val onLive: (Boolean) -> Unit = {},
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** The chat a call is bound to, what it is called, and when the call started ([clock] time, for the timer). */
    data class Line(val agentId: String, val title: String, val startedAtMs: Long)

    /** The chat's side of the call: sends what was said as its next follow-up, false when it could not go. */
    fun interface Host {
        fun send(words: String): Boolean
    }

    private val _line = MutableStateFlow<Line?>(null)
    val line: StateFlow<Line?> = _line.asStateFlow()
    private val _state = MutableStateFlow(VoiceChatState())
    val state: StateFlow<VoiceChatState> = _state.asStateFlow()

    private var chat: VoiceChat? = null
    private var callScope: CoroutineScope? = null
    private var mirror: Job? = null
    private var host: Host? = null

    /** Called once for each turn the call takes, for the haptics. */
    var onPhase: (VoiceChatPhase) -> Unit = {}

    /** Whether the call under way is [agentId]'s. */
    fun isOn(agentId: String): Boolean = _line.value?.agentId == agentId && _state.value.active

    /**
     * Starts a call with [agentId]'s agent through [host]; a call with another chat is ended first. The microphone
     * permission is the caller's to have asked for.
     */
    fun start(agentId: String, title: String, host: Host) {
        if (isOn(agentId)) return
        end()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val next = newChat(scope) { words -> this.host?.send(words) ?: false }
        next.onPhase = { phase ->
            onPhase(phase)
            if (phase == VoiceChatPhase.Off) scope.launch { closeLine(next) }
        }
        chat = next
        callScope = scope
        this.host = host
        _line.value = Line(agentId, title.ifBlank { VoiceChatCopy.UntitledChat }, clock())
        mirror = scope.launch { next.state.collect { _state.value = it } }
        onLive(true)
        next.start()
    }

    /** [agentId]'s screen is (back) in place: it hosts the call again, and a call it was holding listens again. */
    fun attach(agentId: String, host: Host) {
        if (_line.value?.agentId != agentId) return
        val wasAway = this.host == null
        this.host = host
        if (wasAway && _state.value.phase == VoiceChatPhase.Muted) chat?.toggleMute()
    }

    /** [host] is going: the call it hosted holds, mic closed, until the chat is opened again. */
    fun detach(agentId: String, host: Host) {
        if (_line.value?.agentId != agentId || this.host !== host) return
        this.host = null
        if (_state.value.phase is VoiceChatPhase.Listening) chat?.toggleMute()
    }

    /** Where [agentId]'s agent stands, from its host; ignored unless the call is that chat's. */
    fun agent(agentId: String, running: Boolean, replyId: String?, markdown: String?) {
        if (_line.value?.agentId == agentId) chat?.agent(running, replyId, markdown)
    }

    fun title(agentId: String, title: String) {
        val line = _line.value ?: return
        if (line.agentId == agentId && title.isNotBlank() && title != line.title) _line.value = line.copy(title = title)
    }

    fun tap() { chat?.tap() }
    fun toggleMute() { chat?.toggleMute() }
    fun permissionDenied(agentId: String, title: String, host: Host, permanently: Boolean) {
        start(agentId, title, host)
        chat?.permissionDenied(permanently)
    }

    /** Hangs up, from wherever: the call's card, another chat's strip, the notification. */
    fun end() {
        val current = chat ?: return
        current.end()
        closeLine(current)
    }

    private fun closeLine(ended: VoiceChat) {
        if (chat !== ended) return
        chat = null
        host = null
        mirror?.cancel()
        mirror = null
        ended.release()
        callScope?.cancel()
        callScope = null
        _line.value = null
        _state.value = VoiceChatState()
        onLive(false)
    }

    companion object {
        /** The app's calls: the device's microphone and voice, and the account's transcription. */
        fun create(context: Context, transcription: () -> TranscriptionApi, onLive: (Boolean) -> Unit): VoiceCalls {
            val app = context.applicationContext
            return VoiceCalls(
                newChat = { scope, send ->
                    VoiceChat(
                        scope = scope,
                        transcription = transcription(),
                        newCapture = { MediaRecorderCapture(app) as AudioCapture },
                        speaker = TextToSpeechSpeaker(app) as Speaker,
                        send = send,
                        clock = SystemClock::elapsedRealtime,
                        errorMessage = { VoiceInput.messageFor(it) },
                    )
                },
                onLive = onLive,
            )
        }
    }
}
