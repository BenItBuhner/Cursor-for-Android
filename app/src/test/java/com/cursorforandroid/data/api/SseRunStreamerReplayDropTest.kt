package com.cursorforandroid.data.api

import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Test

/**
 * A connection that dies after a prefix of the run must come back with `Last-Event-ID` set to the last frame
 * actually read. The stand-in server replays only what follows that id, the way the real stream does, so a client
 * that forgets the header, or that moves the cursor past a frame it never delivered, shows up as a duplicate or a
 * hole in the story.
 */
class SseRunStreamerReplayDropTest {

    private data class Frame(val id: String, val wire: String)

    @Test
    fun `a mid-stream drop resumes from Last-Event-ID without duplicating or dropping events`() = runTest {
        val frames = listOf(
            frame("e-1", "status", """{"runId":"run-1","status":"RUNNING"}"""),
            frame("e-2", "assistant", """{"text":"alpha"}"""),
            frame("e-3", "thinking", """{"text":"beta"}"""),
            frame("e-4", "assistant", """{"text":"gamma"}"""),
            frame("e-5", "result", """{"runId":"run-1","status":"FINISHED","text":"gamma"}"""),
            frame("e-5", "done", "{}"),
        )
        val server = MockWebServer()
        var dropped = false
        val seenIds = mutableListOf<String?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val lastId = request.getHeader("Last-Event-ID")
                seenIds += lastId
                val start = if (lastId == null) {
                    0
                } else {
                    val at = frames.indexOfLast { it.id == lastId }
                    if (at < 0) 0 else at + 1
                }
                val remaining = frames.drop(start)
                if (!dropped) {
                    dropped = true
                    return MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(remaining.take(3).joinToString("") { it.wire })
                        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                }
                return MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(remaining.joinToString("") { it.wire })
            }
        }
        server.start()
        val waits = mutableListOf<Long>()
        val streamer = SseRunStreamer(
            OkHttpClient(),
            { null },
            urlFor = { _, _ -> server.url("/v1/agents/bc-1/runs/run-1/stream").toString() },
            maxAttempts = 4,
            waiter = { waits += it },
            mux = null,
        )

        val events = try {
            streamer.stream("bc-1", "run-1", lastEventId = null).toList()
        } finally {
            server.shutdown()
        }

        val story = events.filterNot { it is RunStreamEvent.Position }
        assertThat(story.map { it::class.simpleName })
            .containsExactly("Status", "Assistant", "Thinking", "Assistant", "Result", "Done")
            .inOrder()
        assertThat(story.filterIsInstance<RunStreamEvent.Assistant>().map { it.text })
            .containsExactly("alpha", "gamma")
            .inOrder()
        assertThat(story.filterIsInstance<RunStreamEvent.Thinking>().map { it.text }).containsExactly("beta")
        assertThat(story.filterIsInstance<RunStreamEvent.Status>().single().status).isEqualTo(RunStatus.RUNNING)
        val result = story.filterIsInstance<RunStreamEvent.Result>().single()
        assertThat(result.status).isEqualTo(RunStatus.FINISHED)
        assertThat(result.text).isEqualTo("gamma")
        assertThat(story.last()).isEqualTo(RunStreamEvent.Done)

        val positions = events.filterIsInstance<RunStreamEvent.Position>().map { it.id }
        assertThat(positions).containsExactly("e-1", "e-2", "e-3", "e-4", "e-5", "e-5").inOrder()

        assertThat(seenIds).containsExactly(null, "e-3").inOrder()
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(waits).hasSize(1)
    }

    private fun frame(id: String, event: String, data: String) =
        Frame(id, "id: $id\nevent: $event\ndata: $data\n\n")
}
