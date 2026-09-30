package com.cursorforandroid.data.api

/**
 * The process's threads at one moment, each thread's state and stack taken together (`ThreadMXBean.dumpAllThreads`,
 * through reflection: the tests compile against the Android SDK). A thread's state read apart from its stack can
 * name one that has already left the code the stack shows.
 */
internal object StreamThreads {
    private val bean = runCatching { Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null) }.getOrNull()
    private val dump = runCatching {
        Class.forName("java.lang.management.ThreadMXBean").getMethod("dumpAllThreads", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    }.getOrNull()
    private val info = runCatching { Class.forName("java.lang.management.ThreadInfo") }.getOrNull()
    private val name = info?.getMethod("getThreadName")
    private val state = info?.getMethod("getThreadState")
    private val stack = info?.getMethod("getStackTrace")

    /** The stream reader's classes: a thread parked in one of them is held by a stream it waits to read. */
    private val READERS = listOf("SseRunStreamer", "SseStreamReader", "SseParser", "RunStreamMux").map { "com.cursorforandroid.data.api.$it" }
    private val SERVERS = listOf("okhttp3.mockwebserver.", "com.cursorforandroid.data.faults.FaultServer", "com.cursorforandroid.ui.scale.WireStreams")

    /**
     * [readers]: the stream multiplexer's own threads and every thread parked inside the stream reader (through
     * OkHttp, a stream holds one for as long as it is open). [servers]: threads serving the test's streams, its
     * server's idle pool included. [total]: every live thread; [app], those that are not the server's.
     */
    class Census(val readers: List<String>, val servers: Int, val total: Int) {
        val app: Int get() = total - servers
    }

    fun census(): Census {
        val infos = runCatching { dump?.invoke(bean, false, false) as? Array<*> }.getOrNull().orEmpty().filterNotNull()
        val readers = ArrayList<String>()
        var servers = 0
        for (i in infos) {
            val threadName = name?.invoke(i) as? String ?: continue
            val threadState = state?.invoke(i) as? Thread.State
            @Suppress("UNCHECKED_CAST")
            val frames = (stack?.invoke(i) as? Array<StackTraceElement>).orEmpty()
            val parked = threadState == Thread.State.WAITING || threadState == Thread.State.TIMED_WAITING
            if (threadName.startsWith("RunStreams") || parked && frames.any { f -> READERS.any { f.className == it || f.className.startsWith("$it$") } }) {
                readers += "$threadName/$threadState"
            } else if (threadName.startsWith("MockWebServer") || frames.any { f -> SERVERS.any { f.className.startsWith(it) } }) {
                servers++
            }
        }
        return Census(readers, servers, infos.size)
    }
}
