package com.cursorforandroid.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.DeviceOption
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.FlatIconButton
import com.cursorforandroid.ui.components.pressable
import com.cursorforandroid.ui.theme.CursorTheme

/** The words of the offer, kept in one place for the tests and the screenshots. */
internal object MachineOfferCopy {
    fun title(option: DeviceOption): String = "${option.target.label} is online"
    const val ACTION = "Run here"
    const val DISMISS = "Dismiss"
    fun description(option: DeviceOption): String = "${title(option)}. Run the next chat on it."
}

internal object MachineOfferTags {
    const val ROW = "machine-offer"
    const val ACTION = "machine-offer-run"
    const val DISMISS = "machine-offer-dismiss"
}

/**
 * Under the New Chat composer while it is on Cloud: one of the user's own computers that is reporting to Cursor and
 * free ([offer]; see `KnownDevices.offeredMachine`), with the one tap that moves the next chat onto it and an X that
 * puts the offer away. It opens and closes with the page rather than popping: the row grows from nothing, and keeps
 * drawing the machine it last named while it shrinks, so the words do not vanish before the room they stood in.
 */
@Composable
internal fun MachineOffer(offer: DeviceOption?, onRun: (DeviceOption) -> Unit, onDismiss: (DeviceOption) -> Unit, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(offer) }
    if (offer != null) shown = offer
    AnimatedVisibility(
        visible = offer != null,
        modifier = modifier,
        enter = expandVertically(spring(dampingRatio = 0.86f, stiffness = 420f), expandFrom = Alignment.Top) + fadeIn(tween(180, delayMillis = 60)),
        exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
    ) {
        shown?.let { MachineOfferRow(it, onRun = { onRun(it) }, onDismiss = { onDismiss(it) }) }
    }
}

@Composable
private fun MachineOfferRow(option: DeviceOption, onRun: () -> Unit, onDismiss: () -> Unit) {
    val colors = CursorTheme.colors
    val type = CursorTheme.typography
    val shape = CursorTheme.shapes.base
    Row(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.strokeSubtle, shape)
            .pressable(onRun, shape)
            .heightIn(min = 44.dp)
            .padding(start = 12.dp, end = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = MachineOfferCopy.description(option) }
            .testTag(MachineOfferTags.ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PresenceDot()
        Icon(CursorIcons.Desktop, null, tint = colors.iconSecondary, modifier = Modifier.size(15.dp))
        // The name and the action share what the dot, the icon and the dismiss button leave: the name gives way first,
        // and the action stays whole beside it.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                MachineOfferCopy.title(option),
                style = type.base,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(MachineOfferCopy.ACTION, style = type.base, fontWeight = FontWeight.Medium, color = colors.textPrimary, maxLines = 1, modifier = Modifier.testTag(MachineOfferTags.ACTION))
        }
        FlatIconButton(CursorIcons.Close, MachineOfferCopy.DISMISS, onClick = onDismiss, modifier = Modifier.testTag(MachineOfferTags.DISMISS))
    }
}

/** The green dot of a machine that is reporting, with a slow breath so "online" reads as live rather than as a label. */
@Composable
private fun PresenceDot() {
    val green = CursorTheme.colors.green
    val breath by rememberInfiniteTransition(label = "presence").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "presence-breath",
    )
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).graphicsLayer { alpha = 0.10f + 0.22f * breath; scaleX = 0.7f + 0.3f * breath; scaleY = scaleX }.background(green, CircleShape))
        Box(Modifier.size(7.dp).background(green, CircleShape))
    }
}
