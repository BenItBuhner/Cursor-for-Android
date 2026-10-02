package com.cursorforandroid.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.cursorforandroid.domain.ModelAxis
import com.cursorforandroid.domain.ModelOption
import com.cursorforandroid.domain.ModelVariant
import com.cursorforandroid.domain.arrangedForPicker
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
import com.cursorforandroid.ui.components.PickerToggle
import com.cursorforandroid.ui.components.PickerTrailingAction
import com.cursorforandroid.ui.components.PopoverAnchor
import com.cursorforandroid.ui.components.pickerMatches
import androidx.compose.ui.unit.dp

/**
 * A fallback row for a follow-up whose current model the catalog cannot show checked — unknown (started elsewhere)
 * or no longer offered. There is no "Default" model: the catalog is the list.
 */
internal data class NoModelRow(val title: String, val subtitle: String?)

/**
 * The composer's model picker, on Cursor's model dropdown: a search, one row per model from `GET /v1/models` with the
 * selected variant muted after the selected model's name, a pin that keeps a model at the top, and a Refresh row at
 * the foot. A model with parameters opens its options beside the picker as it is selected — switches under
 * "Options" ("Fast", "Thinking"), then a section of choices per other parameter ("Effort", "Context") — and stays
 * open while they are set; a model without any is picked and the picker closes.
 *
 * The order is the one the picker opened with (the selection first, then pins, then the catalog), so a row does not
 * jump out from under the finger, or from beside its open options, when it is picked.
 *
 * List keys are positional plus id: the API's model ids are unique in practice but nothing guarantees it, and a
 * duplicate key aborts the composition.
 */
@Composable
internal fun ModelSheet(
    models: List<ModelOption>,
    selectedModel: ModelOption?,
    selectedVariant: ModelVariant?,
    loading: Boolean,
    unavailable: Boolean,
    /** Fetches the list again, whether or not it is fresh: the picker's Refresh row. */
    onRefresh: () -> Unit,
    onSelect: (ModelOption?, ModelVariant?) -> Unit,
    onDismiss: () -> Unit,
    pinnedIds: List<String> = emptyList(),
    onTogglePin: (String) -> Unit = {},
    noModelRow: NoModelRow? = null,
    anchor: PopoverAnchor? = null,
    presentation: PickerPresentation? = null,
) {
    val openedWith = remember { selectedModel?.id }
    val ordered = remember(models, pinnedIds) { models.arrangedForPicker(pinnedIds, openedWith) }
    CursorPicker(
        onDismiss = onDismiss,
        anchor = anchor,
        title = "Model",
        searchPlaceholder = "Search models",
        width = 300.dp,
        presentation = presentation,
        testTag = MODEL_PICKER_TAG,
        entries = { query ->
            modelEntries(query, ordered, selectedModel, selectedVariant, loading, unavailable, pinnedIds, noModelRow, onSelect, onTogglePin, onRefresh)
        },
    )
}

internal const val MODEL_PICKER_TAG = "model-picker"

private fun modelEntries(
    query: String,
    ordered: List<ModelOption>,
    selectedModel: ModelOption?,
    selectedVariant: ModelVariant?,
    loading: Boolean,
    unavailable: Boolean,
    pinnedIds: List<String>,
    noModelRow: NoModelRow?,
    onSelect: (ModelOption?, ModelVariant?) -> Unit,
    onTogglePin: (String) -> Unit,
    onRefresh: () -> Unit,
): List<PickerEntry> = buildList {
    val typed = query.trim()
    if (noModelRow != null && pickerMatches(typed, noModelRow.title, noModelRow.subtitle)) {
        add(PickerItem("current", noModelRow.title, subtitle = noModelRow.subtitle, selected = selectedModel == null, onPick = { onSelect(null, null) }))
        add(PickerDivider("current-divider"))
    }
    if (ordered.isEmpty()) {
        when {
            loading -> add(PickerNote("loading", "Loading models…"))
            unavailable -> add(PickerNote("unavailable", "Couldn't load the model list. Refresh to try again."))
        }
    }
    val visible = ordered.withIndex().filter { (_, model) -> pickerMatches(typed, model.displayName, model.id) }
    if (ordered.isNotEmpty() && visible.isEmpty()) add(PickerNote("no-match", "No models match \u201C$typed\u201D"))
    visible.forEach { (index, model) ->
        val selected = model.id == selectedModel?.id
        val variant = if (selected) selectedVariant ?: model.defaultVariant else model.defaultVariant
        val axes = model.axes
        val pinned = model.id in pinnedIds
        add(
            PickerItem(
                key = "model-$index-${model.id}",
                label = model.displayName,
                detail = if (selected) variantSummary(axes, variant) else null,
                selected = selected,
                onPick = { onSelect(model, variant) },
                trailingAction = PickerTrailingAction(
                    icon = CursorIcons.Pin,
                    contentDescription = if (pinned) "Unpin ${model.displayName}" else "Pin ${model.displayName}",
                    active = pinned,
                    onClick = { onTogglePin(model.id) },
                ),
                submenu = if (axes.isEmpty()) null else PickerSubmenu(title = model.displayName, width = 232.dp) {
                    optionEntries(model, axes, variant) { picked -> onSelect(model, picked) }
                },
            ),
        )
    }
    add(PickerDivider("refresh-divider"))
    add(PickerAction("refresh", "Refresh models", onClick = onRefresh, icon = CursorIcons.Refresh, busy = loading))
}

/**
 * A model's options: its on/off parameters as switches under "Options", then each other parameter as a section of
 * choices, the one in force checked. [onVariant] gets the variant the change resolves to; which one that is is the
 * model's business ([ModelOption.variantWith]).
 */
internal fun optionEntries(model: ModelOption, axes: List<ModelAxis>, variant: ModelVariant?, onVariant: (ModelVariant) -> Unit): List<PickerEntry> = buildList {
    fun set(axis: ModelAxis, value: String) {
        model.variantWith(variant, axis.id, value)?.let(onVariant)
    }
    val switches = axes.filter { it.isSwitch }
    val choices = axes.filterNot { it.isSwitch }
    if (switches.isNotEmpty()) {
        add(PickerSection("options", "Options"))
        switches.forEach { axis ->
            val on = variant?.param(axis.id).equals(axis.onValue, ignoreCase = true)
            add(PickerToggle("switch-${axis.id}", axis.displayName, on, onToggle = { checked -> set(axis, if (checked) axis.onValue else axis.offValue) }))
        }
    }
    choices.forEachIndexed { i, axis ->
        if (i > 0 || switches.isNotEmpty()) add(PickerDivider("divider-${axis.id}"))
        add(PickerSection("axis-${axis.id}", axis.displayName))
        val current = variant?.param(axis.id)
        axis.values.forEach { value ->
            add(
                PickerItem(
                    key = "value-${axis.id}-${value.value}",
                    label = value.displayName,
                    selected = value.value == current,
                    dismiss = PickerDismiss.None,
                    onPick = { set(axis, value.value) },
                ),
            )
        }
    }
}

/**
 * The variant in force, as Cursor's picker writes it after the name: each choice's value and each switch that is on,
 * in the parameters' order ("High Fast", "1M Max"); null when it leaves nothing to say.
 */
internal fun variantSummary(axes: List<ModelAxis>, variant: ModelVariant?): String? {
    if (variant == null || axes.isEmpty()) return null
    val words = axes.mapNotNull { axis ->
        val value = variant.param(axis.id) ?: return@mapNotNull null
        if (axis.isSwitch) {
            axis.displayName.takeIf { value.equals(axis.onValue, ignoreCase = true) }
        } else {
            axis.values.firstOrNull { it.value == value }?.displayName
        }
    }
    return words.joinToString(" ").takeIf { it.isNotBlank() }
}
