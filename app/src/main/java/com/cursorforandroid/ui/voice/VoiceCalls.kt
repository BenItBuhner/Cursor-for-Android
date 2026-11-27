package com.cursorforandroid.ui.voice

import android.content.Context
import android.os.SystemClock
import com.cursorforandroid.data.api.RealtimeVoiceApi
import com.cursorforandroid.data.api.userMessage
import com.cursorforandroid.data.voice.AndroidCallMicrophone
import com.cursorforandroid.data.voice.AndroidCallSpeaker
import com.cursorforandroid.data.voice.RealtimeDialer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The one voice chat the app holds, outside any screen: a call is bound to one chat ([Line]) and lives in the app's
 * scope, so it goes on with the screen off, with the app in the background (where [VoiceCallService] keeps the
 * microphone and the notification), and while the user reads another chat (which shows the call's strip to come
 * back to). Starting a call on another chat ends the one under way, as a phone does.
 *
 * The call itself is Cursor's realtime voice call ([VoiceChat]). What it hands the agent and reads of the chat goes
 * through the bound chat's [Host], its view model. A host that goes (its screen left the back stack) leaves the call
 * up, but a job asked for meanwhile is refused until the chat's screen is back.
 */
class VoiceCalls(
    private val newChat: (scope: CoroutineScope, agentId: String, host: VoiceChat.Host) -> VoiceChat,
    /** Called with true when a call starts and false when it ends, for the foreground service. */
    private val onLive: (Boolean) -> Unit = {},
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** Where the call's voice, speed and language are kept between calls. */
    private val store: VoiceSettingsStore = VoiceSettingsStore.InMemory(),
    /** Keeps a voice picked in the menu on the account, as the desktop's menu does (`SetRealtimeVoice`). */
    private val saveVoice: suspend (agentId: String, voiceId: String) -> Unit = { _, _ -> },
) {
    /** The chat a call is bound to, what it is called, and when the call started ([clock] time, for the timer). */
    data class Line(val agentId: String, val title: String, val startedAtMs: Long)

    /** The chat's side of the call (see [VoiceChat.Host]). */
    interface Host {
        fun sendTask(request: String): VoiceChat.TaskSent
        fun writtenLines(): List<WrittenLine>
    }

    private val _line = MutableStateFlow<Line?>(null)
    val line: StateFlow<Line?> = _line.asStateFlow()
    private val _state = MutableStateFlow(VoiceChatState())
    val state: StateFlow<VoiceChatState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(store.load())
    /** The call's voice, speed and language, as its tray's settings menu sets them; they hold for later calls too. */
    val settings: StateFlow<VoiceCallSettings> = _settings.asStateFlow()

    private val _sentByVoice = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** By chat, the jobs its calls sent as follow-ups: those messages wear Cursor's "Voice call" label. */
    val sentByVoice: StateFlow<Map<String, Set<String>>> = _sentByVoice.asStateFlow()

    private var chat: VoiceChat? = null
    private var callScope: CoroutineScope? = null
    private var mirror: Job? = null
    private var host: Host? = null

    /** Called once for each phase the call enters, for the haptics. */
    var onPhase: (VoiceChatPhase) -> Unit = {}

    /** Whether the call under way is [agentId]'s. */
    fun isOn(agentId: String): Boolean = _line.value?.agentId == agentId && _state.value.active

    /**
     * Starts a call with [agentId]'s agent through [host]; a call with another chat is ended first. The microphone
     * permission is the caller's to have asked for.
     */
    fun start(agentId: String, title: String, host: Host) {
        if (isOn(agentId) && _state.value.phase !is VoiceChatPhase.Failed) return
        end()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val chatHost = object : VoiceChat.Host {
            override fun sendTask(request: String): VoiceChat.TaskSent {
                val sent = this@VoiceCalls.host?.sendTask(request) ?: VoiceChat.TaskSent.Refused
                if (sent != VoiceChat.TaskSent.Refused) _sentByVoice.update { it + (agentId to (it[agentId].orEmpty() + request.trim())) }
                return sent
            }

            override fun writtenLines(): List<WrittenLine> = this@VoiceCalls.host?.writtenLines().orEmpty()
        }
        val next = newChat(scope, agentId, chatHost)
        next.settings = _settings.value
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

    /** [agentId]'s screen is (back) in place: it hosts the call again. */
    fun attach(agentId: String, host: Host) {
        if (_line.value?.agentId != agentId) return
        this.host = host
    }

    /** [host] is going: the call stays up, but has no chat to hand a job to until the screen is back. */
    fun detach(agentId: String, host: Host) {
        if (_line.value?.agentId != agentId || this.host !== host) return
        this.host = null
    }

    /** Where [agentId]'s agent stands, from its host; ignored unless the call is that chat's. */
    fun agent(agentId: String, running: Boolean, lines: List<WrittenLine>) {
        if (_line.value?.agentId != agentId) return
        chat?.agent(running, lines)
    }

    fun title(agentId: String, title: String) {
        val line = _line.value ?: return
        if (line.agentId == agentId && title.isNotBlank() && title != line.title) _line.value = line.copy(title = title)
    }

    fun tap() { chat?.tap() }

    fun toggleMute() { chat?.toggleMute() }

    /** The call's voice, speed and language from now on, this call's and the next. */
    fun setSettings(settings: VoiceCallSettings) {
        val before = _settings.value
        _settings.value = settings
        store.save(settings)
        chat?.settings = settings
        val voice = settings.voice
        val agentId = _line.value?.agentId
        if (voice != null && voice != before.voice && agentId != null) callScope?.launch { runCatching { saveVoice(agentId, voice) } }
    }

    /** The voices the account offers this call; empty until it is minted. */
    fun voices(): List<VoiceOption> = chat?.voices.orEmpty().map { VoiceOption(it.id, it.label) }

    fun permissionDenied(agentId: String, title: String, host: Host, permanently: Boolean) {
        start(agentId, title, host)
        chat?.permissionDenied(permanently)
    }

    /** Hangs up, from wherever: the call's tray, another chat's strip, the notification. */
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
        /** The app's calls: Cursor's realtime voice line, the device's microphone and loudspeaker. */
        fun create(context: Context, api: () -> RealtimeVoiceApi, dialer: () -> RealtimeDialer, onLive: (Boolean) -> Unit): VoiceCalls {
            val app = context.applicationContext
            return VoiceCalls(
                newChat = { scope, agentId, host ->
                    VoiceChat(
                        scope = scope,
                        agentId = agentId,
                        api = api(),
                        dialer = dialer(),
                        newMicrophone = { AndroidCallMicrophone() },
                        newSpeaker = { AndroidCallSpeaker(app) },
                        host = host,
                        clock = SystemClock::elapsedRealtime,
                        errorMessage = { it.userMessage() },
                    )
                },
                onLive = onLive,
                store = VoiceSettingsStore.Prefs(app),
                saveVoice = { agentId, voiceId -> api().setVoice(agentId, voiceId) },
            )
        }
    }
}
