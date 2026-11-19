package com.cursorforandroid.ui.voice

import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.Speaker
import com.cursorforandroid.domain.SpokenText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a voice chat stands. Exactly one of these at a time; the microphone is open in [Listening] alone. */
sealed interface VoiceChatPhase {
    /** No call. */
    data object Off : VoiceChatPhase

    /** The microphone is open and waiting; [heard] once speech has started, after which a pause ends the utterance. */
    data class Listening(val heard: Boolean = false) : VoiceChatPhase

    /** The utterance is with Cursor's transcription. */
    data object Transcribing : VoiceChatPhase

    /** [said] went to the agent, which is on its turn. */
    data class Working(val said: String) : VoiceChatPhase

    /** The agent's reply is being read out as [text]. */
    data class Speaking(val text: String) : VoiceChatPhase

    /** The call is held: the microphone is closed until it is taken off hold. */
    data object Muted : VoiceChatPhase

    /**
     * Something did not work. [resumes]: the call goes back to listening by itself after a moment; otherwise it waits
     * for a tap. [opensSettings]: the microphone permission is off for good, and the tap opens the app's settings page.
     */
    data class Failed(val message: String, val resumes: Boolean = false, val opensSettings: Boolean = false) : VoiceChatPhase
}

/** A voice chat as the screen draws it: its [phase], the microphone's [level] (0 to 1) while it is open, and what was last heard. */
data class VoiceChatState(
    val phase: VoiceChatPhase = VoiceChatPhase.Off,
    val level: Float = 0f,
    val lastSaid: String? = null,
) {
    val active: Boolean get() = phase != VoiceChatPhase.Off
}

/**
 * A hands-free conversation with the agent of one chat, as a loop of what the app can already do: listen until the
 * speaker pauses, transcribe the utterance (Cursor's `TranscribeAudio`, the composer's dictation), send it as a
 * follow-up ([send]), wait for the agent's turn to end ([agent]), read its reply aloud ([speaker]) and listen again.
 * Nothing is typed and nothing is tapped in between; a tap ([tap]) is only ever a shortcut — send now, stop talking.
 *
 * This is not Cursor's own Voice Chat, which holds a speech-to-speech call with a voice agent; it is the same
 * conversation with the turns taken in the open, on the endpoints this app is known to speak.
 *
 * Every state the call can be in is a [VoiceChatPhase]; the microphone, the request and the voice each belong to one
 * phase and are closed on the way out of it, whichever way that is. All of it runs on [scope]'s (main) dispatcher;
 * [io] is where the recorder is stopped and its file read back.
 */
class VoiceChat(
    private val scope: CoroutineScope,
    private val transcription: TranscriptionApi,
    private val newCapture: () -> AudioCapture,
    private val speaker: Speaker,
    /** Sends [String] as the chat's next follow-up; false when it could not go (the composer's own notice says why). */
    private val send: (String) -> Boolean,
    private val clock: () -> Long,
    /** A failed transcription in a few words. */
    private val errorMessage: (Throwable) -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(VoiceChatState())
    val state: StateFlow<VoiceChatState> = _state.asStateFlow()

    /** Called once for each turn the call takes, for the haptics. */
    var onPhase: (VoiceChatPhase) -> Unit = {}

    private var capture: AudioCapture? = null
    private var job: Job? = null
    /** Counts the phases entered, so a call-back from a phase that has been left is told from one that still counts. */
    private var entered = 0
    /** The newest reply the chat has shown, and the one it showed when the last utterance was sent. */
    private var latestReplyId: String? = null
    private var replyAtSend: String? = null
    private var sawRunning = false

    private val phase: VoiceChatPhase get() = _state.value.phase

    /** Starts the call; does nothing while one is under way. */
    fun start() {
        if (_state.value.active) return
        _state.value = VoiceChatState()
        listen()
    }

    /** Ends the call from any phase: the microphone closes, a request is dropped, the voice stops. */
    fun end() {
        if (!_state.value.active) return
        leave()
        enter(VoiceChatPhase.Off)
    }

    /** Ends the call and lets the voice go, for good: the screen that owned this is gone. */
    fun release() {
        end()
        speaker.release()
    }

    /**
     * The shortcut of the moment: while an utterance is being heard, send it now rather than after the pause; while
     * the reply is being read, stop reading and listen; on hold or after a failure, listen again.
     */
    fun tap() {
        when (val now = phase) {
            is VoiceChatPhase.Listening -> if (now.heard) finish()
            is VoiceChatPhase.Speaking, VoiceChatPhase.Muted -> listen()
            is VoiceChatPhase.Failed -> if (!now.opensSettings) listen()
            VoiceChatPhase.Off, VoiceChatPhase.Transcribing, is VoiceChatPhase.Working -> Unit
        }
    }

    /** Puts a listening call on hold, or takes it off; nothing while the agent works or speaks, which hold nothing open. */
    fun toggleMute() {
        when (phase) {
            is VoiceChatPhase.Listening -> {
                leave()
                enter(VoiceChatPhase.Muted)
            }
            VoiceChatPhase.Muted -> listen()
            else -> Unit
        }
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

    /**
     * What the chat shows of the agent: whether it is on a turn ([running]) and its newest reply ([replyId], [markdown]).
     * Told on every change. While the call waits on the agent, a turn that has ended with a reply newer than the one
     * on screen when the utterance was sent is read aloud; a turn that ended without one goes back to listening.
     */
    fun agent(running: Boolean, replyId: String?, markdown: String?) {
        latestReplyId = replyId
        if (phase !is VoiceChatPhase.Working) return
        if (running) {
            sawRunning = true
            return
        }
        val fresh = replyId != null && replyId != replyAtSend && !markdown.isNullOrBlank()
        when {
            fresh -> speak(markdown.orEmpty())
            sawRunning -> listen()
        }
    }

    private fun listen() {
        leave()
        val next = newCapture()
        try {
            next.start()
        } catch (e: Exception) {
            enter(VoiceChatPhase.Failed("Couldn't open the microphone. Another app may be using it."))
            return
        }
        capture = next
        enter(VoiceChatPhase.Listening())
        val openedAt = clock()
        job = scope.launch {
            var mic = next
            var micOpenedAt = openedAt
            var voiced = 0
            var heard = false
            var lastVoiceAt = openedAt
            while (isActive) {
                delay(TickMillis)
                val now = clock()
                val level = mic.level()
                _state.update { it.copy(level = level) }
                if (level >= SpeechLevel) {
                    voiced++
                    lastVoiceAt = now
                } else if (!heard) {
                    voiced = 0
                }
                if (!heard && voiced >= SpeechTicks) {
                    heard = true
                    _state.update { it.copy(phase = VoiceChatPhase.Listening(heard = true)) }
                }
                when {
                    heard && (now - lastVoiceAt >= EndSilenceMillis || now - micOpenedAt >= MaxUtteranceMillis) -> {
                        finish()
                        return@launch
                    }
                    !heard && now - openedAt >= IdleEndMillis -> {
                        end()
                        return@launch
                    }
                    // Nothing said for a while: start the clip again, so an utterance never carries minutes of room noise.
                    !heard && now - micOpenedAt >= ReopenMillis -> {
                        runCatching { mic.cancel() }
                        val fresh = newCapture()
                        try {
                            fresh.start()
                        } catch (e: Exception) {
                            capture = null
                            enter(VoiceChatPhase.Failed("Couldn't open the microphone. Another app may be using it."))
                            return@launch
                        }
                        mic = fresh
                        capture = fresh
                        micOpenedAt = now
                    }
                }
            }
        }
    }

    /** Closes the utterance, transcribes it and sends it. */
    private fun finish() {
        val recording = capture ?: return
        capture = null
        job?.cancel()
        enter(VoiceChatPhase.Transcribing)
        val turn = entered
        job = scope.launch {
            try {
                val clip = withContext(io) { recording.stop() }
                if (clip == null || clip.durationMillis < MinClipMillis) {
                    if (turn == entered) listen()
                    return@launch
                }
                val words = transcription.transcribe(clip.audio, clip.mimeType, language = null).text.trim()
                if (turn != entered) return@launch
                when {
                    words.isEmpty() -> listen()
                    SpokenText.isHangUp(words) -> end()
                    else -> {
                        replyAtSend = latestReplyId
                        sawRunning = false
                        _state.update { it.copy(lastSaid = words) }
                        if (send(words)) enter(VoiceChatPhase.Working(words))
                        else enter(VoiceChatPhase.Failed("That didn't send. Tap to try again."))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (turn == entered) failAndResume(errorMessage(e))
            }
        }
    }

    private fun speak(markdown: String) {
        leave()
        val text = SpokenText.of(markdown)
        if (text.isEmpty()) {
            listen()
            return
        }
        enter(VoiceChatPhase.Speaking(text))
        val turn = entered
        // Said or not (no engine on this device), the reply is in the chat; the call carries on either way.
        speaker.speak(text) { if (turn == entered) listen() }
    }

    private fun failAndResume(message: String) {
        leave()
        enter(VoiceChatPhase.Failed(message, resumes = true))
        val turn = entered
        job = scope.launch {
            delay(ErrorShownMillis)
            if (turn == entered) listen()
        }
    }

    /** Closes whatever the phase being left holds open. */
    private fun leave() {
        job?.cancel()
        job = null
        capture?.let { runCatching { it.cancel() } }
        capture = null
        if (phase is VoiceChatPhase.Speaking) speaker.stop()
    }

    private fun enter(next: VoiceChatPhase) {
        entered++
        _state.update { it.copy(phase = next, level = 0f) }
        onPhase(next)
    }

    companion object {
        internal const val TickMillis = 80L

        /** The meter reading (see `MediaRecorderCapture.normalizedLevel`) from which a tick counts as speech. */
        internal const val SpeechLevel = 0.28f

        /** Ticks of speech in a row before the utterance has begun: a door or a key press is shorter. */
        internal const val SpeechTicks = 3

        /** The pause that ends an utterance. */
        internal const val EndSilenceMillis = 1_400L
        internal const val MaxUtteranceMillis = 120_000L
        internal const val ReopenMillis = 20_000L

        /** A call nobody speaks into ends itself, as Cursor's does after about ten minutes. */
        internal const val IdleEndMillis = 600_000L
        internal const val MinClipMillis = 500L
        internal const val ErrorShownMillis = 4_000L
    }
}
