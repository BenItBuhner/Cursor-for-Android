package com.cursorforandroid.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.data.media.SpeakerVoice
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorMenu
import com.cursorforandroid.ui.components.CursorMenuItem
import com.cursorforandroid.ui.components.CursorMenuSeparator
import com.cursorforandroid.ui.components.CursorSheet
import com.cursorforandroid.ui.components.DockedRowHeight
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.Haptic
import com.cursorforandroid.ui.components.SheetHeader
import com.cursorforandroid.ui.components.TouchTarget
import com.cursorforandroid.ui.components.cursorSurface
import com.cursorforandroid.ui.components.dockedCard
import com.cursorforandroid.ui.components.fadingVerticalScroll
import com.cursorforandroid.ui.components.findActivity
import com.cursorforandroid.ui.components.openAppSettings
import com.cursorforandroid.ui.components.rememberHaptics
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The voice chat's words, as Cursor's own call tray has them, in one place for the trays, the notification and their tests. */
object VoiceChatCopy {
    /** Cursor's label for the waveform button in the Send slot. */
    const val Start = "Start voice chat"
    const val Title = "Voice chat"
    const val UntitledChat = "New Agent"
    const val Mute = "Mute"
    const val Unmute = "Unmute"
    const val HangUp = "Hang up"
    const val Settings = "Settings"
    const val ViewTranscript = "View transcript"
    const val Return = "Return"
    const val Dismiss = "Dismiss"
    const val VoiceCall = "Voice call"
    const val NothingSaid = "Nothing was said on this call."
    const val Voice = "Voice"
    const val Speed = "Speed"
    const val Language = "Language"
    const val DefaultVoice = "Default"

    /** The other chats' tray: "Voice chat with Fix the login bug". */
    fun with(title: String): String = "Voice chat with ${name(title)}"

    fun name(title: String): String = title.trim().ifEmpty { UntitledChat }

    /**
     * What the call is doing where there is no voice to draw: the agent's side of a call that takes turns. Null while
     * the line itself says it (listening, speaking, muted).
     */
    fun waiting(phase: VoiceChatPhase): String? = when (phase) {
        VoiceChatPhase.Transcribing -> "Thinking…"
        is VoiceChatPhase.Working -> "Agent is working…"
        else -> null
    }

    /** One line for the notification. */
    fun status(state: VoiceChatState): String = when (val phase = state.phase) {
        VoiceChatPhase.Off -> ""
        is VoiceChatPhase.Failed -> phase.message
        is VoiceChatPhase.Listening -> if (state.muted) "Muted" else "Listening"
        VoiceChatPhase.Muted -> "Muted"
        VoiceChatPhase.Transcribing -> "Thinking"
        is VoiceChatPhase.Working -> "Agent is working"
        is VoiceChatPhase.Speaking -> "Speaking"
    }

    /** Mute is the call's at any point of it, as on Cursor's tray: the agent goes on working and answering. */
    fun canMute(state: VoiceChatState): Boolean = state.phase != VoiceChatPhase.Off && state.phase !is VoiceChatPhase.Failed

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
    const val Tray = "voice-call-tray"
    const val ReturnTray = "voice-call-return"
    const val Line = "voice-call-line"
    const val End = "voice-call-end"
    const val Mute = "voice-call-mute"
    const val Settings = "voice-call-settings"
    const val SettingsMenu = "voice-call-settings-menu"
    const val Transcript = "voice-call-transcript"
    const val TranscriptSheet = "voice-call-transcript-sheet"
    const val Return = "voice-call-return-button"
    const val Label = "voice-call-label"
    const val Chip = "voice-call-chip"
}

/**
 * The words of this chat that were said on a call rather than typed, so their bubbles wear Cursor's "Voice call"
 * label. Provided by the conversation screen from [VoiceCalls.sentByVoice].
 */
val LocalVoiceSent = staticCompositionLocalOf<Set<String>> { emptySet() }

/** The live call's line, for the sidebar's "Voice chat in progress" pill on the chat it is with. */
val LocalVoiceCallLine = compositionLocalOf<VoiceCalls.Line?> { null }

/**
 * Cursor's sidebar pill on the chat a call is with: the success tone's soft fill, the waveform glyph and the call's
 * clock, in place of the row's trailing details.
 */
@Composable
fun VoiceCallChip(startedAtMs: Long, modifier: Modifier = Modifier, elapsed: String = rememberElapsed(startedAtMs)) {
    val colors = CursorTheme.colors
    Row(
        modifier
            .background(colors.green.copy(alpha = 0.14f), CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Voice chat in progress, $elapsed" }
            .testTag(VoiceChatTags.Chip),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CursorIcons.CallWaveform, null, tint = colors.green, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(elapsed, style = CursorTheme.typography.small, color = colors.green, maxLines = 1)
    }
}

/**
 * Starting a call from a chat's Send slot: the microphone permission first when it has not been granted, then
 * [start]. A refusal is told to [denied] (permanently when Android will no longer ask), which shows on the tray.
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

/**
 * [VoiceCallTray] for the live call, with its settings menu and transcript sheet. After a failure, Retry listens
 * again, or opens the app's settings page when the microphone permission is off for good.
 */
@Composable
fun VoiceCallTray(calls: VoiceCalls, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by calls.state.collectAsStateWithLifecycle()
    val settings by calls.settings.collectAsStateWithLifecycle()
    val line = calls.line.collectAsStateWithLifecycle().value ?: return
    var transcript by rememberSaveable { mutableStateOf(false) }
    val elapsed = rememberElapsed(line.startedAtMs)
    VoiceCallTray(
        state = state,
        elapsed = elapsed,
        onMute = calls::toggleMute,
        onHangUp = calls::end,
        onTranscript = { transcript = true },
        onRetry = { if ((state.phase as? VoiceChatPhase.Failed)?.opensSettings == true) openAppSettings(context) else calls.tap() },
        settings = settings,
        voices = { calls.voices() },
        onSettings = calls::setSettings,
        modifier = modifier,
    )
    if (transcript) {
        VoiceCallTranscriptSheet(
            turns = state.turns,
            startedAtMs = line.startedAtMs,
            status = if (state.active) "Ongoing · $elapsed" else "Ended · $elapsed",
            onDismiss = { transcript = false },
        )
    }
}

/**
 * Cursor's call tray, docked over the composer as its goal and queue trays are, so the composer stays where it was
 * and can still be typed in: a muted "Voice chat" with the call's clock, and under it the call's line across the
 * tray (the voice as it is heard, the agent's as it is read) with the round controls at its end: Mute, Settings
 * (voice, speed, language), View transcript and Hang up. A failure turns it into Cursor's error tray: the title,
 * what went wrong, and Dismiss.
 */
@Composable
fun VoiceCallTray(
    state: VoiceChatState,
    elapsed: String,
    onMute: () -> Unit,
    onHangUp: () -> Unit,
    onTranscript: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    settings: VoiceCallSettings = VoiceCallSettings(),
    voices: () -> List<SpeakerVoice> = { emptyList() },
    onSettings: (VoiceCallSettings) -> Unit = {},
    settingsOpen: Boolean = false,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val phase = state.phase
    val start = CursorDimens.composerPadding + CursorDimens.composerTextInset
    Column(
        modifier
            .fillMaxWidth()
            .dockedCard()
            .testTag(VoiceChatTags.Tray),
    ) {
        if (phase is VoiceChatPhase.Failed) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = DockedRowHeight).padding(start = start, end = 6.dp).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Text(VoiceChatCopy.Title, style = type.small, color = colors.textTertiary, maxLines = 1)
                    Text(phase.message, style = type.base, color = colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (!phase.resumes) {
                    Spacer(Modifier.width(8.dp))
                    CursorButton(if (phase.opensSettings) "Settings" else "Retry", onRetry, height = CursorDimens.composerFooter)
                }
                Spacer(Modifier.width(2.dp))
                FlatIconButton(CursorIcons.CallClose, VoiceChatCopy.Dismiss, onClick = onHangUp, modifier = Modifier.testTag(VoiceChatTags.End))
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(start = start, end = CursorDimens.composerPadding, top = 8.dp)) {
            Text(VoiceChatCopy.Title, style = type.small, color = colors.textTertiary, maxLines = 1)
            Text(" · $elapsed", style = type.small, color = colors.textQuaternary, maxLines = 1)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = start, end = 8.dp, top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(TrayButton), contentAlignment = Alignment.CenterStart) {
                AnimatedContent(
                    targetState = VoiceChatCopy.waiting(phase),
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                    label = "voice-call-line",
                ) { words ->
                    if (words != null) {
                        Text(words, style = type.base, color = colors.textTertiary, maxLines = 1, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    } else {
                        VoiceLine(phase = phase, level = state.level, muted = state.muted, modifier = Modifier.fillMaxWidth().testTag(VoiceChatTags.Line))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MuteButton(muted = state.muted, enabled = VoiceChatCopy.canMute(state), onMute = onMute, size = TrayButton)
                var menu by rememberSaveable { mutableStateOf(settingsOpen) }
                Box {
                    TrayRoundButton(
                        icon = CursorIcons.CallSettings,
                        description = VoiceChatCopy.Settings,
                        onClick = { menu = true },
                        tone = if (menu) Tone.On else Tone.Rest,
                        size = TrayButton,
                        modifier = Modifier.testTag(VoiceChatTags.Settings),
                    )
                    VoiceCallSettingsMenu(expanded = menu, onDismiss = { menu = false }, settings = settings, voices = voices, onChange = onSettings)
                }
                TrayRoundButton(
                    icon = CursorIcons.CallTranscript,
                    description = VoiceChatCopy.ViewTranscript,
                    onClick = onTranscript,
                    size = TrayButton,
                    modifier = Modifier.testTag(VoiceChatTags.Transcript),
                )
                TrayRoundButton(
                    icon = CursorIcons.CallHangUp,
                    description = VoiceChatCopy.HangUp,
                    onClick = onHangUp,
                    tone = Tone.Danger,
                    size = TrayButton,
                    modifier = Modifier.testTag(VoiceChatTags.End),
                )
            }
        }
    }
}

/**
 * The call seen from another chat, as Cursor's return tray has it: "Voice chat with" the call's chat and its clock,
 * then Mute, a hang-up X and Return, which goes back to the call's chat ([onReturn]).
 */
@Composable
fun VoiceCallReturnTray(calls: VoiceCalls, onReturn: (agentId: String) -> Unit, modifier: Modifier = Modifier) {
    val state by calls.state.collectAsStateWithLifecycle()
    val line = calls.line.collectAsStateWithLifecycle().value ?: return
    VoiceCallReturnTray(
        title = line.title,
        state = state,
        elapsed = rememberElapsed(line.startedAtMs),
        onMute = calls::toggleMute,
        onHangUp = calls::end,
        onReturn = { onReturn(line.agentId) },
        modifier = modifier,
    )
}

@Composable
fun VoiceCallReturnTray(
    title: String,
    state: VoiceChatState,
    elapsed: String,
    onMute: () -> Unit,
    onHangUp: () -> Unit,
    onReturn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .dockedCard()
            .heightIn(min = DockedRowHeight)
            .padding(start = CursorDimens.composerPadding + CursorDimens.composerTextInset, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag(VoiceChatTags.ReturnTray),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                VoiceChatCopy.with(title),
                style = type.base,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(" · $elapsed", style = type.base, color = colors.textTertiary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MuteButton(muted = state.muted, enabled = VoiceChatCopy.canMute(state), onMute = onMute, size = ReturnButton)
            TrayRoundButton(
                icon = CursorIcons.CallClose,
                description = VoiceChatCopy.HangUp,
                onClick = onHangUp,
                tone = Tone.Danger,
                size = ReturnButton,
                modifier = Modifier.testTag(VoiceChatTags.End),
            )
            Spacer(Modifier.width(2.dp))
            CursorButton(
                VoiceChatCopy.Return,
                onReturn,
                height = CursorDimens.composerFooter,
                modifier = Modifier
                    .semantics { contentDescription = "Return to the voice chat with ${VoiceChatCopy.name(title)}" }
                    .testTag(VoiceChatTags.Return),
            )
        }
    }
}

/** Which list the call's settings menu shows. */
enum class SettingsPage { Main, Voice, Speed, Language }

/** Cursor's settings menu on the call tray: Voice, Speed and Language, each opening onto its choices with the current one ticked. */
@Composable
fun VoiceCallSettingsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    settings: VoiceCallSettings,
    voices: () -> List<SpeakerVoice>,
    onChange: (VoiceCallSettings) -> Unit,
    initialPage: SettingsPage = SettingsPage.Main,
) {
    var page by remember(expanded) { mutableStateOf(initialPage) }
    CursorMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.testTag(VoiceChatTags.SettingsMenu)) {
        when (page) {
            SettingsPage.Main -> {
                val voiceLabel = settings.voice?.let { id -> voices().firstOrNull { it.id == id }?.label } ?: VoiceChatCopy.DefaultVoice
                SettingsRow(VoiceChatCopy.Voice, voiceLabel) { page = SettingsPage.Voice }
                SettingsRow(VoiceChatCopy.Speed, VoiceCallSettings.speedLabel(settings.speed)) { page = SettingsPage.Speed }
                SettingsRow(VoiceChatCopy.Language, VoiceCallSettings.languageLabel(settings.language)) { page = SettingsPage.Language }
            }
            SettingsPage.Voice -> {
                PageBack(VoiceChatCopy.Voice) { page = SettingsPage.Main }
                CursorMenuSeparator()
                Choice(VoiceChatCopy.DefaultVoice, settings.voice == null) { onChange(settings.copy(voice = null)); onDismiss() }
                voices().forEach { voice ->
                    Choice(voice.label, settings.voice == voice.id) { onChange(settings.copy(voice = voice.id)); onDismiss() }
                }
            }
            SettingsPage.Speed -> {
                PageBack(VoiceChatCopy.Speed) { page = SettingsPage.Main }
                CursorMenuSeparator()
                VoiceCallSettings.Speeds.forEach { speed ->
                    Choice(speed.label, settings.speed == speed.value) { onChange(settings.copy(speed = speed.value)); onDismiss() }
                }
            }
            SettingsPage.Language -> {
                PageBack(VoiceChatCopy.Language) { page = SettingsPage.Main }
                CursorMenuSeparator()
                VoiceCallSettings.Languages.forEach { language ->
                    // Another language has its own voices: the voice goes back to that language's default.
                    Choice(language.label, settings.language == language.tag) {
                        onChange(settings.copy(language = language.tag, voice = if (language.tag == settings.language) settings.voice else null))
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    CursorMenuItem(label = label, icon = null, onClick = onClick, trailing = {
        Text(value, style = CursorTheme.typography.base, color = CursorTheme.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
        Spacer(Modifier.width(4.dp))
        Icon(CursorIcons.ChevronRight, null, tint = CursorTheme.colors.iconQuaternary, modifier = Modifier.size(CursorDimens.menuIcon))
    })
}

@Composable
private fun PageBack(title: String, onBack: () -> Unit) {
    CursorMenuItem(label = title, icon = CursorIcons.ChevronLeft, onClick = onBack)
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    CursorMenuItem(label = label, icon = null, onClick = onClick, trailing = {
        if (selected) Icon(CursorIcons.Check, "Selected", tint = CursorTheme.colors.iconSecondary, modifier = Modifier.size(CursorDimens.menuIcon))
    })
}

/**
 * The call's transcript, as Cursor's transcript panel has it: "Voice call", when it started and how it stands, then
 * the turns in the chat's own rows (what was said in a bubble, the agent's answer as text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCallTranscriptSheet(turns: List<VoiceTurn>, startedAtMs: Long, status: String, onDismiss: () -> Unit) {
    CursorSheet(onDismiss = onDismiss, modifier = Modifier.testTag(VoiceChatTags.TranscriptSheet)) { dismiss ->
        SheetHeader(VoiceChatCopy.VoiceCall, trailing = { FlatIconButton(CursorIcons.CallClose, "Close", onClick = dismiss) })
        VoiceCallTranscript(turns = turns, started = startedClock(startedAtMs), status = status)
    }
}

@Composable
fun VoiceCallTranscript(turns: List<VoiceTurn>, started: String, status: String, modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Text("$started · $status", style = type.small, color = colors.textTertiary)
        Spacer(Modifier.height(16.dp))
        if (turns.isEmpty()) {
            Text(VoiceChatCopy.NothingSaid, style = type.base, color = colors.textTertiary, modifier = Modifier.padding(vertical = 12.dp))
        } else {
            Column(Modifier.fadingVerticalScroll(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                turns.forEach { turn ->
                    if (turn.fromUser) {
                        Box(Modifier.fillMaxWidth().padding(start = 32.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                turn.text,
                                style = type.message,
                                color = colors.textPrimary,
                                modifier = Modifier
                                    .cursorSurface(colors.fillFaint, colors.stroke, CursorTheme.shapes.xl)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        Text(turn.text, style = type.message, color = colors.textPrimary)
                    }
                }
            }
        }
    }
}

/** Cursor's "Voice call" attribution over a message that was said on a call. */
@Composable
fun VoiceCallLabel(modifier: Modifier = Modifier) {
    val colors = CursorTheme.colors
    Row(modifier.testTag(VoiceChatTags.Label), verticalAlignment = Alignment.CenterVertically) {
        Icon(CursorIcons.CallWaveform, null, tint = colors.iconTertiary, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(VoiceChatCopy.VoiceCall, style = CursorTheme.typography.small, color = colors.textTertiary)
    }
}

private enum class Tone { Rest, On, Danger }

private val TrayButton = 28.dp
private val ReturnButton = 24.dp

/** Cursor's round secondary icon button: a soft fill, the accent's while its menu is open, the red's for hanging up. */
@Composable
private fun TrayRoundButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Rest,
    enabled: Boolean = true,
    pressed: Boolean? = null,
) {
    val colors = CursorTheme.colors
    val fill by animateColorAsState(
        when (tone) {
            Tone.Rest -> colors.fill
            Tone.On -> colors.accent.copy(alpha = 0.18f)
            Tone.Danger -> colors.red.copy(alpha = 0.16f)
        },
        tween(160),
        label = "tray-button-fill",
    )
    val tint by animateColorAsState(
        when {
            !enabled -> colors.iconQuaternary
            tone == Tone.On -> colors.accent
            tone == Tone.Danger -> colors.red
            else -> colors.iconSecondary
        },
        tween(160),
        label = "tray-button-tint",
    )
    TouchTarget(
        size = size,
        touchSize = CursorDimens.roundButtonTouch,
        shape = CircleShape,
        onClick = onClick,
        enabled = enabled,
        contentDescription = description,
        modifier = modifier.then(if (pressed != null) Modifier.semantics { stateDescription = if (pressed) "On" else "Off" } else Modifier),
    ) {
        Box(Modifier.size(size).background(fill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(if (size >= TrayButton) 16.dp else 14.dp))
        }
    }
}

@Composable
private fun MuteButton(muted: Boolean, enabled: Boolean, onMute: () -> Unit, size: Dp) {
    TrayRoundButton(
        icon = if (muted) CursorIcons.CallMicSlash else CursorIcons.CallMic,
        description = if (muted) VoiceChatCopy.Unmute else VoiceChatCopy.Mute,
        onClick = onMute,
        size = size,
        enabled = enabled,
        pressed = muted,
        modifier = Modifier.testTag(VoiceChatTags.Mute),
    )
}

/** Seconds since [startedAtMs] (elapsed-realtime), ticking once a second. */
@Composable
internal fun rememberElapsed(startedAtMs: Long): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000L - (now - startedAtMs) % 1000L)
        }
    }
    return VoiceChatCopy.elapsed(now - startedAtMs)
}

/** The wall-clock time an elapsed-realtime start fell at, in the device's short time format: "3:42 PM". */
private fun startedClock(startedAtMs: Long): String {
    val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAtMs)
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(wall))
}

/**
 * The call's line, as Cursor's tray draws it: a spectrum of thin round-ended bars across the tray, 2dp wide with 3dp
 * between, in the text colour and fading out at both ends. It follows the voice while the microphone hears one,
 * sweeps while the agent's answer is read out, and lies flat as a row of dots while muted.
 */
@Composable
fun VoiceLine(phase: VoiceChatPhase, level: Float, muted: Boolean, modifier: Modifier = Modifier, height: Dp = 20.dp) {
    val colors = CursorTheme.colors
    val listening = phase is VoiceChatPhase.Listening && !muted
    val speaking = phase is VoiceChatPhase.Speaking
    val voice by animateFloatAsState(
        when {
            listening -> 0.12f + 0.88f * level.coerceIn(0f, 1f)
            speaking -> 0.75f
            else -> 0f
        },
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "voice-line-amplitude",
    )
    val motion = rememberInfiniteTransition(label = "voice-line")
    val turn by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "voice-line-turn")
    val tint = if (muted) colors.iconTertiary else colors.textPrimary
    Canvas(modifier.height(height)) {
        val bar = 2.dp.toPx()
        val gap = 3.dp.toPx()
        val count = max(1, floor((size.width + gap) / (bar + gap)).toInt())
        val used = count * bar + (count - 1) * gap
        val left = (size.width - used) / 2f
        val fadeBars = max(1f, min(10f, count / 4f))
        val t = turn * 2f * PI.toFloat()
        repeat(count) { i ->
            val x = i.toFloat()
            // Two slow waves against each other read as speech rather than a sine; the middle of the line carries more.
            val ripple = abs(sin(x * 0.42f + t * 3f) * 0.6f + sin(x * 0.17f - t * 2f) * 0.4f)
            val centre = 1f - abs((x - count / 2f) / (count / 2f)) * 0.45f
            val h = max(bar, size.height * voice * ripple * centre)
            val edge = min(x + 1f, count - x).coerceAtMost(fadeBars) / fadeBars
            drawRoundRect(
                tint.copy(alpha = tint.alpha * edge.coerceIn(0f, 1f)),
                Offset(left + i * (bar + gap), (size.height - h) / 2f),
                Size(bar, h),
                CornerRadius(bar / 2f),
            )
        }
    }
}
