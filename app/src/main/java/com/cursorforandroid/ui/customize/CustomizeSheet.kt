package com.cursorforandroid.ui.customize

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cursorforandroid.domain.EnvironmentFilter
import com.cursorforandroid.domain.FilterKind
import com.cursorforandroid.domain.GitFilter
import com.cursorforandroid.domain.GroupBy
import com.cursorforandroid.domain.ListPreferences
import com.cursorforandroid.domain.SortOrder
import com.cursorforandroid.domain.SourceFilter
import com.cursorforandroid.domain.StatusFilter
import com.cursorforandroid.ui.agents.AgentsViewModel
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
import com.cursorforandroid.ui.components.PickerWidths
import com.cursorforandroid.ui.components.PopoverAnchor
import com.cursorforandroid.ui.components.pickerMatches

/**
 * The "Chats" menu (the filter icon in the sidebar's account row), on Cursor's chat-list menu: how the list is
 * grouped, checked in place; Sort by, and the Repo / Status / Git / Source / Environment filters, each a submenu
 * with what it is set to beside its name; the metadata switches; and Read all and Reset at the foot.
 */
@Composable
fun CustomizeSheet(
    viewModel: AgentsViewModel,
    onDismiss: () -> Unit,
    anchor: PopoverAnchor? = null,
    presentation: PickerPresentation? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CursorPicker(
        onDismiss = onDismiss,
        anchor = anchor,
        title = "Chats",
        width = PickerWidths.Default,
        presentation = presentation,
        testTag = CHATS_MENU_TAG,
        entries = { chatsEntries(state.prefs, state.repoSlugs, state.unreadCount, viewModel) },
    )
}

const val CHATS_MENU_TAG = "chats-menu"

private fun chatsEntries(prefs: ListPreferences, repoSlugs: List<String>, unreadCount: Int, viewModel: AgentsViewModel): List<PickerEntry> = buildList {
    add(PickerSection("group", "Group by"))
    GroupBy.entries.forEach { group ->
        // "Repository" rather than "Repo", as Cursor's menu has it: the Repo filter under it is a row of its own.
        val label = if (group == GroupBy.Repo) "Repository" else group.label
        add(PickerItem("group-${group.name}", label, selected = prefs.groupBy == group, dismiss = PickerDismiss.None, onPick = { viewModel.setGroupBy(group) }))
    }
    add(
        PickerItem(
            key = "sort",
            label = "Sort by",
            detail = prefs.sortOrder.label,
            submenu = PickerSubmenu("Sort by", width = PickerWidths.Narrow) {
                SortOrder.entries.map { order ->
                    PickerItem("sort-${order.name}", order.label, selected = prefs.sortOrder == order, dismiss = PickerDismiss.Submenu, onPick = { viewModel.setSortOrder(order) })
                }
            },
        ),
    )

    add(PickerDivider("filter-divider"))
    add(PickerSection("filter", "Filter"))
    add(filterRow(FilterKind.Repo, CursorIcons.Repo, prefs, searchPlaceholder = "Search repositories\u2026", width = PickerWidths.Wide) { query -> repoEntries(query, repoSlugs, prefs.repos, viewModel) })
    add(filterRow(FilterKind.Status, CursorIcons.Sparkle, prefs) { checklist(StatusFilter.entries, { it.label }, { it in prefs.statuses }, viewModel::toggleStatus) })
    add(filterRow(FilterKind.Git, CursorIcons.GitBranch, prefs) { checklist(GitFilter.entries, { it.label }, { it in prefs.git }, viewModel::toggleGit) })
    add(
        filterRow(FilterKind.Source, CursorIcons.Globe, prefs) {
            checklist(SourceFilter.entries, { it.label }, { it in prefs.sources }, viewModel::toggleSource) +
                PickerNote("source-note", "Where each chat was started, as your Cursor account records it. Chats started from this app count as \"This device\"; the account sees them as API.")
        },
    )
    add(filterRow(FilterKind.Environment, CursorIcons.Cloud, prefs) { checklist(EnvironmentFilter.entries, { it.label }, { it in prefs.environments }, viewModel::toggleEnvironment) })

    add(PickerDivider("show-divider"))
    add(PickerSection("show", "Show"))
    add(PickerToggle("workspace", "Workspace", prefs.showWorkspace, viewModel::setShowWorkspace, icon = CursorIcons.Folder))
    add(PickerToggle("branch-status", "Branch status", prefs.showBranchStatus, viewModel::setShowBranchStatus, icon = CursorIcons.GitPullRequest))
    add(PickerToggle("runtime", "Runtime", prefs.showRuntime, viewModel::setShowRuntime, icon = CursorIcons.Clock))

    add(PickerDivider("actions-divider"))
    val readAll = readAllAction(unreadCount, viewModel::markAllRead)
    add(
        PickerAction(
            key = "read-all",
            label = readAll.label,
            detail = readAll.subtitle,
            icon = readAll.icon ?: CursorIcons.Check,
            enabled = readAll.enabled,
            onClick = readAll.onClick,
            testTag = READ_ALL_TAG,
        ),
    )
    if (!prefs.isDefault) add(PickerAction("reset", "Reset", onClick = viewModel::resetPrefs, icon = CursorIcons.Reset, testTag = RESET_TAG))
}

const val READ_ALL_TAG = "chats-menu-read-all"
const val RESET_TAG = "chats-menu-reset"

private fun filterRow(
    kind: FilterKind,
    icon: ImageVector,
    prefs: ListPreferences,
    searchPlaceholder: String? = null,
    width: Dp = 220.dp,
    entries: (String) -> List<PickerEntry>,
) = PickerItem(
    key = "filter-${kind.name}",
    label = kind.label,
    detail = prefs.summaryFor(kind),
    icon = icon,
    submenu = PickerSubmenu(kind.label, searchPlaceholder = searchPlaceholder, width = width, entries = entries),
    testTag = filterTag(kind),
)

fun filterTag(kind: FilterKind): String = "chats-menu-filter-${kind.name}"

private fun <T> checklist(values: List<T>, label: (T) -> String, checked: (T) -> Boolean, onToggle: (T) -> Unit): List<PickerEntry> =
    values.mapIndexed { index, value ->
        PickerItem("check-$index", label(value), selected = checked(value), dismiss = PickerDismiss.None, onPick = { onToggle(value) })
    }

/** An account can have hundreds of repositories: the submenu's list composes only what is on screen, and is searched. */
private fun repoEntries(query: String, slugs: List<String>, selected: Set<String>?, viewModel: AgentsViewModel): List<PickerEntry> = buildList {
    if (query.isBlank()) {
        add(PickerItem("all-repos", "All repositories", selected = selected == null, dismiss = PickerDismiss.None, onPick = { viewModel.setRepos(null) }))
        add(PickerDivider("all-divider"))
    }
    if (slugs.isEmpty()) add(PickerNote("none", "No repositories yet"))
    val visible = slugs.filter { pickerMatches(query, it) }
    if (slugs.isNotEmpty() && visible.isEmpty()) add(PickerNote("no-match", "No repositories match \"${query.trim()}\""))
    visible.forEach { slug ->
        add(
            PickerItem(
                key = slug,
                label = slug.substringAfterLast('/'),
                detail = slug.substringBeforeLast('/', "").takeIf { it.isNotEmpty() },
                icon = CursorIcons.Repo,
                selected = selected == null || slug in selected,
                dismiss = PickerDismiss.None,
                onPick = {
                    val current = selected ?: slugs.toSet()
                    val next = if (slug in current) current - slug else current + slug
                    viewModel.setRepos(if (next.size == slugs.size) null else next)
                },
            ),
        )
    }
}
