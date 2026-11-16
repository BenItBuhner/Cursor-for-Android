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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.dockedCard
import com.cursorforandroid.ui.components.findActivity
import com.cursorforandroid.ui.components.openAppSettings
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/** The voice chat's words, in one place for the card, the strip, the notification and their tests. */
object VoiceChatCopy {
    /** Cursor's own label for the waveform button in the Send slot. */
    const val Start = "Start voice chat"
    const val End = "End voice chat"
    const val Mute = "Mute"
    const val Unmute = "Unmute"
    const val Return = "Return to call"
    const val UntitledChat = "Voice chat"

    fun title(phase: VoiceChatPhase): String = when (phase) {
        VoiceChatPhase.Off -> ""
        is VoiceChatPhase.Listening -> "Listening"
        VoiceChatPhase.Transcribing -> "Thinking"
        is VoiceChatPhase.Working -> "Agent is working"
        is VoiceChatPhase.Speaking -> "Speaking"
        VoiceChatPhase.Muted -> "Muted"
        is VoiceChatPhase.Failed -> "Voice chat"
    }

    /** The second line: what was said or is being read where there is something, and otherwise what a tap would do. */
    fun detail(state: VoiceChatState): String = when (val phase = state.phase) {
        VoiceChatPhase.Off -> ""
        is VoiceChatPhase.Listening -> when {
            phase.heard -> "Pause to send, or tap to send now."
            state.lastSaid != null -> "Go on, or say goodbye to hang up."
            else -> "Say what you want done. A pause sends it."
        }
        VoiceChatPhase.Transcribing -> "Catching what you said."
        is VoiceChatPhase.Working -> "“${phase.said}”"
        is VoiceChatPhase.Speaking -> phase.text
        VoiceChatPhase.Muted -> "Your microphone is off. Unmute to go on."
        is VoiceChatPhase.Failed -> phase.message
    }

    /** One line for the notification and the other chats' strip. */
    fun status(state: VoiceChatState): String = when (val phase = state.phase) {
        is VoiceChatPhase.Failed -> phase.message
        VoiceChatPhase.Off -> ""
        else -> title(phase)
    }

    /** The microphone can be muted while it is the call's: listening, or already muted. */
    fun canMute(state: VoiceChatState): Boolean = state.phase is VoiceChatPhase.Listening || state.phase == VoiceChatPhase.Muted

    /** `0:42`, `12:05`, `1:02:09`. */
    fun elapsed(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}

object VoiceChatTags {
    const val Card = "voice-call-card"
    const val Strip = "voice-call-strip"
    const val End = "voice-call-end"
    const val Mute = "voice-call-mute"
}

/**
 * Starting a call from a chat's Send slot: the microphone permission first when it has not been granted, then
 * [start]. A refusal is told to [denied] (permanently when Android will no longer ask), which shows on the card.
 */
@Composable
fun rememberVoiceCallStarter(start: () -> Unit, denied: (permanently: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            start()
        } else {
            val activity = context.findActivity()
            val canAskAgain = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
            denied(!canAskAgain)
        }
    }
    return remember(permission, start) {
        {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** The call's haptics, felt on whichever screen is up while it changes turn. */
@Composable
fun VoiceCallHaptics(calls: VoiceCalls) {
    val haptics = rememberHaptics()
    SideEffect {
        calls.onPhase = { phase ->
            when (phase) {
                is VoiceChatPhase.Listening -> if (!phase.heard) haptics.perform(Haptic.ToggleOn)
                VoiceChatPhase.Transcribing -> haptics.perform(Haptic.Confirm)
                is VoiceChatPhase.Failed -> haptics.perform(Haptic.Reject)
                VoiceChatPhase.Off, VoiceChatPhase.Muted -> haptics.perform(Haptic.ToggleOff)
                is VoiceChatPhase.Working, is VoiceChatPhase.Speaking -> Unit
            }
        }
    }
}

/** [VoiceCallCard] for the live call: a tap on a refused permission opens Settings, any other tap is the call's shortcut. */
@Composable
fun VoiceCallCard(calls: VoiceCalls, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by calls.state.collectAsStateWithLifecycle()
    val line by calls.line.collectAsStateWithLifecycle()
    val startedAt = line?.startedAtMs ?: return
    // The card arrives where the composer stood: it rises a little and comes into focus rather than replacing it at a cut.
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrival.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
    VoiceCallCard(
        state = state,
        elapsed = rememberElapsed(startedAt),
        onTap = { if ((state.phase as? VoiceChatPhase.Failed)?.opensSettings == true) openAppSettings(context) else calls.tap() },
        onMute = calls::toggleMute,
        onEnd = calls::end,
        modifier = modifier.graphicsLayer {
            val t = arrival.value
            alpha = t.coerceIn(0f, 1f)
            translationY = (1f - t) * 12.dp.toPx()
        },
    )
}

/**
 * A voice chat where the composer stands, in the composer's own box: the same surface, stroke, corners and padding, so
 * the chat's bottom keeps its shape through the call. The waveform and the call's state lead its top line with the
 * timer at the end; under it, what was said or is being read; and the footer keeps the composer's: Mute where the
 * footer's controls start, and in the Send slot the red disc that hangs up. The body is the call's shortcut ([onTap]).
 */
@Composable
fun VoiceCallCard(state: VoiceChatState, elapsed: String, onTap: () -> Unit, onMute: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val phase = state.phase
    val failed = phase is VoiceChatPhase.Failed
    val shape = remember { RoundedCornerShape(CursorDimens.composerRadius) }
    val pad = CursorDimens.composerPadding
    Column(
        modifier
            .fillMaxWidth()
            .cursorSurface(colors.elevated, colors.strokeSubtle, shape)
            .testTag(VoiceChatTags.Card),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .pressable(onClick = onTap, shape = shape)
                .padding(start = pad + CursorDimens.composerTextInset, end = pad + CursorDimens.composerTextInset, top = pad + 2.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CallWave(phase = phase, level = state.level, height = 16.dp)
                Spacer(Modifier.width(10.dp))
                AnimatedContent(
                    targetState = VoiceChatCopy.title(phase),
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    label = "voice-call-title",
                    modifier = Modifier.weight(1f),
                ) { text ->
                    Text(text, style = type.baseMedium, color = if (failed) colors.red else colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(elapsed, style = type.small, color = colors.textTertiary, maxLines = 1)
            }
            AnimatedContent(
                targetState = VoiceChatCopy.detail(state),
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "voice-call-detail",
                modifier = Modifier.padding(top = 4.dp).heightIn(min = 44.dp),
            ) { text ->
                Text(text, style = type.input, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // The composer's footer, with the call's two controls in its places.
        Row(
            Modifier.fillMaxWidth().padding(start = pad, end = pad, top = 6.dp, bottom = pad - 2.dp + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val muted = phase == VoiceChatPhase.Muted
            val canMute = VoiceChatCopy.canMute(state)
            FooterDisc(
                fill = if (muted) colors.textPrimary else colors.fill,
                tint = if (muted) colors.canvas else if (canMute) colors.iconSecondary else colors.iconQuaternary,
                icon = if (muted) CursorIcons.MicOff else CursorIcons.Mic,
                description = if (muted) VoiceChatCopy.Unmute else VoiceChatCopy.Mute,
                onClick = if (canMute) onMute else null,
                modifier = Modifier.testTag(VoiceChatTags.Mute),
            )
            Spacer(Modifier.weight(1f))
            FooterDisc(
                fill = colors.red,
                tint = Color.White,
                icon = CursorIcons.PhoneOff,
                description = VoiceChatCopy.End,
                onClick = onEnd,
                modifier = Modifier.testTag(VoiceChatTags.End),
            )
        }
    }
}

/**
 * The call seen from another chat: a docked card over that chat's composer saying whose call it is and what it is
 * doing, which leads back to it ([onOpen]), with the hang-up at its end.
 */
@Composable
fun VoiceCallStrip(calls: VoiceCalls, onOpen: (agentId: String) -> Unit, modifier: Modifier = Modifier) {
    val state by calls.state.collectAsStateWithLifecycle()
    val line = calls.line.collectAsStateWithLifecycle().value ?: return
    VoiceCallStrip(
        title = line.title,
        state = state,
        elapsed = rememberElapsed(line.startedAtMs),
        onOpen = { onOpen(line.agentId) },
        onEnd = calls::end,
        modifier = modifier,
    )
}

@Composable
fun VoiceCallStrip(title: String, state: VoiceChatState, elapsed: String, onOpen: () -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.xl
    Row(
        modifier
            .fillMaxWidth()
            .dockedCard(shape = shape)
            .pressable(onClick = onOpen, shape = shape)
            .semantics { contentDescription = "${VoiceChatCopy.Return}: $title"; role = Role.Button }
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag(VoiceChatTags.Strip),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CallWave(phase = state.phase, level = state.level, height = 14.dp)
        Spacer(Modifier.width(10.dp))
        val line = buildAnnotatedString {
            withStyle(type.baseMedium.toSpanStyle().copy(color = colors.textPrimary)) { append(title) }
            withStyle(type.small.toSpanStyle().copy(color = colors.textTertiary)) { append("  ·  ${VoiceChatCopy.status(state)}  ·  $elapsed") }
        }
        Text(line, style = type.baseMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        FooterDisc(fill = colors.red, tint = Color.White, icon = CursorIcons.PhoneOff, description = VoiceChatCopy.End, onClick = onEnd)
    }
}

/** The composer's footer disc (Send's size and touch), here for Mute and Hang up. */
@Composable
private fun FooterDisc(fill: Color, tint: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val animatedFill by animateColorAsState(fill, tween(180), label = "disc-fill")
    val animatedTint by animateColorAsState(tint, tween(180), label = "disc-tint")
    Box(modifier.size(CursorDimens.roundButtonTouch), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredSize(CursorDimens.roundButtonTouch)
                .then(if (onClick != null) Modifier.pressable(onClick = onClick, shape = CircleShape) else Modifier)
                .semantics { contentDescription = description; role = Role.Button },
        )
        Box(Modifier.size(CursorDimens.roundButton).background(animatedFill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = animatedTint, modifier = Modifier.size(CursorDimens.roundButtonGlyph - 2.dp))
        }
    }
}

/** Seconds since [startedAtMs] (elapsed-realtime), ticking once a second. */
@Composable
private fun rememberElapsed(startedAtMs: Long): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000L - (now - startedAtMs) % 1000L)
        }
    }
    return VoiceChatCopy.elapsed(now - startedAtMs)
}

/**
 * The call's waveform: Cursor's audio-lines glyph come alive. Its six bars follow the voice while the microphone
 * hears one, sweep in a wave while the agent speaks, ripple slowly while the call waits on Cursor, and lie flat when
 * muted. Green while it is yours to talk, the text colour while it is the agent's, red after a failure.
 */
@Composable
fun CallWave(phase: VoiceChatPhase, level: Float, height: Dp, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val tint by animateColorAsState(
        when (phase) {
            is VoiceChatPhase.Listening -> colors.green
            is VoiceChatPhase.Failed -> colors.red
            VoiceChatPhase.Muted, VoiceChatPhase.Off -> colors.iconTertiary
            else -> colors.textPrimary
        },
        tween(220),
        label = "call-wave-tint",
    )
    val voice by animateFloatAsState(
        if (phase is VoiceChatPhase.Listening) level.coerceIn(0f, 1f) else 0f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "call-wave-voice",
    )
    val motion = rememberInfiniteTransition(label = "call-wave")
    val period = when (phase) {
        is VoiceChatPhase.Speaking -> 900
        else -> 1600
    }
    val turn by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "call-wave-turn")
    // Lucide audio-lines' bar heights, out of 18: the glyph at rest.
    val rest = remember { floatArrayOf(3f, 11f, 18f, 7f, 13f, 3f) }
    Canvas(modifier.size(width = height * 1.25f, height = height)) {
        val bars = rest.size
        val stroke = size.width / (bars * 2f - 1f)
        val gap = stroke
        repeat(bars) { i ->
            val base = rest[i] / 18f
            val wave = (sin(turn * 2 * PI - i * 0.8).toFloat() + 1f) / 2f
            val share = when (phase) {
                is VoiceChatPhase.Listening -> 0.2f + 0.8f * (0.25f * base + voice * (0.4f + 0.6f * wave))
                is VoiceChatPhase.Speaking -> 0.25f + 0.75f * wave
                VoiceChatPhase.Transcribing, is VoiceChatPhase.Working -> 0.2f + 0.35f * wave
                else -> 0.18f
            }.coerceIn(0.15f, 1f)
            val h = size.height * share
            val left = i * (stroke + gap)
            drawRoundRect(tint, Offset(left, (size.height - h) / 2), Size(stroke, h), CornerRadius(stroke / 2))
        }
    }
}
