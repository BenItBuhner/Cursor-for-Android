package com.cursorforandroid.ui.computers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.ComputerRepository
import com.cursorforandroid.domain.ComputerPairing
import com.cursorforandroid.domain.ComputerPresence
import com.cursorforandroid.domain.ControllerTrust
import com.cursorforandroid.domain.LocalAgentLine
import com.cursorforandroid.domain.LocalAgentLineKind
import com.cursorforandroid.domain.LocalAgentSession
import com.cursorforandroid.domain.LocalAgentStatus
import com.cursorforandroid.domain.LocalWorkspace
import com.cursorforandroid.ui.components.CursorButton
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.StylusTextInput
import com.cursorforandroid.ui.components.contentColumn
import com.cursorforandroid.ui.components.fadingVerticalScroll
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.components.stylusWriting
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ComputerScreen(
    graph: AppGraph,
    targetId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by graph.computers.detail(targetId).collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(targetId) { graph.computers.refreshDetail(targetId) }
    val pending = state.challenge?.status?.awaitingDesktop == true
    val trustedOnline =
        state.challenge?.status == ControllerTrust.TRUSTED && state.computer?.presence == ComputerPresence.ONLINE
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(targetId, pending) {
        if (!pending) return@LaunchedEffect
        while (true) {
            delay(2_000)
            graph.computers.refreshDetail(targetId)
        }
    }
    LaunchedEffect(targetId, trustedOnline) {
        if (!trustedOnline) return@LaunchedEffect
        graph.computers.watchInbox(targetId)
    }
    LaunchedEffect(state.sessions) {
        if (selected != null && state.sessions.none { it.sessionId == selected }) {
            selected = state.sessions.firstOrNull()?.sessionId
        }
        if (selected == null) selected = state.sessions.firstOrNull()?.sessionId
    }
    LaunchedEffect(targetId, selected, trustedOnline) {
        val sessionId = selected ?: return@LaunchedEffect
        if (!trustedOnline) return@LaunchedEffect
        graph.computers.attach(targetId, sessionId)
    }
    val title = state.computer?.displayName ?: ComputersCopy.HEADER
    Column(modifier.fillMaxSize().background(CursorTheme.colors.canvas).testTag(ComputersTags.DETAIL)) {
        CursorHeader(
            title = title,
            subtitle = state.computer?.let { subtitle(it) },
            leading = { FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack) },
            trailing = {
                if (state.loading) SpinnerRing()
                else FlatIconButton(CursorIcons.Refresh, "Refresh", onClick = { scope.launch { graph.computers.refreshDetail(targetId) } })
            },
        )
        ComputerDetail(
            state = state,
            onPair = { scope.launch { graph.computers.requestPairing(targetId) } },
            onReply = { sessionId, text -> scope.launch { graph.computers.reply(targetId, sessionId, text) } },
            onStart = { text, paths -> scope.launch { graph.computers.start(targetId, text, paths) } },
            selectedSessionId = selected,
            onSelectSession = { selected = it },
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        )
    }
}

@Composable
fun ComputerDetail(
    state: ComputerRepository.DetailState,
    onPair: () -> Unit,
    onReply: (sessionId: String, text: String) -> Unit,
    onStart: (text: String, workspacePaths: List<String>) -> Unit,
    selectedSessionId: String? = null,
    onSelectSession: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Column(
        modifier
            .fadingVerticalScroll(surface = colors.canvas)
            .contentColumn()
            .padding(bottom = 24.dp),
    ) {
        if (!state.allowed) {
            Spacer(Modifier.height(16.dp))
            Text(ComputersCopy.NEEDS_MODE_TITLE, style = type.sectionTitle, color = colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(state.error ?: ComputersCopy.NEEDS_MODE_BODY, style = type.base, color = colors.textSecondary)
            return
        }
        if (state.error != null) {
            Spacer(Modifier.height(16.dp))
            Text(state.error, style = type.base, color = colors.textSecondary)
        }
        if (state.notice != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.notice, style = type.small, color = colors.textSecondary)
        }
        val computer = state.computer
        val challenge = state.challenge
        val trust = challenge?.status
        when {
            trust == ControllerTrust.PENDING || computer?.pairing == ComputerPairing.PENDING -> {
                Spacer(Modifier.height(20.dp))
                Text(ComputersCopy.CODE_LABEL, style = type.small, color = colors.textTertiary)
                Spacer(Modifier.height(6.dp))
                Text(
                    challenge?.verificationCode ?: "————-————",
                    style = type.sectionTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.testTag(ComputersTags.CODE),
                )
                Spacer(Modifier.height(12.dp))
                Text(ComputersCopy.APPROVE, style = type.base, color = colors.textSecondary)
            }
            trust == ControllerTrust.TRUSTED && computer?.presence == ComputerPresence.OFFLINE -> {
                Spacer(Modifier.height(16.dp))
                Text(ComputersCopy.OFFLINE, style = type.base, color = colors.textSecondary)
            }
            trust == ControllerTrust.TRUSTED -> {
                Inbox(
                    sessions = state.sessions,
                    workspaces = state.workspaces,
                    sending = state.sending,
                    selectedSessionId = selectedSessionId,
                    onSelectSession = onSelectSession,
                    transcript = state.transcript,
                    attachError = state.attachError,
                    watchError = state.watchError,
                    onReply = onReply,
                    onStart = onStart,
                )
            }
            else -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    when (trust) {
                        ControllerTrust.REVOKED -> ComputersCopy.REVOKED
                        ControllerTrust.REJECTED -> ComputersCopy.REJECTED
                        else -> ComputersCopy.UNPAIRED
                    },
                    style = type.base,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(16.dp))
                CursorButton(
                    text = ComputersCopy.PAIR,
                    onClick = onPair,
                    primary = true,
                    enabled = !state.loading,
                    modifier = Modifier.testTag(ComputersTags.PAIR_BUTTON),
                )
            }
        }
    }
}

@Composable
private fun Inbox(
    sessions: List<LocalAgentSession>,
    workspaces: List<LocalWorkspace>,
    sending: Boolean,
    selectedSessionId: String?,
    onSelectSession: (String) -> Unit,
    transcript: List<LocalAgentLine>,
    attachError: String?,
    watchError: String?,
    onReply: (sessionId: String, text: String) -> Unit,
    onStart: (text: String, workspacePaths: List<String>) -> Unit,
) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    var selected by rememberSaveable { mutableStateOf(selectedSessionId ?: sessions.firstOrNull()?.sessionId) }
    if (selectedSessionId != null) selected = selectedSessionId
    if (selected != null && sessions.none { it.sessionId == selected }) selected = sessions.firstOrNull()?.sessionId
    var reply by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf("") }
    var workspace by rememberSaveable { mutableStateOf(workspaces.firstOrNull()?.workspacePaths?.joinToString("\n").orEmpty()) }
    Spacer(Modifier.height(16.dp))
    Text(ComputersCopy.INBOX, style = type.small, color = colors.textTertiary)
    Spacer(Modifier.height(6.dp))
    if (watchError != null) {
        Spacer(Modifier.height(6.dp))
        Text(watchError, style = type.small, color = colors.textTertiary)
    }
    if (sessions.isEmpty()) {
        Text(ComputersCopy.NO_AGENTS, style = type.base, color = colors.textSecondary)
    } else {
        CursorCard(Modifier.fillMaxWidth()) {
            sessions.forEachIndexed { index, session ->
                if (index > 0) HairlineDivider()
                SessionRow(
                    session,
                    selected = session.sessionId == selected,
                    onClick = {
                        selected = session.sessionId
                        onSelectSession(session.sessionId)
                    },
                )
            }
        }
        if (transcript.isNotEmpty() || attachError != null) {
            Spacer(Modifier.height(16.dp))
            Text(ComputersCopy.TRANSCRIPT, style = type.small, color = colors.textTertiary)
            Spacer(Modifier.height(6.dp))
            if (attachError != null) {
                Text(attachError, style = type.small, color = colors.textTertiary)
                Spacer(Modifier.height(6.dp))
            }
            Column(Modifier.fillMaxWidth().testTag(ComputersTags.TRANSCRIPT)) {
                transcript.forEach { line ->
                    val color = when (line.kind) {
                        LocalAgentLineKind.USER -> colors.textPrimary
                        LocalAgentLineKind.ASSISTANT -> colors.textSecondary
                        LocalAgentLineKind.NOTICE -> colors.textTertiary
                        LocalAgentLineKind.STATUS -> colors.textQuaternary
                    }
                    Text(line.text, style = type.base, color = color)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(ComputersCopy.REPLY, style = type.small, color = colors.textTertiary)
        Spacer(Modifier.height(6.dp))
        MessageField(value = reply, onValueChange = { reply = it }, tag = ComputersTags.REPLY_FIELD)
        Spacer(Modifier.height(8.dp))
        CursorButton(
            text = ComputersCopy.REPLY,
            onClick = {
                val id = selected ?: return@CursorButton
                onReply(id, reply)
                reply = ""
            },
            primary = true,
            enabled = !sending && !selected.isNullOrBlank() && reply.isNotBlank(),
            modifier = Modifier.testTag(ComputersTags.REPLY_SEND),
        )
    }
    Spacer(Modifier.height(20.dp))
    Text(ComputersCopy.START, style = type.small, color = colors.textTertiary)
    Spacer(Modifier.height(4.dp))
    Text(ComputersCopy.START_HINT, style = type.small, color = colors.textQuaternary)
    if (workspaces.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(ComputersCopy.WORKSPACE, style = type.small, color = colors.textTertiary)
        Spacer(Modifier.height(6.dp))
        workspaces.forEach { item ->
            val paths = item.workspacePaths.joinToString("\n")
            val chosen = workspace == paths || (workspace.isEmpty() && item == workspaces.first())
            WorkspaceChip(label = item.displayPath, selected = chosen, onClick = { workspace = paths })
        }
    }
    Spacer(Modifier.height(8.dp))
    MessageField(value = start, onValueChange = { start = it }, tag = ComputersTags.START_FIELD)
    Spacer(Modifier.height(8.dp))
    CursorButton(
        text = ComputersCopy.START,
        onClick = {
            val paths = workspace.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            onStart(start, paths)
            start = ""
        },
        primary = true,
        enabled = !sending && start.isNotBlank(),
        modifier = Modifier.testTag(ComputersTags.START_SEND),
    )
}

@Composable
private fun SessionRow(session: LocalAgentSession, selected: Boolean, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val status = when (session.status) {
        LocalAgentStatus.RUNNING -> "Running"
        LocalAgentStatus.AWAITING_INPUT -> "Waiting"
        LocalAgentStatus.IDLE -> "Idle"
        LocalAgentStatus.UNKNOWN -> "Local"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, RectangleShape, role = Role.Button)
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag(ComputersTags.sessionRow(session.sessionId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(session.title, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(status, session.workspace?.displayPath).joinToString(" · ")
            Text(detail, style = type.small, color = if (selected) colors.textSecondary else colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WorkspaceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    Text(
        label,
        style = CursorTheme.typography.small,
        color = if (selected) colors.textPrimary else colors.textSecondary,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .pressable(onClick, CursorTheme.shapes.base, role = Role.Button)
            .background(if (selected) colors.fillFaint else colors.canvas, CursorTheme.shapes.base)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MessageField(value: String, onValueChange: (String) -> Unit, tag: String) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .stylusWriting()
            .background(colors.fillFaint, shape)
            .border(CursorDimens.hairline, colors.strokeSubtle, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        StylusTextInput {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = type.base.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier.fillMaxWidth().testTag(tag),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(ComputersCopy.MESSAGE, style = type.base, color = colors.textQuaternary)
                        inner()
                    }
                },
            )
        }
    }
}
