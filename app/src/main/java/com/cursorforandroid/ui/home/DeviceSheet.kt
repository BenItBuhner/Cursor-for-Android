package com.cursorforandroid.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cursorforandroid.domain.DeviceOption
import com.cursorforandroid.domain.DeviceSection
import com.cursorforandroid.domain.DeviceTarget
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.KnownDevices
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorPicker
import com.cursorforandroid.ui.components.PickerAction
import com.cursorforandroid.ui.components.PickerDismiss
import com.cursorforandroid.ui.components.PickerDivider
import com.cursorforandroid.ui.components.PickerEntry
import com.cursorforandroid.ui.components.PickerItem
import com.cursorforandroid.ui.components.PickerNote
import com.cursorforandroid.ui.components.PickerPresentation
import com.cursorforandroid.ui.components.PickerSection
import com.cursorforandroid.ui.components.PickerSubmenu
import com.cursorforandroid.ui.components.PickerWidths
import com.cursorforandroid.ui.components.PopoverAnchor
import com.cursorforandroid.ui.components.pickerMatches

/**
 * The composer's device picker, on Cursor's Cloud / Remote dropdown: Cloud (always, and the default), and Remote,
 * which opens the machines and team pools — My machines that are online or that past chats ran on, then the pools,
 * an offline machine dimmed — with a search of their own. There is no "this device" row: an Android client cannot
 * host the agent. A name typed into that search that matches none of the rows is offered as a machine and as a pool,
 * the way the branch picker offers an unseen branch.
 */
@Composable
internal fun DeviceSheet(
    devices: List<DeviceOption>,
    selected: DeviceTarget,
    loading: Boolean,
    onSelect: (DeviceTarget) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    anchor: PopoverAnchor? = null,
    presentation: PickerPresentation? = null,
) {
    CursorPicker(
        onDismiss = onDismiss,
        anchor = anchor,
        title = "Device",
        width = PickerWidths.Narrow,
        presentation = presentation,
        testTag = DEVICE_PICKER_TAG,
        entries = { deviceEntries(devices, selected, loading, onSelect, onRefresh) },
    )
}

internal const val DEVICE_PICKER_TAG = "device-picker"

/** The Remote row's label and its submenu's title. */
internal const val REMOTE = "Remote"

private fun deviceEntries(
    devices: List<DeviceOption>,
    selected: DeviceTarget,
    loading: Boolean,
    onSelect: (DeviceTarget) -> Unit,
    onRefresh: () -> Unit,
): List<PickerEntry> = buildList {
    val cloud = devices.firstOrNull { it.section == DeviceSection.Cloud } ?: KnownDevices.cloud
    add(PickerItem("cloud", cloud.target.label, detail = cloud.subtitle, icon = CursorIcons.Cloud, selected = selected.isCloud, onPick = { onSelect(DeviceTarget.Cloud) }))
    add(
        PickerItem(
            key = "remote",
            label = REMOTE,
            detail = selected.takeUnless { it.isCloud }?.label,
            icon = if (selected.isCloud) CursorIcons.Desktop else deviceIcon(selected),
            selected = !selected.isCloud,
            submenu = PickerSubmenu(REMOTE, searchPlaceholder = "Search remote machines\u2026", width = 280.dp) { query ->
                remoteEntries(query, devices, selected, onSelect)
            },
        ),
    )
    add(PickerDivider("refresh-divider"))
    add(PickerAction("refresh", "Refresh devices", onClick = onRefresh, icon = CursorIcons.Refresh, busy = loading))
    add(PickerNote("note", "This phone can't run an agent itself. Cloud is Cursor's hosted VM; a machine or team pool is one you've left online."))
}

private fun remoteEntries(query: String, devices: List<DeviceOption>, selected: DeviceTarget, onSelect: (DeviceTarget) -> Unit): List<PickerEntry> = buildList {
    val typed = query.trim()
    fun matches(option: DeviceOption) = pickerMatches(typed, option.target.label, option.subtitle)
    val remote = devices.filter { it.section != DeviceSection.Cloud }
    val visible = remote.filter(::matches)
    val selectedKey = DeviceOption.keyOf(selected)
    val listedKeys = devices.map { it.key }.toSet()
    val currentUnlisted = !selected.isCloud && selectedKey !in listedKeys && pickerMatches(typed, selected.label)
    val typedAsNew = typed.isNotEmpty() &&
        devices.none { it.target.apiName.equals(typed, ignoreCase = true) } &&
        !selected.apiName.equals(typed, ignoreCase = true)
    val machines = visible.filter { it.section == DeviceSection.Machines }
    val pools = visible.filter { it.section == DeviceSection.Pools }
    fun row(option: DeviceOption, icon: ImageVector) = PickerItem(
        key = "device:${option.key}",
        label = option.target.label,
        subtitle = option.subtitle,
        icon = if (option.online) icon else CursorIcons.Plug,
        dimmed = !option.online,
        selected = option.key == selectedKey,
        onPick = { onSelect(option.target) },
    )
    if (currentUnlisted) add(PickerItem("current", selected.label, icon = deviceIcon(selected), selected = true, onPick = { onSelect(selected) }))
    if (machines.isNotEmpty()) {
        add(PickerSection("machines", "My machines"))
        machines.forEach { add(row(it, CursorIcons.Desktop)) }
    }
    if (pools.isNotEmpty()) {
        if (machines.isNotEmpty()) add(PickerDivider("pools-divider"))
        add(PickerSection("pools", "Team pools"))
        pools.forEach { add(row(it, CursorIcons.Layers)) }
    }
    if (typedAsNew) {
        if (isNotEmpty()) add(PickerDivider("typed-divider"))
        add(PickerAction("typed-machine", "Use \u201C$typed\u201D as a machine", onClick = { onSelect(DeviceTarget.machine(typed)) }, dismiss = PickerDismiss.All))
        add(PickerAction("typed-pool", "Use \u201C$typed\u201D as a team pool", onClick = { onSelect(DeviceTarget.pool(typed)) }, dismiss = PickerDismiss.All))
    }
    when {
        typed.isNotEmpty() && visible.isEmpty() && !currentUnlisted && !typedAsNew -> add(PickerNote("no-match", "No devices match \"$typed\""))
        typed.isEmpty() && remote.isEmpty() && !currentUnlisted ->
            add(PickerNote("empty", "No machines or team pools yet. Type a name to use one that hasn't connected."))
    }
}

internal fun deviceIcon(target: DeviceTarget): ImageVector = when (target.type) {
    EnvType.MACHINE -> CursorIcons.Desktop
    EnvType.POOL -> CursorIcons.Layers
    else -> CursorIcons.Cloud
}
