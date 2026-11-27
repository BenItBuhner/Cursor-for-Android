package com.cursorforandroid.domain

/**
 * The documented Cloud Agents `model` object: an id from `GET /v1/models` plus that model's `params`.
 *
 * Composer 2 is retired — `composer-2` / `composer-2-fast` are rerouted to `composer-2.5` (fast mode is
 * `params:[{id:"fast",value:"true"}]`). Cursor Router is `auto-smart` with a required `optimize_for` of
 * `cost`, `balanced`, or `intelligence`; omitting it or sending the desktop's `default` is not a supported
 * Router contract. `{id:"auto"}` is the server-selected Auto fallback and is left alone.
 */
object ModelSelection {

    const val COMPOSER_2_5 = "composer-2.5"
    const val AUTO_SMART = "auto-smart"
    const val AUTO = "auto"
    const val OPTIMIZE_FOR = "optimize_for"
    const val OPTIMIZE_COST = "cost"
    const val OPTIMIZE_BALANCED = "balanced"
    const val OPTIMIZE_INTELLIGENCE = "intelligence"

    val OPTIMIZE_FOR_VALUES = setOf(OPTIMIZE_COST, OPTIMIZE_BALANCED, OPTIMIZE_INTELLIGENCE)

    /**
     * The id and parameters that go out on Create An Agent / Create A Run. Null when [id] is blank so the
     * request omits `model` and Cursor uses the account default.
     */
    fun wire(id: String?, params: List<ModelParam> = emptyList()): Pair<String, List<ModelParam>>? {
        val raw = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            raw.equals("composer-2-fast", ignoreCase = true) -> COMPOSER_2_5 to params.with(FAST, "true")
            raw.equals("composer-2", ignoreCase = true) -> COMPOSER_2_5 to params
            raw.equals(AccountModel.AUTO_ID, ignoreCase = true) || raw.equals(AUTO_SMART, ignoreCase = true) ->
                AUTO_SMART to params.withRequiredOptimizeFor()
            else -> raw to params
        }
    }

    private const val FAST = "fast"

    private fun List<ModelParam>.with(id: String, value: String): List<ModelParam> =
        filterNot { it.id.equals(id, ignoreCase = true) } + ModelParam(id, value)

    private fun List<ModelParam>.withRequiredOptimizeFor(): List<ModelParam> {
        val current = firstOrNull { it.id.equals(OPTIMIZE_FOR, ignoreCase = true) }?.value?.lowercase()
        return if (current in OPTIMIZE_FOR_VALUES) this else with(OPTIMIZE_FOR, OPTIMIZE_BALANCED)
    }
}
