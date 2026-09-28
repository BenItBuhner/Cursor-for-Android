package com.cursorforandroid.util

import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.RecomposeScopeObserver
import androidx.compose.runtime.tooling.observe
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Counts every recompose scope that runs, Compose's own included (a lazy list's item wrappers, `LazyLayout`), by the
 * class of the lambda it restarts, which [RecomposeCounter] cannot see. Attach with `composition.observe(this)` from
 * the root, then [observeAll] once composed so scopes re-run by a changed parameter, not only invalidated ones, count
 * too; subcompositions (lazy items, `BoxWithConstraints`) are walked through their remembered context, by reflection.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
class RecomposeScopes : CompositionObserver, RecomposeScopeObserver {
    var scopes = 0
        private set
    private val byName = HashMap<String, Int>()
    private val observed = Collections.newSetFromMap(IdentityHashMap<RecomposeScope, Boolean>())

    override fun onBeginComposition(composition: Composition, invalidationMap: Map<RecomposeScope, Set<Any>?>) {
        for (scope in invalidationMap.keys) if (observed.add(scope)) scope.observe(this)
    }

    override fun onEndComposition(composition: Composition) = Unit

    override fun onBeginScopeComposition(scope: RecomposeScope) {
        scopes++
        byName.merge(nameOf(scope), 1, Int::plus)
    }

    override fun onEndScopeComposition(scope: RecomposeScope) = Unit

    override fun onScopeDisposed(scope: RecomposeScope) {
        observed.remove(scope)
    }

    fun observeAll(data: CompositionData) {
        fun walk(group: CompositionGroup) {
            for (d in group.data) {
                if (d is RecomposeScope && observed.add(d)) d.observe(this)
                subcompositions(d).forEach { sub -> sub.compositionGroups.forEach(::walk) }
            }
            for (child in group.compositionGroups) walk(child)
        }
        for (group in data.compositionGroups) walk(group)
    }

    fun reset() {
        scopes = 0
        byName.clear()
    }

    /** Runs of the scopes whose name contains [part]. */
    fun count(part: String): Int = byName.entries.filter { part in it.key }.sumOf { it.value }

    fun top(n: Int = 20): String = byName.entries.sortedByDescending { it.value }.take(n).joinToString("\n") { "  ${it.value}x ${it.key}" }

    private fun subcompositions(d: Any?): List<CompositionData> = runCatching {
        var x: Any = d ?: return emptyList()
        if (x.javaClass.name.endsWith("RememberObserverHolder")) x = field(x, "wrapped") ?: return emptyList()
        if (!x.javaClass.name.endsWith("CompositionContextHolder")) return emptyList()
        val ref = field(x, "ref") ?: return emptyList()
        (field(ref, "composers") as Collection<*>).mapNotNull { (it as? Composer)?.compositionData }
    }.getOrDefault(emptyList())

    /** A composable's own name where the lambda has one; a hidden lambda class by what it captures. */
    private fun nameOf(scope: RecomposeScope): String = runCatching {
        var block: Any? = field(scope, "block")
        if (block != null && block.javaClass.name.startsWith("androidx.compose.runtime.internal.ComposableLambdaImpl")) {
            val outer = if (block.javaClass.name.endsWith("\$invoke\$1")) {
                block.javaClass.declaredFields.firstOrNull { it.type.name.contains("ComposableLambdaImpl") }?.apply { isAccessible = true }?.get(block)
            } else {
                block
            }
            block = outer?.let { field(it, "_block") } ?: block
        }
        val c = block?.javaClass ?: return@runCatching "?"
        if (c.name.contains("\$\$Lambda")) c.name.substringBefore("\$\$Lambda") + "{" + c.declaredFields.joinToString(",") { it.type.simpleName } + "}" else c.name
    }.getOrDefault("?")

    private fun field(of: Any, name: String): Any? = of.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(of)
}
