package com.cursorforandroid.ui.voice

import com.cursorforandroid.data.api.Transcription
import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.media.AudioCapture
import com.cursorforandroid.data.media.RecordedClip
import com.cursorforandroid.data.media.Speaker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The app's one call outside any screen: bound to one chat, held while that chat's host is gone and picked up when it
 * comes back, ended by a call on another chat, and telling the foreground service when it is live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallsTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val live = mutableListOf<Boolean>()
    private val opened = mutableListOf<String>()

    private object QuietMic : AudioCapture {
        override fun start() = Unit
        override fun level() = 0f
        override fun stop(): RecordedClip? = null
        override fun cancel() = Unit
    }

    private class Voice : Speaker {
        var released = 0
        override fun speak(text: String, onDone: (Boolean) -> Unit) = onDone(true)
        override fun stop() = Unit
        override fun release() { released++ }
    }

    private val voices = mutableListOf<Voice>()

    private fun calls() = VoiceCalls(
        newChat = { callScope, send ->
            VoiceChat(
                scope = callScope,
                transcription = TranscriptionApi { _, _, _ -> Transcription("", null) },
                newCapture = { QuietMic },
                speaker = Voice().also { voices += it },
                send = send,
                clock = { scope.testScheduler.currentTime },
                errorMessage = { "no" },
                io = dispatcher,
            )
        },
        onLive = { live += it },
        clock = { scope.testScheduler.currentTime },
    )

    private fun host(agentId: String) = VoiceCalls.Host { words -> opened += "$agentId:$words"; true }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a call is bound to its chat, and its line and live flag follow it`() = scope.runTest {
        val calls = calls()
        calls.start("bc-1", "Fix login", host("bc-1"))
        runCurrent()
        assertThat(calls.isOn("bc-1")).isTrue()
        assertThat(calls.isOn("bc-2")).isFalse()
        assertThat(calls.line.value?.title).isEqualTo("Fix login")
        assertThat(calls.state.value.phase).isEqualTo(VoiceChatPhase.Listening())
        assertThat(live).containsExactly(true)

        calls.end()
        runCurrent()
        assertThat(calls.line.value).isNull()
        assertThat(calls.state.value.active).isFalse()
        assertThat(live).containsExactly(true, false).inOrder()
        assertThat(voices.single().released).isEqualTo(1)
    }

    @Test
    fun `the chat's screen leaving holds the call, and its coming back takes it off hold`() = scope.runTest {
        val calls = calls()
        val first = host("bc-1")
        calls.start("bc-1", "Fix login", first)
        runCurrent()

        calls.detach("bc-1", first)
        runCurrent()
        assertThat(calls.state.value.phase).isEqualTo(VoiceChatPhase.Muted)
        assertThat(calls.line.value?.agentId).isEqualTo("bc-1")

        // Another chat's host is not this call's.
        calls.attach("bc-2", host("bc-2"))
        assertThat(calls.state.value.phase).isEqualTo(VoiceChatPhase.Muted)

        calls.attach("bc-1", host("bc-1"))
        runCurrent()
        assertThat(calls.state.value.phase).isEqualTo(VoiceChatPhase.Listening())
    }

    @Test
    fun `a stale host going does not hold a call a newer screen hosts`() = scope.runTest {
        val calls = calls()
        val old = host("bc-1")
        calls.start("bc-1", "Fix login", old)
        val fresh = host("bc-1")
        calls.attach("bc-1", fresh)
        calls.detach("bc-1", old)
        runCurrent()
        assertThat(calls.state.value.phase).isEqualTo(VoiceChatPhase.Listening())
    }

    @Test
    fun `starting a call on another chat hangs up the first`() = scope.runTest {
        val calls = calls()
        calls.start("bc-1", "Fix login", host("bc-1"))
        calls.start("bc-2", "Ship docs", host("bc-2"))
        runCurrent()
        assertThat(calls.line.value?.agentId).isEqualTo("bc-2")
        assertThat(voices.first().released).isEqualTo(1)
        assertThat(live).containsExactly(true, false, true).inOrder()
    }

    @Test
    fun `the agent's progress only reaches the chat the call is with`() = scope.runTest {
        val calls = calls()
        calls.start("bc-1", "Fix login", host("bc-1"))
        runCurrent()
        calls.agent("bc-2", running = true, replyId = "x", markdown = "elsewhere")
        assertThat(calls.state.value.phase).isEqualTo(VoiceChatPhase.Listening())
        calls.title("bc-1", "Fix the login flow")
        assertThat(calls.line.value?.title).isEqualTo("Fix the login flow")
        calls.title("bc-2", "Other")
        assertThat(calls.line.value?.title).isEqualTo("Fix the login flow")
    }

    @Test
    fun `the call's settings are kept between calls, and what a call sent is remembered by chat`() = scope.runTest {
        val store = VoiceSettingsStore.InMemory()
        val calls = VoiceCalls(
            newChat = { callScope, send ->
                VoiceChat(callScope, TranscriptionApi { _, _, _ -> Transcription("", null) }, { QuietMic }, Voice(), send, clock = { scope.testScheduler.currentTime }, errorMessage = { "no" }, io = dispatcher)
            },
            clock = { scope.testScheduler.currentTime },
            store = store,
        )
        calls.setSettings(VoiceCallSettings(speed = 1.5f, language = "fr"))
        assertThat(store.load()).isEqualTo(VoiceCallSettings(speed = 1.5f, language = "fr"))
        assertThat(calls.settings.value.languageTag).isEqualTo("fr")
        assertThat(VoiceCallSettings.speedLabel(calls.settings.value.speed)).isEqualTo("1.5x")
        assertThat(VoiceCallSettings.languageLabel("fr")).isEqualTo("French")
        assertThat(calls.sentByVoice.value).isEmpty()
    }
}
