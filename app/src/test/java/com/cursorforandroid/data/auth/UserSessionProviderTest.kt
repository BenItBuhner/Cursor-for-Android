package com.cursorforandroid.data.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** The browser sign-in's account session for calls: kept as released, renewed the desktop's way, dropped when refused. */
class UserSessionProviderTest {

    private val server = MockWebServer()
    private var now = 1_800_000_000_000L
    private var stored: String? = null
    private var allowed = true
    private lateinit var session: UserSessionProvider

    @Before
    fun setUp() {
        server.start()
        session = UserSessionProvider(
            client = OkHttpClient(),
            read = { stored },
            write = { stored = it },
            apiUrl = server.url("/").toString(),
            now = { now },
            sessionAllowed = { allowed },
        )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun noSession(block: suspend () -> Unit): SessionUnavailableException =
        assertThrows(SessionUnavailableException::class.java) { runBlocking { block() } }

    @Test
    fun `without a kept session, the call is told to connect the account`() {
        assertThat(session.connected()).isFalse()
        assertThat(noSession { session.accessToken() }.code).isEqualTo(SessionUnavailableException.NO_ACCOUNT_SESSION)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a kept session is handed out as is while it is fresh`() = runBlocking<Unit> {
        val access = jwt(now / 1000 + 3600)
        session.save(SessionTokens(access, "rt-1"))
        assertThat(session.connected()).isTrue()
        assertThat(session.accessToken()).isEqualTo(access)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a lapsing session is renewed at oauth token with the desktop's client and the new pair is kept`() = runBlocking<Unit> {
        session.save(SessionTokens(jwt(now / 1000 + 30), "rt-1"))
        val renewed = jwt(now / 1000 + 7200)
        server.enqueue(MockResponse().setBody("""{"access_token":"$renewed","refresh_token":"rt-2","token_type":"Bearer"}"""))

        assertThat(session.accessToken()).isEqualTo(renewed)
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/oauth/token")
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertThat(body["grant_type"]?.jsonPrimitive?.content).isEqualTo("refresh_token")
        assertThat(body["client_id"]?.jsonPrimitive?.content).isEqualTo(UserSessionProvider.DESKTOP_CLIENT_ID)
        assertThat(body["refresh_token"]?.jsonPrimitive?.content).isEqualTo("rt-1")
        assertThat(stored).contains("rt-2")
    }

    @Test
    fun `a refused token is renewed on the next ask`() = runBlocking<Unit> {
        val first = jwt(now / 1000 + 3600)
        session.save(SessionTokens(first, "rt-1"))
        session.invalidate()
        val renewed = jwt(now / 1000 + 7200)
        server.enqueue(MockResponse().setBody("""{"access_token":"$renewed"}"""))
        assertThat(session.accessToken()).isEqualTo(renewed)
        // No new refresh token named: the desktop carries on with the access token as the next one.
        session.invalidate()
        server.enqueue(MockResponse().setBody("""{"access_token":"${jwt(now / 1000 + 9000)}"}"""))
        session.accessToken()
        server.takeRequest()
        assertThat(server.takeRequest().body.readUtf8()).contains(renewed)
    }

    @Test
    fun `a refresh Cursor refuses drops the session and asks for the account again`() {
        session.save(SessionTokens(jwt(now / 1000 + 10), "rt-1"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_grant","shouldLogout":true}"""))
        assertThat(noSession { session.accessToken() }.code).isEqualTo(SessionUnavailableException.NO_ACCOUNT_SESSION)
        assertThat(stored).isNull()
    }

    @Test
    fun `a device-bound refresh token is not sent, since this app holds no device key`() {
        session.save(SessionTokens(jwt(now / 1000 + 10), "session_rt_abc"))
        assertThat(noSession { session.accessToken() }.code).isEqualTo(SessionUnavailableException.NO_ACCOUNT_SESSION)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a server error leaves the session kept for a later try`() {
        session.save(SessionTokens(jwt(now / 1000 + 10), "rt-1"))
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(noSession { session.accessToken() }.code).isNull()
        assertThat(session.connected()).isTrue()
    }

    @Test
    fun `nothing is handed out while Extended mode is off`() {
        session.save(SessionTokens(jwt(now / 1000 + 3600), "rt-1"))
        allowed = false
        assertThat(noSession { session.accessToken() }.code).isEqualTo(SessionUnavailableException.EXTENDED_MODE_OFF)
    }

    private fun jwt(expSeconds: Long): String = "${base64Url("""{"alg":"HS256"}""")}.${base64Url("""{"sub":"auth0|u","exp":$expSeconds}""")}.sig"

    private fun base64Url(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
}
