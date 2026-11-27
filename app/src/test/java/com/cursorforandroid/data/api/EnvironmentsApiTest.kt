package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class EnvironmentsApiTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun api() = EnvironmentsApi.retrofit(OkHttpClient(), server.url("/").toString())

    @Test
    fun `list reads a page and an unknown field does not fail it`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"env_1","name":"web","owner":"team","repos":[{"url":"https://github.com/a/b"}],"extra":1}],"nextCursor":"n2"}"""))
        val page = api().list(owner = "team", cursor = "c1")
        assertThat(page.items.single().repos.single().url).isEqualTo("https://github.com/a/b")
        assertThat(page.nextCursor).isEqualTo("n2")
        val path = server.takeRequest().path
        assertThat(path).startsWith("/v1/environments?")
        assertThat(path).contains("owner=team")
        assertThat(path).contains("cursor=c1")
    }

    @Test
    fun `builds parse status and failure, in progress has no completedAt`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"b2","status":"IN_PROGRESS"},{"id":"b1","status":"FAILED","failure":{"type":"INSTALL_FAILED","code":"environment_json_invalid"},"completedAt":"2026-11-01T00:00:00Z"}]}"""))
        val builds = api().builds("env_1", status = null).items
        assertThat(builds[0].inProgress).isTrue()
        assertThat(builds[0].completedAt).isNull()
        assertThat(builds[1].failure?.code).isEqualTo("environment_json_invalid")
        assertThat(server.takeRequest().path).isEqualTo("/v1/environments/env_1/builds?limit=10")
    }

    @Test
    fun `start build posts to builds`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"id":"b3","environmentId":"env_1"}"""))
        assertThat(api().startBuild("env_1").id).isEqualTo("b3")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/v1/environments/env_1/builds")
    }

    @Test
    fun `put secret sends value and type, delete hits the name`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"secv_1","name":"API_TOKEN","type":"runtime_secret","repos":[]}"""))
        val secret = api().putSecret("env_1", "API_TOKEN", PutSecretRequestDto(value = "s3cret", type = "runtime_secret"))
        assertThat(secret.id).isEqualTo("secv_1")
        val put = server.takeRequest()
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.body.readUtf8()).contains("\"value\":\"s3cret\"")

        server.enqueue(MockResponse().setBody("""{"name":"API_TOKEN"}"""))
        api().deleteSecret("env_1", "API_TOKEN")
        val delete = server.takeRequest()
        assertThat(delete.method).isEqualTo("DELETE")
        assertThat(delete.path).isEqualTo("/v1/environments/env_1/secrets/API_TOKEN")
    }

    @Test
    fun `secret names follow the documented rules`() {
        assertThat(EnvironmentsApi.isValidSecretName("API_TOKEN")).isTrue()
        assertThat(EnvironmentsApi.isValidSecretName("_x1")).isTrue()
        assertThat(EnvironmentsApi.isValidSecretName("1ABC")).isFalse()
        assertThat(EnvironmentsApi.isValidSecretName("A-B")).isFalse()
        assertThat(EnvironmentsApi.isValidSecretName("https_proxy")).isFalse()
        assertThat(EnvironmentsApi.isValidSecretName("")).isFalse()
    }
}
