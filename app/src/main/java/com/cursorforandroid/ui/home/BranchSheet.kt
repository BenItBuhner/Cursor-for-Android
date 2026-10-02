package com.cursorforandroid.ui.home

import androidx.compose.runtime.Composable
import com.cursorforandroid.domain.BranchOption
import com.cursorforandroid.domain.KnownBranches
import com.cursorforandroid.domain.Repository
import com.cursorforandroid.ui.components.CursorIcons
import com.cursorforandroid.ui.components.CursorPicker
import com.cursorforandroid.ui.components.PickerAction
import com.cursorforandroid.ui.components.PickerDismiss
import com.cursorforandroid.ui.components.PickerDivider
import com.cursorforandroid.ui.components.PickerEntry
import com.cursorforandroid.ui.components.PickerItem
import com.cursorforandroid.ui.components.PickerNote
import com.cursorforandroid.ui.components.PickerPresentation
import com.cursorforandroid.ui.components.PickerWidths
import com.cursorforandroid.ui.components.PopoverAnchor
import com.cursorforandroid.ui.components.pickerMatches

/**
 * The composer's branch picker, on Cursor's branch dropdown: the repository's default branch — or, for a machine's
 * checkout ([fromCheckout]), the branch that checkout is on — then every branch agents in the repository started from
 * or pushed and, with an account session, the repository's own branches ([branches], see `KnownBranches`), filtered
 * by the search. A name typed into it that matches none of them is offered as a "+" row of its own: without the
 * account's list, that is the only way to start from a branch no agent has touched yet.
 */
@Composable
internal fun BranchSheet(
    repo: Repository?,
    branches: List<BranchOption>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    fromCheckout: Boolean = false,
    listedByAccount: Boolean = false,
    anchor: PopoverAnchor? = null,
    presentation: PickerPresentation? = null,
) {
    CursorPicker(
        onDismiss = onDismiss,
        anchor = anchor,
        title = "Branch",
        searchPlaceholder = "Search branches\u2026",
        width = PickerWidths.Default,
        presentation = presentation,
        testTag = BRANCH_PICKER_TAG,
        entries = { query -> branchEntries(query, repo, branches, selected, fromCheckout, listedByAccount, onSelect) },
    )
}

internal const val BRANCH_PICKER_TAG = "branch-picker"

private fun branchEntries(
    query: String,
    repo: Repository?,
    branches: List<BranchOption>,
    selected: String,
    fromCheckout: Boolean,
    listedByAccount: Boolean,
    onSelect: (String) -> Unit,
): List<PickerEntry> = buildList {
    val typed = query.trim()
    val current = selected.trim()
    val visible = branches.filter { pickerMatches(typed, it.name) }
    // The selection always has a row, even when no agent has used it (typed earlier, or restored from the last launch).
    val currentUnlisted = current.isNotEmpty() && branches.none { it.name == current } && pickerMatches(typed, current)
    val unlisted = typed.isNotEmpty() && typed != current && branches.none { it.name == typed }
    val offerTyped = unlisted && KnownBranches.isPlausibleRef(typed)
    if (typed.isEmpty()) {
        if (fromCheckout) {
            add(PickerItem("default", "Current branch", subtitle = "The machine's checkout, on the branch it is on", icon = CursorIcons.GitBranch, selected = current.isEmpty(), onPick = { onSelect("") }))
        } else {
            add(PickerItem("default", "Default branch", subtitle = "The repository's default branch", icon = CursorIcons.GitBranch, selected = current.isEmpty(), onPick = { onSelect("") }))
        }
        if (currentUnlisted || visible.isNotEmpty()) add(PickerDivider("default-divider"))
    }
    if (currentUnlisted) add(PickerItem("current", current, icon = CursorIcons.GitBranch, selected = true, onPick = { onSelect(current) }))
    visible.forEach { branch ->
        add(
            PickerItem(
                key = "branch:${branch.name}",
                label = branch.name,
                detail = branch.description,
                icon = CursorIcons.GitBranch,
                selected = branch.name == current,
                onPick = { onSelect(branch.name) },
            ),
        )
    }
    if (offerTyped) {
        if (isNotEmpty()) add(PickerDivider("typed-divider"))
        add(PickerAction("typed", "Use \u201C$typed\u201D", onClick = { onSelect(typed) }, dismiss = PickerDismiss.All))
    }
    if (typed.isNotEmpty() && visible.isEmpty() && !currentUnlisted && !offerTyped) {
        add(PickerNote("invalid", "\u201C$typed\u201D can't be a branch name"))
    }
    if (repo != null && !listedByAccount) {
        add(PickerNote("note", "Cursor's API can't list a repository's branches, so these are the ones your agents started from or pushed in ${repo.shortName}. Search for any other branch by name to use it."))
    }
}
