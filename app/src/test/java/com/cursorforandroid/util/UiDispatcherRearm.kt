package com.cursorforandroid.util

import android.os.Handler
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * Mends the two process-wide pieces of Compose that one test can break for every test after it in the same JVM.
 *
 * Compose applies a snapshot write made outside a composition through `GlobalSnapshotManager`: a global write observer
 * sends to a channel, and a coroutine on [AndroidUiDispatcher.Main] receives and calls `Snapshot.sendApplyNotifications`.
 * Both are started once per JVM (per Robolectric sandbox), and neither recovers:
 *
 * - The dispatcher runs its queue from one Handler message and records that it is posted (`scheduledTrampolineDispatch`),
 *   so later dispatches only queue behind it. Robolectric empties the main looper's queue for the next test but leaves
 *   the flag set: a write landing after a test's final idle (a worker that outlived its test) leaves the dispatcher
 *   counting on a message that is gone, or one timed before the clock went back, and it never posts again.
 * - The receiving coroutine dies with the first exception out of `sendApplyNotifications`: a worker writing while the
 *   test harness resumes the recomposer inline (its `ApplyingContinuationInterceptor` applies again on every resume)
 *   ends in a `StackOverflowError` there. The channel is cancelled with it, the manager stays "started", and its write
 *   observer - first in the list, sharing the manager's `sent` flag - keeps swallowing every later write.
 *
 * Either way no global write is applied outside a frame again, `Snapshot.current.hasPendingChanges()` stays true, and
 * every Compose test left in the JVM pumps frames until Espresso's 60 s idle timeout (`AppNotIdleException`): the
 * cascade that ran CI shard jobs into their 30-minute limit.
 *
 * The loopers are reset after Robolectric's own setup hooks and a worker's write can land at any point around that, so
 * the mending happens where every wait for idle passes, on the main thread: Compose's idling strategy drains the main
 * looper through `Espresso.onIdle()` on each turn, and Robolectric's Espresso asks the registered idling resources there.
 * The one registered here is never busy; asked, it re-posts the dispatcher's message when the dispatcher counts on one,
 * and starts the manager again when its channel is closed. Registered in `META-INF/services`.
 */
class UiDispatcherRearm : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        val registry = IdlingRegistry.getInstance()
        if (registry.resources.none { it is Guard }) registry.register(Guard)
    }

    private object Guard : IdlingResource {
        override fun getName() = "UiDispatcherRearm"
        override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) = Unit
        override fun isIdleNow(): Boolean {
            rearmDispatcher()
            restartSnapshotManager()
            return true
        }
    }

    private companion object {
        private fun <T> reflect(what: String, find: () -> T): T = runCatching(find).getOrElse {
            throw IllegalStateException("$what is gone (a Compose update?): UiDispatcherRearm needs a new look", it)
        }

        private fun field(owner: String, name: String): Field =
            reflect("$owner.$name") { Class.forName(owner).getDeclaredField(name).apply { isAccessible = true } }

        private const val DISPATCHER = "androidx.compose.ui.platform.AndroidUiDispatcher"
        private const val MANAGER = "androidx.compose.ui.platform.GlobalSnapshotManager"
        private const val SNAPSHOTS = "androidx.compose.runtime.snapshots.SnapshotKt"

        val mainDelegate = field(DISPATCHER, "Main\$delegate")
        val dispatcherLock = field(DISPATCHER, "lock")
        val handler = field(DISPATCHER, "handler")
        val dispatchCallback = field(DISPATCHER, "dispatchCallback")
        val trampolineScheduled = field(DISPATCHER, "scheduledTrampolineDispatch")

        val manager: Any = field(MANAGER, "INSTANCE").get(null)
        val started = field(MANAGER, "started").get(null) as AtomicBoolean
        val sent = field(MANAGER, "sent").get(null) as AtomicBoolean
        val ensureStarted: Method = reflect("$MANAGER.ensureStarted") { Class.forName(MANAGER).getMethod("ensureStarted") }
        val managerObserverChannel = field("$MANAGER\$ensureStarted\$2", "\$channel")
        val globalWriteObservers = field(SNAPSHOTS, "globalWriteObservers")
        val snapshotLock: Any = field(SNAPSHOTS, "lock").get(null)

        fun rearmDispatcher() {
            if (!(mainDelegate.get(null) as Lazy<*>).isInitialized()) return
            val dispatcher = AndroidUiDispatcher.Main[kotlin.coroutines.ContinuationInterceptor] as AndroidUiDispatcher
            synchronized(dispatcherLock.get(dispatcher)) {
                if (!trampolineScheduled.getBoolean(dispatcher)) return
                // Posted whether or not the queue holds one already: a message posted during the reset keeps its
                // time from before the clock went back, and waits for a time the paused clock will not reach. A
                // second run finds nothing queued and does nothing; only a lost run is harmful.
                (handler.get(dispatcher) as Handler).post(dispatchCallback.get(dispatcher) as Runnable)
            }
        }

        @OptIn(DelicateCoroutinesApi::class)
        fun restartSnapshotManager() {
            val dead = (globalWriteObservers.get(null) as List<*>).filter { observer ->
                observer != null && managerObserverChannel.declaringClass.isInstance(observer) &&
                    (managerObserverChannel.get(observer) as SendChannel<*>).isClosedForSend
            }.toSet()
            if (dead.isEmpty()) return
            synchronized(snapshotLock) { globalWriteObservers.set(null, (globalWriteObservers.get(null) as List<*>) - dead) }
            sent.set(false)
            started.set(false)
            ensureStarted.invoke(manager)
            Snapshot.sendApplyNotifications()
        }
    }
}
