package com.cursorforandroid.ui.voice

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
 * The app's one call outside any screen: bound to one chat, handing that chat's host the jobs while it is there,
 * ended by a call on another chat, and telling the foreground service when it is live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallsTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val live = mutableListOf<Boolean>()
    private val sent = mutableListOf<String>()
    private val speakers = mutableListOf<ScriptedSpeaker>()
    private val dialers = mutableListOf<ScriptedDialer>()
    private val account = ScriptedVoiceAccount()

    private fun calls(store: VoiceSettingsStore = VoiceSettingsStore.InMemory()) = VoiceCalls(
        newChat = { callScope, agentId, host ->
            VoiceChat(
                scope = callScope,
                agentId = agentId,
                api = account,
                dialer = ScriptedDialer().also { dialers += it },
                newMicrophone = { ScriptedMicrophone() },
                newSpeaker = { ScriptedSpeaker().also { speakers += it } },
                host = host,
                clock = { scope.testScheduler.currentTime },
                errorMessage = { "no" },
            )
        },
        onLive = { live += it },
        clock = { scope.testScheduler.currentTime },
        store = store,
        saveVoice = account::setVoice,
    )

    private fun host(agentId: String) = object : VoiceCalls.Host {
        override fun sendTask(request: String): VoiceChat.TaskSent {
            sent += "$agentId:$request"
            return VoiceChat.TaskSent.UnderWay
        }

        override fun writtenLines(): List<WrittenLine> = emptyList()
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun ScriptedDialer.task(request: String) {
        open()
        ready()
        push("""{"type":"response.function_call_arguments.done","call_id":"c1","name":"send_task","arguments":"{\"request\":\"$request\"}"}""")
    }

    @Test
    fun `a call is bound to its chat, and its line and live flag follow it`() = scope.runTest {
        val calls = calls()
        calls.start("bc-1", "Fix login", host("bc-1"))
        runCurrent()
        assertThat(calls.isOn("bc-1")).isTrue()
        assertThat(calls.isOn("bc-2")).isFalse()
        assertThat(calls.line.value?.title).isEqualTo("Fix login")
        assertThat(account.mints).containsExactly("bc-1" to null)
        assertThat(live).containsExactly(true)

        calls.end()
        runCurrent()
        assertThat(calls.line.value).isNull()
        assertThat(calls.state.value.active).isFalse()
        assertThat(live).containsExactly(true, false).inOrder()
        assertThat(speakers.single().released).isEqualTo(1)
    }

    @Test
    fun `a job goes to the chat's host and is remembered for its label, and none goes while the host is away`() = scope.runTest {
        val calls = calls()
        val first = host("bc-1")
        calls.start("bc-1", "Fix login", first)
        runCurrent()
        dialers.single().task("Run the tests")
        runCurrent()
        assertThat(sent).containsExactly("bc-1:Run the tests")
        assertThat(calls.sentByVoice.value).containsExactly("bc-1", setOf("Run the tests"))

        calls.detach("bc-1", first)
        dialers.single().push("""{"type":"response.function_call_arguments.done","call_id":"c2","name":"send_task","arguments":"{\"request\":\"Ship it\"}"}""")
        runCurrent()
        assertThat(sent).containsExactly("bc-1:Run the tests")
        assertThat(calls.line.value?.agentId).isEqualTo("bc-1")
        calls.end()
    }

    @Test
    fun `starting a call on another chat hangs up the first`() = scope.runTest {
        val calls = calls()
        calls.start("bc-1", "Fix login", host("bc-1"))
        runCurrent()
        calls.start("bc-2", "Ship docs", host("bc-2"))
        runCurrent()
        assertThat(calls.line.value?.agentId).isEqualTo("bc-2")
        assertThat(speakers.first().released).isEqualTo(1)
        assertThat(live).containsExactly(true, false, true).inOrder()
        calls.end()
    }

    @Test
    fun `the agent's progress and title only reach the chat the call is with`() = scope.runTest {
        val calls = calls()
        calls.start("bc-1", "Fix login", host("bc-1"))
        runCurrent()
        calls.agent("bc-2", running = true, lines = listOf(WrittenLine("x", false, "elsewhere")))
        calls.title("bc-1", "Fix the login flow")
        assertThat(calls.line.value?.title).isEqualTo("Fix the login flow")
        calls.title("bc-2", "Other")
        assertThat(calls.line.value?.title).isEqualTo("Fix the login flow")
        calls.end()
    }

    @Test
    fun `the call's settings are kept between calls, and a voice picked is kept on the account`() = scope.runTest {
        val store = VoiceSettingsStore.InMemory()
        val calls = calls(store)
        calls.setSettings(VoiceCallSettings(speed = 1.5f, language = "fr"))
        assertThat(store.load()).isEqualTo(VoiceCallSettings(speed = 1.5f, language = "fr"))
        assertThat(calls.settings.value.languageTag).isEqualTo("fr")
        assertThat(VoiceCallSettings.speedLabel(calls.settings.value.speed)).isEqualTo("1.5x")

        calls.start("bc-1", "Fix login", host("bc-1"))
        runCurrent()
        assertThat(account.mints).containsExactly("bc-1" to "fr")
        assertThat(calls.voices().map { it.label }).containsExactly("Eve", "Rex").inOrder()
        calls.setSettings(calls.settings.value.copy(voice = "rex"))
        runCurrent()
        assertThat(account.voicesSet).containsExactly("rex")
        calls.end()
    }
}
