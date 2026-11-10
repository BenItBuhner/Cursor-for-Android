package com.cursorforandroid.ui.voice

import com.cursorforandroid.data.api.Transcription
import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.RecordedClip
import com.cursorforandroid.data.media.Speaker
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The voice chat's loop on a virtual clock: a scripted microphone (a meter reading per tick), a scripted
 * transcription, a voice that says nothing until the test lets it finish, and a chat that records what was sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChatTest {

    /** A microphone whose meter reads [levels] tick by tick, then silence. */
    private class ScriptedMic(private val levels: ArrayDeque<Float>, private val clock: () -> Long) : AudioCapture {
        var started = false
        var stopped = false
        var cancelled = false
        private var startedAt = 0L
        override fun start() {
            started = true
            startedAt = clock()
        }
        override fun level(): Float = levels.removeFirstOrNull() ?: 0f
        override fun stop(): RecordedClip? {
            stopped = true
            return RecordedClip(ByteArray(16), "audio/webm", clock() - startedAt)
        }
        override fun cancel() {
            cancelled = true
        }
    }

    private class RecordingSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        var stops = 0
        var released = false
        private var done: ((Boolean) -> Unit)? = null
        override fun speak(text: String, onDone: (Boolean) -> Unit) {
            spoken += text
            done = onDone
        }
        fun finish(spoken: Boolean = true) {
            val callback = done
            done = null
            callback?.invoke(spoken)
        }
        override fun stop() {
            stops++
        }
        override fun release() {
            released = true
        }
    }

    private class Harness(scope: TestScope) {
        val mics = mutableListOf<ScriptedMic>()
        /** The meter script of each microphone the call opens, in order; a microphone past the script hears silence. */
        val scripts = ArrayDeque<List<Float>>()
        var micFails = false
        val heard = ArrayDeque<String>()
        var transcriptionFails: Exception? = null
        val sent = mutableListOf<String>()
        var sendGoes = true
        val speaker = RecordingSpeaker()
        val phases = mutableListOf<VoiceChatPhase>()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val chat = VoiceChat(
            scope = CoroutineScope(scope.backgroundScope.coroutineContext + dispatcher),
            transcription = TranscriptionApi { _, _, _ ->
                transcriptionFails?.let { throw it }
                Transcription(heard.removeFirstOrNull().orEmpty(), null)
            },
            newCapture = {
                if (micFails) object : AudioCapture {
                    override fun start() = throw IllegalStateException("busy")
                    override fun level() = 0f
                    override fun stop(): RecordedClip? = null
                    override fun cancel() = Unit
                } else ScriptedMic(ArrayDeque(scripts.removeFirstOrNull().orEmpty()), { scope.testScheduler.currentTime }).also { mics += it }
            },
            speaker = speaker,
            send = { words -> sent += words; sendGoes },
            clock = { scope.testScheduler.currentTime },
            errorMessage = { "Couldn't reach Cursor." },
            io = dispatcher,
        ).also { it.onPhase = { phase -> phases += phase } }

        val phase: VoiceChatPhase get() = chat.state.value.phase
    }

    private companion object {
        /** Half a second of speech, then quiet: one short utterance. */
        val Utterance: List<Float> = List(7) { 0.6f }
        const val Tick = VoiceChat.TickMillis

        /** From the microphone opening to the utterance being sent: the speech, the pause that ends it, and a tick over. */
        val UtteranceMillis = Utterance.size * Tick + VoiceChat.EndSilenceMillis + Tick
    }

    @Test
    fun `an utterance is heard, sent, answered aloud and the call listens again`() = runTest {
        val h = Harness(this)
        h.scripts += Utterance
        h.heard += "Run the tests again"
        h.chat.agent(running = false, replyId = "a1", markdown = "Earlier reply.")
        h.chat.start()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = false))

        advanceTimeBy(4 * Tick)
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = true))
        assertThat(h.chat.state.value.level).isEqualTo(0.6f)

        advanceTimeBy(UtteranceMillis)
        runCurrent()
        assertThat(h.sent).containsExactly("Run the tests again")
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Working("Run the tests again"))
        assertThat(h.chat.state.value.lastSaid).isEqualTo("Run the tests again")
        assertThat(h.mics.single().stopped).isTrue()

        // The reply on screen when the utterance went out is not an answer to it, running or not.
        h.chat.agent(running = false, replyId = "a1", markdown = "Earlier reply.")
        assertThat(h.phase).isInstanceOf(VoiceChatPhase.Working::class.java)
        h.chat.agent(running = true, replyId = "a1", markdown = "Earlier reply.")
        h.chat.agent(running = true, replyId = "a2", markdown = "All 412")
        assertThat(h.speaker.spoken).isEmpty()

        h.chat.agent(running = false, replyId = "a2", markdown = "All **412** tests pass.")
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Speaking("All 412 tests pass."))
        assertThat(h.speaker.spoken).containsExactly("All 412 tests pass.")
        assertThat(h.mics).hasSize(1)

        h.speaker.finish()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = false))
        assertThat(h.mics).hasSize(2)
    }

    @Test
    fun `a noise shorter than speech does not start an utterance`() = runTest {
        val h = Harness(this)
        h.scripts += listOf(0.9f, 0.9f, 0f, 0.9f, 0f)
        h.chat.start()
        advanceTimeBy(VoiceChat.EndSilenceMillis * 3)
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = false))
        assertThat(h.sent).isEmpty()
    }

    @Test
    fun `a turn that ends without a reply goes back to listening`() = runTest {
        val h = Harness(this)
        h.scripts += Utterance
        h.heard += "Stop the dev server"
        h.chat.start()
        advanceTimeBy(Utterance.size * Tick + UtteranceMillis)
        runCurrent()
        assertThat(h.phase).isInstanceOf(VoiceChatPhase.Working::class.java)
        h.chat.agent(running = true, replyId = null, markdown = null)
        h.chat.agent(running = false, replyId = null, markdown = null)
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = false))
        assertThat(h.speaker.spoken).isEmpty()
    }

    @Test
    fun `a tap sends what has been heard without waiting for the pause`() = runTest {
        val h = Harness(this)
        h.scripts += List(200) { 0.6f }
        h.heard += "Ship it"
        h.chat.start()
        h.chat.tap()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = false))
        advanceTimeBy(10 * Tick)
        h.chat.tap()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Transcribing)
        runCurrent()
        assertThat(h.sent).containsExactly("Ship it")
    }

    @Test
    fun `a tap while the reply is read stops the voice and listens`() = runTest {
        val h = Harness(this)
        h.scripts += Utterance
        h.heard += "What changed?"
        h.chat.start()
        advanceTimeBy(Utterance.size * Tick + UtteranceMillis)
        runCurrent()
        h.chat.agent(running = true, replyId = null, markdown = null)
        h.chat.agent(running = false, replyId = "a1", markdown = "Three files changed.")
        assertThat(h.phase).isInstanceOf(VoiceChatPhase.Speaking::class.java)

        h.chat.tap()
        assertThat(h.speaker.stops).isEqualTo(1)
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = false))
        // The voice's late word that it finished changes nothing: the call has moved on.
        val micsOpen = h.mics.size
        h.speaker.finish()
        assertThat(h.mics).hasSize(micsOpen)
    }

    @Test
    fun `saying goodbye ends the call and sends nothing`() = runTest {
        val h = Harness(this)
        h.scripts += Utterance
        h.heard += "Goodbye."
        h.chat.start()
        advanceTimeBy(Utterance.size * Tick + UtteranceMillis)
        runCurrent()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Off)
        assertThat(h.sent).isEmpty()
    }

    @Test
    fun `an utterance with no words in it is not sent`() = runTest {
        val h = Harness(this)
        h.scripts += Utterance
        h.heard += "  "
        h.chat.start()
        advanceTimeBy(Utterance.size * Tick + UtteranceMillis)
        runCurrent()
        assertThat(h.sent).isEmpty()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = false))
    }

    @Test
    fun `a failed transcription is said and the call resumes by itself`() = runTest {
        val h = Harness(this)
        h.scripts += Utterance
        h.transcriptionFails = IOException("offline")
        h.chat.start()
        advanceTimeBy(Utterance.size * Tick + UtteranceMillis)
        runCurrent()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Failed("Couldn't reach Cursor.", resumes = true))
        h.transcriptionFails = null
        advanceTimeBy(VoiceChat.ErrorShownMillis + 1)
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = false))
    }

    @Test
    fun `a message the chat would not take waits for a tap`() = runTest {
        val h = Harness(this)
        h.scripts += Utterance
        h.heard += "Deploy"
        h.sendGoes = false
        h.chat.start()
        advanceTimeBy(Utterance.size * Tick + UtteranceMillis)
        runCurrent()
        assertThat(h.phase).isInstanceOf(VoiceChatPhase.Failed::class.java)
        advanceTimeBy(VoiceChat.ErrorShownMillis * 3)
        assertThat(h.phase).isInstanceOf(VoiceChatPhase.Failed::class.java)
        h.chat.tap()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = false))
    }

    @Test
    fun `a microphone that will not open is a failure, not a call`() = runTest {
        val h = Harness(this)
        h.micFails = true
        h.chat.start()
        assertThat(h.phase).isInstanceOf(VoiceChatPhase.Failed::class.java)
        assertThat((h.phase as VoiceChatPhase.Failed).resumes).isFalse()
    }

    @Test
    fun `hold closes the microphone and taking it off opens a new one`() = runTest {
        val h = Harness(this)
        h.chat.start()
        h.chat.toggleMute()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Muted)
        assertThat(h.mics.single().cancelled).isTrue()
        advanceTimeBy(VoiceChat.IdleEndMillis * 2)
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Muted)
        h.chat.toggleMute()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = false))
        assertThat(h.mics).hasSize(2)
    }

    @Test
    fun `a quiet room restarts the clip and, after ten minutes, ends the call`() = runTest {
        val h = Harness(this)
        h.chat.start()
        advanceTimeBy(VoiceChat.ReopenMillis + 2 * Tick)
        assertThat(h.mics).hasSize(2)
        assertThat(h.mics.first().cancelled).isTrue()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Listening(heard = false))
        advanceTimeBy(VoiceChat.IdleEndMillis)
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Off)
        assertThat(h.mics.last().cancelled).isTrue()
    }

    @Test
    fun `ending the call closes whatever it held`() = runTest {
        val h = Harness(this)
        h.scripts += Utterance
        h.heard += "Summarise the diff"
        h.chat.start()
        advanceTimeBy(Utterance.size * Tick + UtteranceMillis)
        runCurrent()
        h.chat.agent(running = true, replyId = null, markdown = null)
        h.chat.agent(running = false, replyId = "a1", markdown = "Two files.")
        h.chat.end()
        assertThat(h.phase).isEqualTo(VoiceChatPhase.Off)
        assertThat(h.speaker.stops).isEqualTo(1)
        // A reply that lands after the call ended is not read.
        h.chat.agent(running = false, replyId = "a2", markdown = "More.")
        assertThat(h.speaker.spoken).hasSize(1)
        assertThat(h.phases.last()).isEqualTo(VoiceChatPhase.Off)
    }

    @Test
    fun `a refused microphone permission says where to grant it`() = runTest {
        val h = Harness(this)
        h.chat.permissionDenied(permanently = true)
        val failed = h.phase as VoiceChatPhase.Failed
        assertThat(failed.opensSettings).isTrue()
        h.chat.tap()
        assertThat(h.phase).isEqualTo(failed)
    }
}
