package com.cursorforandroid.ui.panel

import com.cursorforandroid.data.repo.AgentStoreRepository
import com.cursorforandroid.data.repo.VmRead
import com.cursorforandroid.domain.AgentStoreRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Project tab's Context reads for one chat's panel ([ContextPanelState]): which stores the chat has, the Project's
 * notes, the folders listed so far and the Recents row, read through [stores] on [scope]. [projectRootId] is the
 * Project the chat belongs to as the panel knows it at the time of the read.
 */
internal class ContextReads(
    private val agentId: String,
    private val stores: AgentStoreRepository,
    private val scope: CoroutineScope,
    private val projectRootId: () -> String?,
) {
    val state = MutableStateFlow(ContextPanelState())

    private var job: Job? = null
    private val folderJobs = HashMap<String, Job>()

    /**
     * Reads which stores the chat has, then — in parallel — the Project's notes, both roots' listings and the
     * Recents row. Idempotent while a read is out; [force] re-reads through the repository's cache.
     */
    fun load(force: Boolean = false) {
        if (job?.isActive == true && !force) return
        val current = state.value.stores
        if (!force && current is RemoteLoad.Loaded) return
        job?.cancel()
        state.update { it.copy(stores = RemoteLoad.Loading) }
        job = scope.launch {
            val read = stores.storesFor(agentId, projectRootId(), force)
            val found = when (read) {
                is VmRead.Loaded -> read.value
                is VmRead.NotAvailable -> {
                    state.update { it.copy(stores = RemoteLoad.Unsupported(read.reason), notes = RemoteLoad.Unsupported(read.reason), recents = RemoteLoad.Unsupported(read.reason)) }
                    return@launch
                }
                is VmRead.Failed -> {
                    state.update { it.copy(stores = RemoteLoad.Failed(read.message, retryable = !read.endpointChanged)) }
                    return@launch
                }
            }
            // The roots open at once, as the web's tree does; deeper folders as they are tapped.
            state.update { c -> c.copy(stores = RemoteLoad.Loaded(found), expandedFolders = c.expandedFolders + found.all.map { ContextPanelState.folderKey(it, "") }) }
            found.all.forEach { store -> listFolder(store, "", force) }
            val project = found.project
            if (project != null) {
                state.update { it.copy(notes = RemoteLoad.Loading) }
                launch { state.update { it.copy(notes = stores.notes(project, force).toLoad()) } }
            } else {
                state.update { it.copy(notes = RemoteLoad.Loaded(null)) }
            }
            state.update { it.copy(recents = RemoteLoad.Loading) }
            launch { state.update { it.copy(recents = stores.recents(agentId, found.all, force = force).toLoad()) } }
        }
    }

    /** Opens or closes a folder of the tree; an opened folder not yet listed is listed. */
    fun toggleFolder(store: AgentStoreRef, path: String) {
        val key = ContextPanelState.folderKey(store, path)
        val expanding = key !in state.value.expandedFolders
        state.update { it.copy(expandedFolders = if (expanding) it.expandedFolders + key else it.expandedFolders - key) }
        if (expanding && state.value.listings[key] !is RemoteLoad.Loaded) listFolder(store, path)
    }

    private fun listFolder(store: AgentStoreRef, path: String, force: Boolean = false) {
        val key = ContextPanelState.folderKey(store, path)
        if (folderJobs[key]?.isActive == true && !force) return
        folderJobs[key]?.cancel()
        state.update { it.copy(listings = it.listings + (key to RemoteLoad.Loading)) }
        folderJobs[key] = scope.launch {
            val load = stores.entries(store, path, force).toLoad()
            state.update { it.copy(listings = it.listings + (key to load)) }
        }
    }
}
