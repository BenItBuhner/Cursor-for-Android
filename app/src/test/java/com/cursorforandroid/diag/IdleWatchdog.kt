package com.cursorforandroid.diag

import android.os.Looper
import android.os.MessageQueue
import android.os.SystemClock
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin
import org.robolectric.shadows.ShadowChoreographer

// TEMPORARY CI DIAGNOSTIC: dumps the main thread's frame machinery when Compose's Robolectric idling loop spins.
class IdleWatchdog : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        synchronized(IdleWatchdog::class.java) {
            if (started) return
            started = true
        }
        val t = Thread({ loop() }, "idle-watchdog")
        t.isDaemon = true
        t.start()
    }

    private fun loop() {
        var stuckSince = 0L
        var dumped = 0
        while (true) {
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
            val main = runCatching { Looper.getMainLooper().thread }.getOrNull() ?: continue
            val spinning = main.stackTrace.any { it.className.contains("RobolectricIdlingStrategy") }
            if (!spinning) { stuckSince = 0; continue }
            val now = System.currentTimeMillis()
            if (stuckSince == 0L) stuckSince = now
            if (now - stuckSince >= 15_000 && dumped < 3) {
                dumped++
                System.err.println(runCatching { dump(main) }.getOrElse { "IDLEDUMP failed: $it" })
                stuckSince = now + 30_000
            }
        }
    }

    private fun dump(main: Thread): String = buildString {
        appendLine("IDLEDUMP ===== main=${main.name} uptime=${SystemClock.uptimeMillis()} paused=${ShadowChoreographer.isPaused()} frameDelay=${ShadowChoreographer.getFrameDelay()} nextVsync=${ShadowChoreographer.getNextVsyncTimeNanos()}")
        val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val roots = (field(wmg, "mRoots") as? List<*>).orEmpty()
        appendLine("IDLEDUMP roots=${roots.size}")
        val seenChoreographers = HashSet<Any>()
        roots.forEachIndexed { i, root ->
            root ?: return@forEachIndexed
            appendLine("IDLEDUMP root[$i] ${scalars(root)}")
            val view = field(root, "mView") as? android.view.View
            if (view != null) appendLine("IDLEDUMP root[$i].view ${view.javaClass.name} attached=${view.isAttachedToWindow} layoutRequested=${view.isLayoutRequested} w=${view.width} h=${view.height} vis=${view.visibility}")
            val ch = field(root, "mChoreographer")
            if (ch != null && seenChoreographers.add(ch)) appendLine(choreographer("root[$i]", ch))
        }
        appendLine(queue("main", Looper.getMainLooper().queue))
        appendLine(runCatching { compose() }.getOrElse { "IDLEDUMP compose failed: $it" })
        Thread.getAllStackTraces().forEach { (t, st) ->
            val interesting = t.name.contains("Main Thread") || t.name.contains("Choreographer") ||
                (t.state == Thread.State.RUNNABLE && st.none { it.methodName == "accept" || it.methodName == "epollWait" || it.className.startsWith("sun.nio.ch") || it.methodName == "socketRead0" || it.methodName == "read0" } && st.isNotEmpty())
            if (interesting) {
                appendLine("IDLEDUMP thread ${t.name} ${t.state}")
                st.take(30).forEach { appendLine("IDLEDUMP     at $it") }
            }
        }
        appendLine("IDLEDUMP ===== end")
    }

    private fun compose(): String = buildString {
        val companion = Class.forName("androidx.compose.runtime.Recomposer").getDeclaredField("Companion").get(null)
        val flow = companion.javaClass.getMethod("getRunningRecomposers").invoke(companion) as kotlinx.coroutines.flow.StateFlow<*>
        val infos = (flow.value as? Collection<*>).orEmpty()
        appendLine("IDLEDUMP recomposers=${infos.size}")
        infos.forEachIndexed { i, info ->
            val rec = info?.let { field(it, "this$0") } ?: return@forEachIndexed
            appendLine("IDLEDUMP rec[$i] ${short(rec.toString())} ${scalars(rec)}")
            fields(rec.javaClass).forEach { f ->
                f.isAccessible = true
                val v = runCatching { f.get(rec) }.getOrNull() ?: return@forEach
                when {
                    v.javaClass.name.endsWith("BroadcastFrameClock") -> {
                        val awaiters = (field(v, "awaiters") as? List<*>).orEmpty()
                        appendLine("IDLEDUMP rec[$i].frameClock awaiters=${awaiters.size}")
                        awaiters.take(10).forEach { a -> appendLine("IDLEDUMP     awaiter ${chain(a?.let { field(it, "continuation") })}") }
                    }
                    v is Collection<*> && v.isNotEmpty() -> appendLine("IDLEDUMP rec[$i].${f.name} n=${v.size} ${short(v.take(5).toString())}")
                    v.javaClass.name.contains("ScatterSet") || v.javaClass.name.contains("MutableVector") -> appendLine("IDLEDUMP rec[$i].${f.name} ${short(v.toString())}")
                }
            }
        }
        val snap = Class.forName("androidx.compose.runtime.snapshots.SnapshotKt").declaredFields
            .firstOrNull { it.name == "currentGlobalSnapshot" }?.let { it.isAccessible = true; it.get(null) }
        val global = (snap as? java.util.concurrent.atomic.AtomicReference<*>)?.get() ?: snap
        if (global != null) appendLine("IDLEDUMP globalSnapshot ${global.javaClass.name} modified=${short(field(global, "modified").toString())}")
    }

    private fun chain(c: Any?): String {
        var cur = c
        val out = StringBuilder()
        var n = 0
        while (cur != null && n < 25) {
            out.append(" <- ").append(short(cur.toString(), 160))
            cur = field(cur, "completion") ?: field(cur, "continuation") ?: field(cur, "uCont")
            n++
        }
        return out.toString()
    }

    private fun short(s: String, max: Int = 1500) = if (s.length > max) s.take(max) + "..." else s

    private fun choreographer(label: String, ch: Any): String = buildString {
        appendLine("IDLEDUMP $label.choreographer ${scalars(ch)}")
        field(ch, "mDisplayEventReceiver")?.let { appendLine("IDLEDUMP $label.receiver ${it.javaClass.name} ${scalars(it)}") }
        (field(ch, "mLooper") as? Looper)?.let { appendLine("IDLEDUMP $label.looper thread=${it.thread.name} alive=${it.thread.isAlive} main=${it == Looper.getMainLooper()}") }
        val queues = field(ch, "mCallbackQueues") as? Array<*>
        queues?.forEachIndexed { qi, q ->
            var node = q?.let { field(it, "mHead") }
            var n = 0
            val items = StringBuilder()
            while (node != null && n < 20) {
                items.append(" [due=${field(node, "dueTime")} action=${field(node, "action")?.javaClass?.name} token=${field(node, "token")}]")
                node = field(node, "next")
                n++
            }
            if (n > 0) appendLine("IDLEDUMP $label.callbacks[$qi] n=$n$items")
        }
    }

    private fun queue(label: String, q: MessageQueue): String = buildString {
        var m = field(q, "mMessages")
        var n = 0
        appendLine("IDLEDUMP $label.queue ${scalars(q)}")
        while (m != null && n < 40) {
            appendLine("IDLEDUMP $label.msg when=${field(m, "when")} what=${field(m, "what")} target=${field(m, "target")?.javaClass?.name} callback=${field(m, "callback")?.javaClass?.name} flags=${field(m, "flags")} arg1=${field(m, "arg1")}")
            m = field(m, "next")
            n++
        }
        appendLine("IDLEDUMP $label.queue shown=$n")
    }

    private fun fields(c: Class<*>): List<Field> = generateSequence(c) { it.superclass }.takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }.filter { !Modifier.isStatic(it.modifiers) }.toList()

    private fun scalars(o: Any): String = fields(o.javaClass)
        .filter { it.type.isPrimitive || it.type == String::class.java }
        .joinToString(" ") { f -> runCatching { f.isAccessible = true; "${f.name}=${f.get(o)}" }.getOrElse { "${f.name}=?" } }

    private fun field(o: Any, name: String): Any? = fields(o.javaClass).firstOrNull { it.name == name }
        ?.let { f -> runCatching { f.isAccessible = true; f.get(o) }.getOrNull() }

    private companion object {
        @Volatile var started = false
    }
}
