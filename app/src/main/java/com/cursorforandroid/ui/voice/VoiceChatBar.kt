package com.cursorforandroid.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.data.api.TranscriptionApi
import com.cursorforandroid.data.media.MediaRecorderCapture
import com.cursorforandroid.data.media.TextToSpeechSpeaker
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.VoiceInput
import com.cursorforandroid.ui.components.findActivity
import com.cursorforandroid.ui.components.openAppSettings
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.ui.theme.CursorTheme
import kotlin.math.PI
import kotlin.math.sin

/** The words of the voice chat's bar, in one place for the screen and its tests. */
object VoiceChatCopy {
    const val Start = "Start voice chat"
    const val End = "End voice chat"
    const val Hold = "Hold"
    const val Resume = "Resume"

    fun title(phase: VoiceChatPhase): String = when (phase) {
        VoiceChatPhase.Off -> ""
        is VoiceChatPhase.Listening -> "Listening"
        VoiceChatPhase.Transcribing -> "Transcribing"
        is VoiceChatPhase.Working -> "Agent is working"
        is VoiceChatPhase.Speaking -> "Speaking"
        VoiceChatPhase.Muted -> "On hold"
        is VoiceChatPhase.Failed -> "Voice chat"
    }

    /** The second line: what was said or is being read where there is something, and otherwise what a tap would do. */
    fun detail(state: VoiceChatState): String = when (val phase = state.phase) {
        VoiceChatPhase.Off -> ""
        is VoiceChatPhase.Listening -> when {
            phase.heard -> "Pause to send, or tap to send now."
            state.lastSaid != null -> "Go on, or say goodbye to end."
            else -> "Say what you want done. A pause sends it."
        }
        VoiceChatPhase.Transcribing -> "One moment."
        is VoiceChatPhase.Working -> "“${phase.said}”"
        is VoiceChatPhase.Speaking -> phase.text
        VoiceChatPhase.Muted -> "The microphone is closed. Tap to go on."
        is VoiceChatPhase.Failed -> phase.message
    }
}

object VoiceChatTags {
    const val Bar = "voice-chat-bar"
    const val End = "voice-chat-end"
    const val Hold = "voice-chat-hold"
}

/**
 * The voice chat of one conversation ([VoiceChat]) while [transcription] is given, null otherwise: the microphone, the
 * device's own voice and the permission it needs, with [send] putting an utterance into the chat as a follow-up. The
 * call ends when the app leaves the foreground, where Android would feed it silence, and when the screen goes.
 * The returned start asks for the microphone first when it has not been granted.
 */
@Composable
fun rememberVoiceChat(transcription: TranscriptionApi?, send: (String) -> Boolean): Pair<VoiceChat, () -> Unit>? {
    if (transcription == null) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val latestSend by rememberUpdatedState(send)
    val chat = remember(transcription) {
        val app = context.applicationContext
        VoiceChat(
            scope = scope,
            transcription = transcription,
            newCapture = { MediaRecorderCapture(app) },
            speaker = TextToSpeechSpeaker(app),
            send = { words -> latestSend(words) },
            clock = SystemClock::elapsedRealtime,
            errorMessage = { VoiceInput.messageFor(it) },
        )
    }
    SideEffect {
        chat.onPhase = { phase ->
            when (phase) {
                is VoiceChatPhase.Listening -> if (!phase.heard) haptics.perform(Haptic.ToggleOn)
                VoiceChatPhase.Transcribing -> haptics.perform(Haptic.Confirm)
                is VoiceChatPhase.Failed -> haptics.perform(Haptic.Reject)
                VoiceChatPhase.Off, VoiceChatPhase.Muted -> haptics.perform(Haptic.ToggleOff)
                is VoiceChatPhase.Working, is VoiceChatPhase.Speaking -> Unit
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(chat, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) chat.end() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            chat.release()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            chat.start()
        } else {
            val activity = context.findActivity()
            val canAskAgain = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            chat.permissionDenied(permanently = !canAskAgain)
        }
    }
    val start = remember(chat, permission) {
        {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) chat.start()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    return chat to start
}

/** [VoiceChatBar] for a live [chat]: a tap on a refused permission opens Settings, any other tap is the call's shortcut. */
@Composable
fun VoiceChatBar(chat: VoiceChat, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by chat.state.collectAsStateWithLifecycle()
    // The bar arrives where the composer stood: it rises a little and comes into focus rather than replacing it at a cut.
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrival.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
    VoiceChatBar(
        state = state,
        onTap = { if ((state.phase as? VoiceChatPhase.Failed)?.opensSettings == true) openAppSettings(context) else chat.tap() },
        onHold = chat::toggleMute,
        onEnd = chat::end,
        modifier = modifier.graphicsLayer {
            val t = arrival.value
            alpha = t.coerceIn(0f, 1f)
            translationY = (1f - t) * 12.dp.toPx()
            scaleX = 0.96f + 0.04f * t
            scaleY = 0.96f + 0.04f * t
        },
    )
}

/**
 * A voice chat where the composer stands: the orb that shows what the call is doing, two lines that say it, Hold while
 * the microphone is the call's to hold, and the red button that ends it. The whole bar is the call's shortcut ([onTap]).
 */
@Composable
fun VoiceChatBar(state: VoiceChatState, onTap: () -> Unit, onHold: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val phase = state.phase
    val failed = phase is VoiceChatPhase.Failed
    val title = VoiceChatCopy.title(phase)
    val detail = VoiceChatCopy.detail(state)
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.elevated, CursorTheme.shapes.xl)
            .border(1.dp, colors.strokeSubtle, CursorTheme.shapes.xl)
            .pressable(onClick = onTap, shape = CursorTheme.shapes.xl)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .testTag(VoiceChatTags.Bar),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VoiceOrb(phase = phase, level = state.level)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            // The title changes with the phase and slides as it does; the line under it changes with every word heard
            // or read, so it only fades.
            AnimatedContent(
                targetState = title,
                transitionSpec = { (fadeIn(tween(160)) + slideInVertically(tween(200)) { it / 2 }) togetherWith (fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 2 }) },
                label = "voice-chat-title",
            ) { text ->
                Text(text, style = type.baseMedium, color = if (failed) colors.red else colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AnimatedContent(
                targetState = detail,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "voice-chat-detail",
            ) { text ->
                Text(text, style = type.small, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val holds = phase is VoiceChatPhase.Listening || phase == VoiceChatPhase.Muted
        if (holds) {
            val held = phase == VoiceChatPhase.Muted
            FlatIconButton(
                icon = if (held) CursorIcons.Play else CursorIcons.Pause,
                contentDescription = if (held) VoiceChatCopy.Resume else VoiceChatCopy.Hold,
                onClick = onHold,
                modifier = Modifier.testTag(VoiceChatTags.Hold),
            )
        }
        Box(
            Modifier
                .size(40.dp)
                .background(colors.red, CircleShape)
                .pressable(onClick = onEnd, shape = CircleShape)
                .semantics { contentDescription = VoiceChatCopy.End }
                .testTag(VoiceChatTags.End),
            contentAlignment = Alignment.Center,
        ) {
            Icon(CursorIcons.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * What the call is doing, drawn: a disc that swells with the voice while it listens, an arc going round while the
 * utterance is transcribed and the agent works, five bars moving while the reply is read, and a still, dim disc on
 * hold. The colour says whose turn it is: green for the microphone, the text colour for the agent, red for a failure.
 */
@Composable
private fun VoiceOrb(phase: VoiceChatPhase, level: Float) {
    val colors = CursorTheme.colors
    val listening = phase is VoiceChatPhase.Listening
    val tint by animateColorAsState(
        when (phase) {
            is VoiceChatPhase.Listening -> colors.green
            is VoiceChatPhase.Failed -> colors.red
            VoiceChatPhase.Muted, VoiceChatPhase.Off -> colors.iconSecondary
            else -> colors.textPrimary
        },
        tween(220),
        label = "voice-orb-tint",
    )
    // The meter arrives every 80 ms; the spring carries the disc between readings so it moves like a voice, not a clock.
    val swell by animateFloatAsState(
        if (listening) level.coerceIn(0f, 1f) else 0f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "voice-orb-swell",
    )
    val motion = rememberInfiniteTransition(label = "voice-orb")
    val turn by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "voice-orb-turn")
    val breath by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "voice-orb-breath")
    Canvas(Modifier.size(40.dp)) {
        val centre = Offset(size.width / 2, size.height / 2)
        val full = size.minDimension / 2
        when (phase) {
            is VoiceChatPhase.Listening -> {
                // A halo that breathes while it waits and opens with the voice, around a disc that does the same.
                val halo = full * (0.62f + 0.38f * maxOf(swell, breath * 0.25f))
                drawCircle(tint.copy(alpha = 0.18f), halo, centre)
                drawCircle(tint, full * (0.36f + 0.2f * swell), centre)
            }
            VoiceChatPhase.Transcribing, is VoiceChatPhase.Working -> {
                val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                val inset = stroke.width / 2 + full * 0.3f
                drawCircle(tint.copy(alpha = 0.14f), full - inset, centre, style = stroke)
                drawArc(tint, startAngle = turn * 360f - 90f, sweepAngle = 100f, useCenter = false, topLeft = Offset(inset, inset), size = Size(size.width - 2 * inset, size.height - 2 * inset), style = stroke)
            }
            is VoiceChatPhase.Speaking -> {
                val bars = 5
                val width = 3.dp.toPx()
                val gap = 3.dp.toPx()
                val span = bars * width + (bars - 1) * gap
                repeat(bars) { i ->
                    val wave = (sin((turn * 2 * PI + i * 0.9)).toFloat() + 1f) / 2f
                    val height = full * (0.35f + 0.95f * wave)
                    val left = centre.x - span / 2 + i * (width + gap)
                    drawRoundRect(tint, Offset(left, centre.y - height / 2), Size(width, height), CornerRadius(width / 2))
                }
            }
            VoiceChatPhase.Muted, is VoiceChatPhase.Failed, VoiceChatPhase.Off -> {
                drawCircle(tint.copy(alpha = 0.18f), full * 0.62f, centre)
                drawCircle(tint, full * 0.3f, centre)
            }
        }
    }
}
