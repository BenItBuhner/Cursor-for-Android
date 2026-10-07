package com.cursorforandroid.ui.computers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.repo.ComputerRepository
import com.cursorforandroid.domain.Computer
import com.cursorforandroid.domain.ComputerPairing
import com.cursorforandroid.domain.ComputerPresence
import com.cursorforandroid.ui.components.CursorCard
import com.cursorforandroid.ui.components.CursorHeader
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.HairlineDivider
import com.cursorforandroid.ui.components.SpinnerRing
import com.cursorforandroid.ui.components.contentColumn
import com.cursorforandroid.ui.components.fadingVerticalScroll
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorDimens
import com.cursorforandroid.ui.theme.CursorTheme
import kotlinx.coroutines.launch

@Composable
fun ComputersScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    onOpenComputer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by graph.computers.list.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { graph.computers.refreshList() }
    Column(modifier.fillMaxSize().background(CursorTheme.colors.canvas).testTag(ComputersTags.PAGE)) {
        CursorHeader(
            title = ComputersCopy.HEADER,
            leading = { FlatIconButton(CursorIcons.ChevronLeft, "Back", onClick = onBack) },
            trailing = {
                if (state.loading) SpinnerRing()
                else FlatIconButton(CursorIcons.Refresh, "Refresh", onClick = { scope.launch { graph.computers.refreshList() } })
            },
        )
        ComputersList(
            state = state,
            onOpenComputer = onOpenComputer,
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        )
    }
}

@Composable
fun ComputersList(
    state: ComputerRepository.ListState,
    onOpenComputer: (String) -> Unit,
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
            Spacer(Modifier.height(12.dp))
            Text(ComputersCopy.MACHINES_HINT, style = type.small, color = colors.textTertiary)
            return
        }
        if (state.error != null) {
            Spacer(Modifier.height(16.dp))
            Text(state.error, style = type.base, color = colors.textSecondary)
        }
        if (state.computers.isEmpty() && !state.loading && state.error == null) {
            Spacer(Modifier.height(16.dp))
            Text(ComputersCopy.EMPTY, style = type.base, color = colors.textSecondary)
        }
        if (state.computers.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CursorCard(Modifier.fillMaxWidth()) {
                state.computers.forEachIndexed { index, computer ->
                    if (index > 0) HairlineDivider()
                    ComputerRow(computer, onClick = { onOpenComputer(computer.targetId) })
                }
            }
        }
    }
}

@Composable
private fun ComputerRow(computer: Computer, onClick: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, RectangleShape, role = Role.Button)
            .heightIn(min = CursorDimens.listRow)
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag(ComputersTags.computerRow(computer.targetId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(computer.displayName, style = type.base, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle(computer), style = type.small, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Icon(CursorIcons.ChevronRight, null, tint = colors.iconQuaternary, modifier = Modifier.size(16.dp))
    }
}

internal fun subtitle(computer: Computer): String {
    val presence = when (computer.presence) {
        ComputerPresence.ONLINE -> "Online"
        ComputerPresence.OFFLINE -> "Offline"
        ComputerPresence.UNKNOWN -> "Status unknown"
    }
    val pairing = when (computer.pairing) {
        ComputerPairing.PAIRED -> "Paired"
        ComputerPairing.PENDING -> "Waiting for desktop approve"
        ComputerPairing.UNPAIRED -> "Not paired"
        ComputerPairing.REVOKED -> "Revoked"
        ComputerPairing.UNKNOWN -> "Pairing unknown"
    }
    return "$presence · $pairing"
}
