package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.proto.AgentSchemas
import com.cursorforandroid.data.api.proto.ProtoWire
import com.cursorforandroid.domain.LocalAgentLineKind
import com.cursorforandroid.domain.LocalSessionEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.Base64

class LocalAgentTranscriptTest {

    @Test
    fun `history reads user and assistant text from session blobs, and live events append`() = runBlocking<Unit> {
        val userId = id("user-1")
        val stepId = id("step-1")
        val turnId = id("turn-1")
        val user = ProtoWire.encode(buildJsonObject { put("text", "Fix the nav") }, AgentSchemas.USER_MESSAGE)
        val step = ProtoWire.encode(
            buildJsonObject { put("assistantMessage", buildJsonObject { put("text", "Looking at NavStack.kt") }) },
            AgentSchemas.CONVERSATION_STEP,
        )
        val turn = ProtoWire.encode(
            buildJsonObject {
                put(
                    "agentConversationTurn",
                    buildJsonObject {
                        put("userMessage", userId)
                        put("steps", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(stepId)) })
                    },
                )
            },
            AgentSchemas.CONVERSATION_TURN,
        )
        val blobs = mapOf(userId to user, stepId to step, turnId to turn)
        val history = LocalAgentTranscript.history(listOf(turnId), blobs) { emptyMap() }
        assertThat(history.map { it.kind to it.text }).containsExactly(
            LocalAgentLineKind.USER to "Fix the nav",
            LocalAgentLineKind.ASSISTANT to "Looking at NavStack.kt",
        ).inOrder()

        val live = LocalAgentTranscript.apply(history, LocalSessionEvent.AssistantDelta(" next", false, 5))
        assertThat(live.last().kind).isEqualTo(LocalAgentLineKind.ASSISTANT)
        assertThat(live.last().text).isEqualTo("Looking at NavStack.kt next")
    }

    private fun id(name: String): String = Base64.getEncoder().encodeToString(name.toByteArray())
}
