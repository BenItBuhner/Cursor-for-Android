package com.cursorforandroid.data.media

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * A voice chat's voice: [speak] says one piece of text and calls back when it has been said (or could not be), [stop]
 * cuts it off without the call-back, and [release] gives the engine back. Calls come from the main thread, and the
 * call-back arrives on it. An interface so the voice chat can be driven without a speech engine.
 */
interface Speaker {
    fun speak(text: String, onDone: (spoken: Boolean) -> Unit)
    fun stop()
    fun release()
}

/**
 * [Speaker] on the device's own text-to-speech engine: nothing leaves the phone to be spoken, and it works on any
 * account. The engine is bound on the first [speak] (binding takes a moment, so that first text waits for it) and
 * kept until [release]. A device with no engine, or one that refuses the text, answers `spoken = false` at once, and
 * the voice chat carries on in text.
 */
class TextToSpeechSpeaker(context: Context) : Speaker {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: Pair<String, (Boolean) -> Unit>? = null
    /** The utterance whose end is still awaited, and who awaits it. */
    private var current: Pair<String, (Boolean) -> Unit>? = null

    override fun speak(text: String, onDone: (Boolean) -> Unit) {
        stop()
        if (failed) {
            onDone(false)
            return
        }
        val bound = engine
        if (bound == null) {
            pending = text to onDone
            engine = TextToSpeech(appContext) { status -> main.post { onEngineBound(status) } }
            return
        }
        if (!ready) {
            pending = text to onDone
            return
        }
        say(bound, text, onDone)
    }

    private fun onEngineBound(status: Int) {
        val tts = engine ?: return
        val waiting = pending
        pending = null
        if (status != TextToSpeech.SUCCESS) {
            failed = true
            runCatching { tts.shutdown() }
            engine = null
            waiting?.second?.invoke(false)
            return
        }
        ready = true
        runCatching {
            tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId, spoken = true)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId, spoken = false)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId, spoken = false)
        })
        waiting?.let { (text, onDone) -> say(tts, text, onDone) }
    }

    private fun say(tts: TextToSpeech, text: String, onDone: (Boolean) -> Unit) {
        val id = "voice-chat-${ids.incrementAndGet()}"
        current = id to onDone
        val queued = runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) }.getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) {
            current = null
            onDone(false)
        }
    }

    /** The engine's own thread reports the end; only the utterance still awaited counts (a stopped one has no listener left). */
    private fun finished(utteranceId: String?, spoken: Boolean) {
        main.post {
            val awaited = current ?: return@post
            if (awaited.first != utteranceId) return@post
            current = null
            awaited.second(spoken)
        }
    }

    override fun stop() {
        pending = null
        current = null
        if (ready) runCatching { engine?.stop() }
    }

    override fun release() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }
}
