package com.cursorforandroid.data.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** The open voice line: frames out with [send], and [close] to hang up. */
interface RealtimeLine {
    /** False when the line is gone and nothing went. */
    fun send(frame: String): Boolean
    fun close()
}

/** What the line tells the call. Called on the socket's own thread; the call moves it where it runs. */
interface RealtimeLineListener {
    fun onOpen()
    fun onFrame(frame: String)
    /** The line closed or failed; [message] says how, when it was not a hang-up from here. */
    fun onClosed(code: Int, message: String?)
}

/** Opens the line for a minted credential. */
fun interface RealtimeDialer {
    fun dial(address: String, clientSecret: String, listener: RealtimeLineListener): RealtimeLine
}

/**
 * The line over OkHttp's WebSocket, as the desktop opens it (`new WebSocket(url, ["xai-client-secret.<secret>"])`):
 * the credential goes in `Sec-WebSocket-Protocol`, nothing else is sent with the upgrade. Pinged, so a line a
 * network dropped without a word is noticed.
 */
class OkHttpRealtimeDialer(base: OkHttpClient) : RealtimeDialer {
    private val client = base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    override fun dial(address: String, clientSecret: String, listener: RealtimeLineListener): RealtimeLine {
        val request = Request.Builder()
            .url(address)
            .header("Sec-WebSocket-Protocol", RealtimeWire.subprotocol(clientSecret))
            .build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                @Volatile private var closed = false
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onFrame(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code.takeIf { it in 1000..4999 && it != 1005 && it != 1006 } ?: 1000, null)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (closed) return
                    closed = true
                    listener.onClosed(code, reason.ifBlank { null })
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (closed) return
                    closed = true
                    val said = response?.let { r -> runCatching { r.body?.string() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${r.code}" }
                    listener.onClosed(response?.code ?: 1006, said ?: t.message ?: t.javaClass.simpleName)
                }
            },
        )
        return object : RealtimeLine {
            override fun send(frame: String): Boolean = socket.send(frame)
            override fun close() {
                socket.close(1000, "hang_up")
            }
        }
    }
}
