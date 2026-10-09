package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Query strings the documented list and usage endpoints take (`prUrl`, `runId`). */
class CursorApiQueryTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun api(): CursorApi = CursorApiFactory.retrofit(OkHttpClient(), server.url("/").toString())

    @Test
    fun `listAgents sends prUrl when asked and omits it otherwise`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        api().listAgents(limit = 20, cursor = "c1", includeArchived = false, prUrl = "https://github.com/acme/app/pull/7")
        val withPr = server.takeRequest()
        assertThat(withPr.path).contains("prUrl=https%3A%2F%2Fgithub.com%2Facme%2Fapp%2Fpull%2F7")
        assertThat(withPr.path).contains("limit=20")
        assertThat(withPr.path).contains("cursor=c1")
        assertThat(withPr.path).contains("includeArchived=false")

        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        api().listAgents(limit = 10, cursor = null, includeArchived = true)
        val without = server.takeRequest()
        assertThat(without.path).doesNotContain("prUrl")
        assertThat(without.path).contains("/v1/agents")
    }

    @Test
    fun `usage sends runId when asked and omits it otherwise`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"totalUsage":{},"runs":[]}"""))
        api().usage("bc-1", "run-9")
        assertThat(server.takeRequest().path).isEqualTo("/v1/agents/bc-1/usage?runId=run-9")

        server.enqueue(MockResponse().setBody("""{"totalUsage":{},"runs":[]}"""))
        api().usage("bc-1")
        assertThat(server.takeRequest().path).isEqualTo("/v1/agents/bc-1/usage")
    }
}
