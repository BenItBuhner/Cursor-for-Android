package com.cursorforandroid.data.auth

/** Where an account-service RPC gets its bearer token, and how a `401` makes it start over. */
interface AccountTokens {
    /** A token for the next call; throws [SessionUnavailableException] when none can be had. */
    suspend fun accessToken(): String

    /** The token just used was refused: the next [accessToken] must not hand it out again. */
    fun invalidate()
}
