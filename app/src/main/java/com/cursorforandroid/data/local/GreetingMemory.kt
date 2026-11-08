package com.cursorforandroid.data.local

import com.cursorforandroid.domain.GreetingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.util.concurrent.CompletableFuture

/**
 * The New Chat page's [GreetingLog] between launches, in the cache tree a sign-out wipes. [warm] reads the file once,
 * off the main thread, as the application is created; [peek] answers from what that read found (or this process has
 * shown since) without ever touching the disk, so the page's first frame has its greeting. A page drawn before the
 * read is done picks without the history — a repeat is possible, never a wait.
 */
class GreetingMemory(
    private val cache: JsonDiskCache,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val read = CompletableFuture<GreetingLog?>()
    @Volatile private var started = false
    @Volatile private var latest: GreetingLog? = null
    private val writes = Mutex()

    /** Starts reading the file, once; for `Application.onCreate`. */
    fun warm() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            read.complete(runCatching { cache.read(KEY, GreetingLog.serializer(), VERSION)?.value }.getOrNull())
        }
    }

    /** The log as far as it is known now: never waits, never reads. */
    fun peek(): GreetingLog = latest ?: read.getNow(null) ?: GreetingLog()

    /** Records [lineId] as shown on a visit at [nowMillis], here and on disk. */
    suspend fun shown(lineId: String, nowMillis: Long, zone: ZoneId) {
        val token = cache.token()
        writes.withLock {
            warm()
            val base = latest ?: withTimeoutOrNull(READ_WAIT_MS) { read.await() } ?: GreetingLog()
            val next = base.shown(lineId, nowMillis, zone)
            if (cache.isStale(token)) return
            latest = next
            cache.write(KEY, GreetingLog.serializer(), VERSION, next, token)
        }
    }

    /** The account signed out: the next greeting starts from a clean slate, whatever the file read finds. */
    fun forget() {
        latest = GreetingLog()
    }

    private companion object {
        const val KEY = "log"
        const val VERSION = 1
        const val READ_WAIT_MS = 2_000L
    }
}
