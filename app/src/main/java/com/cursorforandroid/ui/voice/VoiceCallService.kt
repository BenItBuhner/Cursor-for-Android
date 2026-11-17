package com.cursorforandroid.ui.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.cursorforandroid.CursorApp
import com.cursorforandroid.R
import com.cursorforandroid.notifications.LiveNotificationRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps a voice chat going while the app is out of sight: a foreground service of the microphone type, which is what
 * lets Android hand the recorder real audio rather than silence once the activity stops, with the call's ongoing
 * notification (a call-style one: the chat's name, what the call is doing, Mute and Hang up). Started by [VoiceCalls]
 * when a call starts — always from the foreground, as Android 14 requires for the microphone — and gone when it ends.
 */
class VoiceCallService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val calls = (application as CursorApp).graph.voiceCalls
        when (intent?.action) {
            ACTION_END -> calls.end()
            ACTION_MUTE -> calls.toggleMute()
        }
        val line = calls.line.value
        if (line == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val first = notification(line, calls.state.value)
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                first,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }
        if (started.isFailure) {
            // Not allowed from where this was started (the app was not in front): the call goes on in the foreground only.
            stopSelf()
            return START_NOT_STICKY
        }
        if (scope == null) {
            val watch = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = watch
            watch.launch {
                combine(calls.line, calls.state) { l, s -> Triple(l, VoiceChatCopy.status(s), s.muted) }.distinctUntilChanged().collect { (l, _, _) ->
                    if (l == null) {
                        ServiceCompat.stopForeground(this@VoiceCallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        // Updating the service's own foreground notification needs no notification permission.
                        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(l, calls.state.value)) }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    /** The call as the notification shade shows it: who it is with, its timer, what it is doing, Mute and Hang up. */
    private fun notification(line: VoiceCalls.Line, state: VoiceChatState): android.app.Notification {
        val caller = Person.Builder().setName(line.title).setImportant(true).build()
        val hangUp = action(ACTION_END, 1)
        val muted = state.muted
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(line.title)
            .setContentText(VoiceChatCopy.status(state))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - line.startedAtMs))
            .setContentIntent(LiveNotificationRenderer.openAgent(this, line.agentId))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, hangUp))
        if (VoiceChatCopy.canMute(state)) {
            builder.addAction(0, if (muted) VoiceChatCopy.Unmute else VoiceChatCopy.Mute, action(ACTION_MUTE, 2))
        }
        return builder.build()
    }

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, VoiceCallService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL = "voice_chat"
        private const val NOTIFICATION_ID = 0x7c411
        private const val ACTION_END = "com.cursorforandroid.voice.END"
        private const val ACTION_MUTE = "com.cursorforandroid.voice.MUTE"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Voice chat", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The call with an agent while a voice chat is on."
                    setShowBadge(false)
                },
            )
        }

        /** Brings the service up for a call that just started; quietly nothing where Android refuses. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, VoiceCallService::class.java)) }
        }
    }
}
