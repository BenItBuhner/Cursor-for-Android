package com.cursorforandroid.domain

/** Token counts as `GET /v1/agents/{id}/usage` reports them, for one run or the whole agent. */
data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val totalTokens: Long = 0,
    /**
     * A subset of [outputTokens], omitted from [totalTokens] the way `@cursor/sdk`'s `TokenUsage` does.
     * Null when the backend does not report it.
     */
    val reasoningTokens: Long? = null,
) {
    val isEmpty: Boolean get() = totalTokens == 0L && inputTokens == 0L && outputTokens == 0L && cacheReadTokens == 0L && cacheWriteTokens == 0L

    /** The total as reported, else the parts added up — never including [reasoningTokens]. */
    val total: Long get() = if (totalTokens > 0) totalTokens else inputTokens + outputTokens + cacheWriteTokens + cacheReadTokens

    companion object {
        /** "12.4k", "1.2M", "980" — the way the desktop abbreviates token counts. */
        fun format(tokens: Long): String = when {
            tokens >= 1_000_000 -> trimmed(tokens / 1_000_000.0) + "M"
            tokens >= 1_000 -> trimmed(tokens / 1_000.0) + "k"
            else -> tokens.toString()
        }

        private fun trimmed(value: Double): String = String.format(java.util.Locale.US, "%.1f", value).removeSuffix(".0")
    }
}

/** Dollar cost of billed usage, in float cents (`rawCostCents` / `chargedCents`). */
data class UsageCost(
    val rawCostCents: Double = 0.0,
    val chargedCents: Double = 0.0,
) {
    val isEmpty: Boolean get() = rawCostCents == 0.0 && chargedCents == 0.0

    companion object {
        fun formatCents(cents: Double): String = String.format(java.util.Locale.US, "$%.2f", cents / 100.0)
    }
}

/** One run's usage. */
data class RunUsage(
    val runId: String,
    val usage: TokenUsage,
    val cost: UsageCost? = null,
    val usageUuid: String? = null,
)

/** Everything the agent has spent, and how it splits by run (newest first when the API says). */
data class AgentUsage(
    val total: TokenUsage,
    val runs: List<RunUsage>,
    val cost: UsageCost? = null,
) {
    val isEmpty: Boolean get() = total.isEmpty && runs.all { it.usage.isEmpty } && cost?.isEmpty != false
}
