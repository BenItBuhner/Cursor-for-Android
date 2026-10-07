package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Wire names and numbers for Oct 6 computers, pairing, and local sessions. */
class ComputerTest {

    @Test
    fun `presence pairing trust and session status parse names and numbers`() {
        assertThat(ComputerPresence.parse("REMOTE_AGENT_HOST_TARGET_PRESENCE_ONLINE")).isEqualTo(ComputerPresence.ONLINE)
        assertThat(ComputerPresence.parse("OFFLINE")).isEqualTo(ComputerPresence.OFFLINE)
        assertThat(ComputerPresence.parse("2")).isEqualTo(ComputerPresence.OFFLINE)
        assertThat(ComputerPresence.parse(null)).isEqualTo(ComputerPresence.UNKNOWN)
        assertThat(ComputerPresence.parse("later")).isEqualTo(ComputerPresence.UNKNOWN)

        assertThat(ComputerPairing.parse("REMOTE_AGENT_HOST_CONTROLLER_PAIRING_PAIRED")).isEqualTo(ComputerPairing.PAIRED)
        assertThat(ComputerPairing.parse("PENDING")).isEqualTo(ComputerPairing.PENDING)
        assertThat(ComputerPairing.parse("3")).isEqualTo(ComputerPairing.UNPAIRED)
        assertThat(ComputerPairing.PAIRED.canInbox).isTrue()
        assertThat(ComputerPairing.UNPAIRED.canRequest).isTrue()
        assertThat(ComputerPairing.PENDING.canRequest).isFalse()

        assertThat(ControllerTrust.parse("CONTROLLER_TRUST_STATUS_TRUSTED")).isEqualTo(ControllerTrust.TRUSTED)
        assertThat(ControllerTrust.parse("PENDING")).isEqualTo(ControllerTrust.PENDING)
        assertThat(ControllerTrust.parse("2")).isEqualTo(ControllerTrust.TRUSTED)
        assertThat(ControllerTrust.PENDING.awaitingDesktop).isTrue()
        assertThat(ControllerTrust.TRUSTED.paired).isTrue()
        assertThat(ControllerTrust.NONE.paired).isFalse()

        assertThat(LocalAgentStatus.parse("AGENT_HOST_SESSION_STATUS_RUNNING")).isEqualTo(LocalAgentStatus.RUNNING)
        assertThat(LocalAgentStatus.parse("AWAITING_INPUT")).isEqualTo(LocalAgentStatus.AWAITING_INPUT)
        assertThat(LocalAgentStatus.parse("1")).isEqualTo(LocalAgentStatus.IDLE)
    }

    @Test
    fun `a local session id is not a cloud composer id`() {
        val session = LocalAgentSession("sess-1", "Fix the nav", lastEventId = 9)
        assertThat(session.sessionId.startsWith("bc-")).isFalse()
        assertThat(session.lastEventId).isEqualTo(9L)
    }
}
