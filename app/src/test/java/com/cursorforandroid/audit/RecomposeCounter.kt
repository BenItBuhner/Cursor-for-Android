package com.cursorforandroid.audit

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Counts every composable body that runs (not skipped), by function name, through the compiler's trace markers: no
 * production code is touched. Audit-only.
 */
@OptIn(InternalComposeTracingApi::class)
object RecomposeCounter : CompositionTracer {
    private val counts = ConcurrentHashMap<String, Int>()

    @Volatile
    var on = false

    override fun isTraceInProgress(): Boolean = on

    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        val name = info.substringBefore(" (")
        if (name.startsWith("com.cursorforandroid")) counts.merge(name.removePrefix("com.cursorforandroid."), 1, Int::plus)
    }

    override fun traceEventEnd() = Unit

    fun install() {
        Composer.setTracer(this)
        on = true
    }

    fun reset() = counts.clear()

    fun snapshot(): Map<String, Int> = HashMap(counts)

    fun count(suffix: String): Int = counts.entries.filter { it.key == suffix || it.key.endsWith(".$suffix") }.sumOf { it.value }

    fun top(n: Int = 30): String = counts.entries.sortedByDescending { it.value }.take(n).joinToString("\n") { "  ${it.value}\t${it.key}" }
}

object AuditLog {
    private val file = File("/tmp/audit/probe.log").also { it.parentFile.mkdirs() }

    fun line(text: String) {
        println(text)
        file.appendText(text + "\n")
    }
}
