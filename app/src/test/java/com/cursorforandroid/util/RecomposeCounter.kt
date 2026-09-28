package com.cursorforandroid.util

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Counts every composable body of the app that runs (is not skipped), by function name, through the trace markers
 * the Compose compiler puts in debug builds; no production code is touched. [install] before composing, [uninstall]
 * after the test: the tracer is process-wide and cannot be removed, only switched off.
 */
@OptIn(InternalComposeTracingApi::class)
object RecomposeCounter : CompositionTracer {
    private val counts = ConcurrentHashMap<String, Int>()

    @Volatile
    private var on = false

    override fun isTraceInProgress(): Boolean = on

    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        val name = info.substringBefore(" (")
        if (name.startsWith(APP_PREFIX)) counts.merge(name.removePrefix(APP_PREFIX), 1, Int::plus)
    }

    override fun traceEventEnd() = Unit

    fun install() {
        Composer.setTracer(this)
        counts.clear()
        on = true
    }

    fun uninstall() {
        on = false
        counts.clear()
    }

    fun reset() = counts.clear()

    /** Runs of the composables whose name is [name], e.g. `AgentRowItem` for `ui.agents.AgentRowItem`. */
    fun count(name: String): Int = counts.entries.filter { it.key == name || it.key.endsWith(".$name") }.sumOf { it.value }

    fun top(n: Int = 20): String = counts.entries.sortedByDescending { it.value }.take(n).joinToString(", ") { "${it.key}=${it.value}" }

    private const val APP_PREFIX = "com.cursorforandroid."
}
