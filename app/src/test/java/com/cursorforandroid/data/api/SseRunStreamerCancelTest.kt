package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * A stream whose collector has left lets go of its connection at once — not at the next frame the server happens to
 * send. A run between steps sends only keep-alives, which carry no frame; Keep chats live lets go of an unwatched
 * run's stream between looks, and every such stream stayed open, on the server and on a thread here, until the run
 * said something again (or the two-minute read timeout).
 */
class SseRunStreamerCancelTest {

    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        client.dispatcher.cancelAll()
        server.shutdown()
    }

    @Test
    fun `a collector that leaves a quiet stream closes its connection at once`() = runBlocking {
        val keepAlives = ": keep-alive\n\n".repeat(40)
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("id: e-1\nevent: status\ndata: {\"runId\":\"run-1\",\"status\":\"RUNNING\"}\n\n$keepAlives")
                // The status at once, then a few keep-alives every three seconds and nothing else: a run working quietly.
                .throttleBody(100, 3, TimeUnit.SECONDS),
        )
        val streamer = SseRunStreamer(client, apiKeyProvider = { "key" }, urlFor = { _, _ -> server.url("/stream").toString() })

        val first = streamer.stream("bc-1", "run-1", lastEventId = null).first()
        assertThat(first).isInstanceOf(RunStreamEvent.Status::class.java)

        // The dispatcher forgets a call once its headers are in, so the connection is what says the stream is still
        // being read: cancelled, the call closes it; left reading keep-alives, it stays in use.
        val leftAt = System.nanoTime()
        while (client.connectionPool.connectionCount() > 0 && System.nanoTime() - leftAt < 5_000_000_000L) Thread.sleep(10)
        val heldMs = (System.nanoTime() - leftAt) / 1_000_000
        assertThat(client.connectionPool.connectionCount()).isEqualTo(0)
        assertThat(heldMs).isLessThan(1_000L)
    }
}
