package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.proto.AgentHostSchemas
import com.cursorforandroid.data.api.proto.ProtoWire
import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.ComputerPairing
import com.cursorforandroid.domain.ComputerPresence
import com.cursorforandroid.domain.ControllerTrust
import com.cursorforandroid.domain.LocalAgentStatus
import com.cursorforandroid.domain.LocalSessionEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** `aiserver.v1.RemoteAgentHostPresenceService` over Connect JSON, as desktop 3.23.23 issues it. */
class RemoteAgentHostApiTest {

    private val server = MockWebServer()
    private lateinit var api: ConnectRemoteAgentHostApi

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient()
        val base = server.url("/").toString()
        api = ConnectRemoteAgentHostApi(
            ConnectJsonClient(client, base),
            SessionTokenProvider(client, apiKeyProvider = { "key_abc" }, apiUrl = base, now = { 0L }),
        )
        server.enqueue(MockResponse().setBody("""{"accessToken":"session-1","refreshToken":"rt"}"""))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `the computer list paginates and maps presence and pairing`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"targets":[{"targetId":"desk-1","displayName":"Bennett's Mac","agentHostVersion":"3.23.23","presence":"REMOTE_AGENT_HOST_TARGET_PRESENCE_ONLINE","controllerPairing":"REMOTE_AGENT_HOST_CONTROLLER_PAIRING_UNPAIRED"}],"nextPageToken":"p2"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"targets":[{"targetId":"desk-2","displayName":"Studio","presence":2,"controllerPairing":1,"enrollmentGeneration":3}]}""",
            ),
        )

        val computers = api.listComputers("thumb-1")

        server.takeRequest()
        val first = server.takeRequest()
        assertThat(first.path).isEqualTo("/aiserver.v1.RemoteAgentHostPresenceService/ListSharedTargets")
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer session-1")
        assertThat(first.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        assertThat(first.getHeader("User-Agent").orEmpty()).doesNotContain("iPhone")
        assertThat(first.getHeader("User-Agent").orEmpty()).doesNotContain("Darwin")
        assertThat(first.body.readUtf8()).isEqualTo("""{"pageSize":100,"controllerThumbprint":"thumb-1"}""")
        val second = server.takeRequest()
        assertThat(second.body.readUtf8()).isEqualTo("""{"pageSize":100,"pageToken":"p2","controllerThumbprint":"thumb-1"}""")
        assertThat(computers.map { it.targetId }).containsExactly("desk-1", "desk-2").inOrder()
        assertThat(computers[0].displayName).isEqualTo("Bennett's Mac")
        assertThat(computers[0].presence).isEqualTo(ComputerPresence.ONLINE)
        assertThat(computers[0].pairing).isEqualTo(ComputerPairing.UNPAIRED)
        assertThat(computers[1].presence).isEqualTo(ComputerPresence.OFFLINE)
        assertThat(computers[1].pairing).isEqualTo(ComputerPairing.PAIRED)
        assertThat(computers[1].enrollmentGeneration).isEqualTo(3)
    }

    @Test
    fun `TrustController sends the public JWK and the desktop returns a verification code`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setBody(
                """{"status":"CONTROLLER_TRUST_STATUS_PENDING","thumbprint":"thumb-1","challengeId":"ch-9","verificationCode":"1234-5678"}""",
            ),
        )

        val challenge = api.trustController("desk-1", """{"kty":"EC","crv":"P-256","x":"x","y":"y"}""", "Pixel 9")

        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.RemoteAgentHostPresenceService/TrustController")
        assertThat(request.body.readUtf8()).isEqualTo(
            """{"publicKeyJwk":"{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"x\",\"y\":\"y\"}","label":"Pixel 9","targetId":"desk-1"}""",
        )
        assertThat(challenge.status).isEqualTo(ControllerTrust.PENDING)
        assertThat(challenge.verificationCode).isEqualTo("1234-5678")
        assertThat(challenge.challengeId).isEqualTo("ch-9")
    }

    @Test
    fun `CallAgentHost LIST_SESSIONS decodes the inner protobuf, including an empty answer`() = runBlocking<Unit> {
        val payload = ProtoWire.encode(
            buildJsonObject {
                put(
                    "sessions",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("sessionId", "sess-1")
                                put("title", "Fix the nav")
                                put("status", 2)
                            },
                        )
                    },
                )
            },
            AgentHostSchemas.LIST_SESSIONS_RESPONSE,
        )
        server.enqueue(MockResponse().setBody("""{"data":"${Base64.getEncoder().encodeToString(payload)}"}"""))

        val sessions = api.listSessions("desk-1")

        server.takeRequest()
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/aiserver.v1.RemoteAgentHostPresenceService/CallAgentHost")
        assertThat(request.body.readUtf8()).isEqualTo(
            """{"targetId":"desk-1","method":"REMOTE_AGENT_HOST_CONTROLLER_METHOD_LIST_SESSIONS"}""",
        )
        assertThat(sessions).hasSize(1)
        assertThat(sessions[0].sessionId).isEqualTo("sess-1")
        assertThat(sessions[0].title).isEqualTo("Fix the nav")
        assertThat(sessions[0].status).isEqualTo(LocalAgentStatus.RUNNING)

        server.enqueue(MockResponse().setBody("{}"))
        assertThat(api.listSessions("desk-1")).isEmpty()
    }

    @Test
    fun `SEND_MESSAGE and CREATE_SESSION wrap protobuf data on CallAgentHost`() = runBlocking<Unit> {
        val turn = ProtoWire.encode(
            buildJsonObject { put("turnId", "turn-3") },
            AgentHostSchemas.SEND_MESSAGE_RESPONSE,
        )
        server.enqueue(MockResponse().setBody("""{"data":"${Base64.getEncoder().encodeToString(turn)}"}"""))
        assertThat(api.sendMessage("desk-1", "sess-1", "keep going")).isEqualTo("turn-3")
        server.takeRequest()
        val send = server.takeRequest().body.readUtf8()
        assertThat(send).contains("REMOTE_AGENT_HOST_CONTROLLER_METHOD_SEND_MESSAGE")
        assertThat(send).contains("\"data\":")

        server.enqueue(MockResponse().setBody("{}"))
        val sessionId = api.createSession("desk-1", "client-1", listOf("/workspace"))
        assertThat(sessionId).isNotEmpty()
        val create = server.takeRequest().body.readUtf8()
        assertThat(create).contains("REMOTE_AGENT_HOST_CONTROLLER_METHOD_CREATE_SESSION")
        assertThat(create).contains("\"data\":")
    }

    @Test
    fun `StreamAgentHost WATCH_SESSIONS and ATTACH_SESSION decode inner protobuf and do not spoof iOS`() = runBlocking<Unit> {
        val snapshot = ProtoWire.encode(
            buildJsonObject {
                put(
                    "snapshot",
                    buildJsonObject {
                        put(
                            "sessions",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("sessionId", "sess-1")
                                        put("title", "Fix the nav")
                                        put("status", 2)
                                        put("lastEventId", "9")
                                    },
                                )
                            },
                        )
                    },
                )
            },
            AgentHostSchemas.WATCH_EVENT,
        )
        server.enqueue(
            ConnectStreamFixtures.streamResponse(
                listOf("""{"data":"${Base64.getEncoder().encodeToString(snapshot)}"}"""),
            ),
        )
        var watched = emptyList<com.cursorforandroid.domain.LocalAgentSession>()
        api.watchSessions("desk-1") { sessions ->
            watched = sessions
            false
        }
        server.takeRequest()
        val watch = server.takeRequest()
        assertThat(watch.path).isEqualTo("/aiserver.v1.RemoteAgentHostPresenceService/StreamAgentHost")
        assertThat(watch.getHeader("Connect-Protocol-Version")).isEqualTo("1")
        assertThat(watch.getHeader("User-Agent").orEmpty()).doesNotContain("iPhone")
        assertThat(watch.getHeader("User-Agent").orEmpty()).doesNotContain("Darwin")
        assertThat(ConnectStreamFixtures.requestJson(watch)).contains("REMOTE_AGENT_HOST_CONTROLLER_METHOD_WATCH_SESSIONS")
        assertThat(watched).hasSize(1)
        assertThat(watched[0].sessionId).isEqualTo("sess-1")
        assertThat(watched[0].lastEventId).isEqualTo(9L)

        val delta = ProtoWire.encode(
            buildJsonObject {
                put("eventId", "3")
                put("interactionUpdate", buildJsonObject {
                    put("textDelta", buildJsonObject { put("text", "Looking at NavStack.kt") })
                })
            },
            AgentHostSchemas.SESSION_EVENT,
        )
        server.enqueue(
            ConnectStreamFixtures.streamResponse(
                listOf("""{"data":"${Base64.getEncoder().encodeToString(delta)}"}"""),
            ),
        )
        var event: LocalSessionEvent? = null
        api.attachSession("desk-1", "sess-1", lastEventId = 2, clientInstanceId = "phone-1") {
            event = it
            false
        }
        val attach = server.takeRequest()
        assertThat(attach.path).isEqualTo("/aiserver.v1.RemoteAgentHostPresenceService/StreamAgentHost")
        val attachBody = ConnectStreamFixtures.requestJson(attach)
        assertThat(attachBody).contains("REMOTE_AGENT_HOST_CONTROLLER_METHOD_ATTACH_SESSION")
        assertThat(attachBody).contains("\"data\":")
        assertThat(event).isEqualTo(LocalSessionEvent.AssistantDelta("Looking at NavStack.kt", false, 3))
    }

    @Test
    fun `GET_SESSION_BLOBS is a unary CallAgentHost of blob ids`() = runBlocking<Unit> {
        val blobId = Base64.getEncoder().encodeToString("turn-1".toByteArray())
        val payload = ProtoWire.encode(
            buildJsonObject {
                put(
                    "blobs",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("blobId", blobId)
                                put("data", Base64.getEncoder().encodeToString("hello".toByteArray()))
                            },
                        )
                    },
                )
            },
            AgentHostSchemas.GET_BLOBS_RESPONSE,
        )
        server.enqueue(MockResponse().setBody("""{"data":"${Base64.getEncoder().encodeToString(payload)}"}"""))
        val blobs = api.getSessionBlobs("desk-1", "sess-1", listOf(blobId))
        server.takeRequest()
        val request = server.takeRequest().body.readUtf8()
        assertThat(request).contains("REMOTE_AGENT_HOST_CONTROLLER_METHOD_GET_SESSION_BLOBS")
        assertThat(blobs[blobId]!!.decodeToString()).isEqualTo("hello")
    }
}
