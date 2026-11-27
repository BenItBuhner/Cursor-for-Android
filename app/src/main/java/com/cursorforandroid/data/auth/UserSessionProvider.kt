package com.cursorforandroid.data.auth

import com.cursorforandroid.data.api.ConnectRpc
import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.await
import com.cursorforandroid.util.AppClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * The account session itself, as the desktop holds it: the access and refresh token a browser sign-in releases
 * (`/auth/poll`), kept encrypted at rest and renewed the desktop's way (`cursor-session-refresh.ts`):
 * `POST /oauth/token {grant_type: "refresh_token", client_id, refresh_token}`.
 *
 * Most account RPCs take the session [SessionTokenProvider] exchanges from the API key. Some do not: Cursor refuses
 * `MintRealtimeVoiceSecret` with "Realtime voice is not available with API key authentication", so thread calls go
 * through this one. Without a kept session it throws [SessionUnavailableException.NO_ACCOUNT_SESSION], which the
 * call answers by offering to connect the account in the browser.
 */
class UserSessionProvider(
    private val client: OkHttpClient,
    private val read: () -> String?,
    private val write: (String?) -> Unit,
    private val apiUrl: String = CursorLoginEndpoints.API_URL,
    /** The desktop's `authClientId` for production (`cursorCreds`). */
    private val clientId: String = DESKTOP_CLIENT_ID,
    private val now: () -> Long = AppClock::now,
    /** Extended mode, as for [SessionTokenProvider]: nothing on api2 while it is off. */
    private val sessionAllowed: suspend () -> Boolean = { true },
) : AccountTokens {

    @Serializable
    internal data class Stored(val accessToken: String, val refreshToken: String)

    private val mutex = Mutex()
    @Volatile private var refused: String? = null

    /** Whether a session is kept at all. */
    fun connected(): Boolean = load() != null

    /** Keeps the session a browser sign-in released, replacing any earlier one. */
    fun save(tokens: SessionTokens) {
        refused = null
        store(Stored(tokens.accessToken, tokens.refreshToken.ifBlank { tokens.accessToken }))
    }

    fun clear() {
        refused = null
        write(null)
    }

    override suspend fun accessToken(): String {
        if (!sessionAllowed()) {
            throw SessionUnavailableException("Extended mode is off, so Cursor's account service isn't used.", SessionUnavailableException.EXTENDED_MODE_OFF)
        }
        load()?.takeIf { it.isUsable() }?.let { return it.accessToken }
        return mutex.withLock {
            val current = load() ?: throw notConnected()
            if (current.isUsable()) current.accessToken else refresh(current).accessToken
        }
    }

    override fun invalidate() {
        refused = load()?.accessToken
    }

    /** Not the token just refused, and not about to lapse (a token whose expiry can't be read is tried as is). */
    private fun Stored.isUsable(): Boolean {
        val expiry = SessionTokenProvider.jwtExpiryMs(accessToken)
        return accessToken != refused && (expiry == null || expiry - now() > SessionTokenProvider.EXPIRY_MARGIN_MS)
    }

    private suspend fun refresh(current: Stored): Stored = withContext(Dispatchers.IO) {
        // A `session_rt_` refresh token is bound to a device key (DPoP) this app never holds, so it cannot be renewed.
        if (current.refreshToken.startsWith(DPOP_REFRESH_PREFIX)) throw ended()
        val body = CursorJson.encodeToString(RefreshRequestDto.serializer(), RefreshRequestDto(grantType = "refresh_token", clientId = clientId, refreshToken = current.refreshToken))
        val request = Request.Builder()
            .url("${apiUrl.trimEnd('/')}/oauth/token")
            .header("Accept", "application/json")
            .post(ConnectRpc.jsonBody(body))
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw SessionUnavailableException("Couldn't reach Cursor to renew your account session.", cause = e)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            val dto = runCatching { CursorJson.decodeFromString(RefreshResponseDto.serializer(), text) }.getOrNull()
            if (dto?.shouldLogout == true || r.code in REFUSED_CODES) throw ended()
            val access = dto?.accessToken?.takeIf { it.isNotBlank() }
                ?: throw SessionUnavailableException("Cursor couldn't renew your account session (HTTP ${r.code}).")
            // The desktop's rule: a refresh that names no new refresh token leaves the access token as the next one.
            val next = Stored(access, dto.refreshToken?.takeIf { it.isNotBlank() } ?: access)
            refused = null
            store(next)
            next
        }
    }

    private fun ended(): SessionUnavailableException {
        clear()
        return SessionUnavailableException(
            "Your Cursor account needs to be connected again for calls.",
            SessionUnavailableException.NO_ACCOUNT_SESSION,
        )
    }

    private fun notConnected() = SessionUnavailableException(
        "Calls use your Cursor account. Connect it once in the browser.",
        SessionUnavailableException.NO_ACCOUNT_SESSION,
    )

    private fun load(): Stored? = read()?.let { json ->
        runCatching { CursorJson.decodeFromString(Stored.serializer(), json) }.getOrNull()?.takeIf { it.accessToken.isNotBlank() }
    }

    private fun store(session: Stored) = write(CursorJson.encodeToString(Stored.serializer(), session))

    @Serializable
    private data class RefreshRequestDto(
        @SerialName("grant_type") val grantType: String,
        @SerialName("client_id") val clientId: String,
        @SerialName("refresh_token") val refreshToken: String,
    )

    @Serializable
    private data class RefreshResponseDto(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        val shouldLogout: Boolean? = null,
    )

    companion object {
        const val DESKTOP_CLIENT_ID = "KbZUR41cY7W6zRSdpSUJ7I7mLYBKOCmB"
        const val DPOP_REFRESH_PREFIX = "session_rt_"
        /** The refresh was refused for good (bad request, unauthenticated, forbidden): the account must connect again. */
        private val REFUSED_CODES = setOf(400, 401, 403)
    }
}
