package com.cursorforandroid.promo

import com.cursorforandroid.AppGraph
import com.cursorforandroid.data.api.ComposerSnapshot
import com.cursorforandroid.data.demo.DemoData
import com.cursorforandroid.data.repo.AgentRepository
import com.cursorforandroid.data.repo.PullRequestLookup
import com.cursorforandroid.data.repo.PullRequestRepository
import com.cursorforandroid.data.repo.PullRequestSource
import com.cursorforandroid.domain.AgentSource
import com.cursorforandroid.domain.PullRequestState

/**
 * The account the capture signs in to: a week of someone's agents on one product, most of them landed, one still
 * working. The demo's own seeds show off every corner of the app; these are what a user's list looks like.
 */
internal object PromoSeeds {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    private fun pr(n: Int) = "$CESIUM_REPO/pull/$n"

    val seeds: List<DemoData.Seed> = listOf(
        DemoData.Seed(
            id = "bc-promo-0001", name = "Speed up search indexing", repo = CESIUM_REPO, ageMillis = 6 * MIN,
            runStatus = "RUNNING", lifecycle = "ACTIVE",
            prompt = "Indexing a large workspace takes 40 seconds. Profile it and make it fast.",
        ),
        DemoData.Seed(
            id = "bc-promo-0002", name = "Fix flaky checkout test", repo = CESIUM_REPO, ageMillis = 18 * MIN,
            runStatus = "FINISHED", branch = "cursor/flaky-checkout-81c0", prUrl = pr(219), prState = PullRequestState.Open, durationMs = 9 * MIN,
            source = AgentSource.SLACK,
            prompt = "checkout.test.ts fails about one run in ten on CI. Find out why and fix it.",
            replies = listOf("The test raced the cart's debounced save. It now waits for the save to settle; 200 runs in a row pass."),
        ),
        DemoData.Seed(
            id = "bc-promo-0003", name = "Stripe webhook retries", repo = CESIUM_REPO, ageMillis = 41 * MIN,
            runStatus = "FINISHED", branch = "cursor/webhook-retries-3a7d", prUrl = pr(217), prState = PullRequestState.Merged, durationMs = 14 * MIN,
            prompt = "Make the Stripe webhook handler safe to retry.",
            replies = listOf("Events are deduplicated on their id before anything is written, so a retried delivery is a no-op."),
        ),
        DemoData.Seed(
            id = "bc-promo-0004", name = "Upgrade to React 19", repo = CESIUM_REPO, ageMillis = HOUR + 5 * MIN,
            runStatus = "FINISHED", branch = "cursor/react-19-5e21", prUrl = pr(216), prState = PullRequestState.Draft, durationMs = 26 * MIN,
            prompt = "Upgrade the app to React 19 and fix whatever breaks.",
            replies = listOf("Upgraded, with the three deprecated APIs replaced. Draft until the date picker ships a compatible release."),
        ),
        DemoData.Seed(
            id = "bc-promo-0005", name = "Add CSV export to reports", repo = CESIUM_REPO, ageMillis = 2 * HOUR,
            runStatus = "FINISHED", branch = "cursor/csv-export-2d9f", durationMs = 11 * MIN,
            source = AgentSource.GITHUB,
            prompt = "Add a CSV export button to the reports page.",
            replies = listOf("Reports export to CSV from the toolbar, streamed so large reports don't block the page. Pushed to a branch."),
        ),
        DemoData.Seed(
            id = "bc-promo-0006", name = "Rate-limit the public API", repo = CESIUM_REPO, ageMillis = 3 * HOUR + 20 * MIN,
            runStatus = "FINISHED", branch = "cursor/rate-limit-9b44", prUrl = pr(213), prState = PullRequestState.Open, durationMs = 18 * MIN,
            prompt = "Rate-limit the public API per key: 100 requests a minute, with a Retry-After header.",
            replies = listOf("Every public route is limited per key with a sliding window, and a limited request gets a 429 with Retry-After."),
        ),
        DemoData.Seed(
            id = "bc-promo-0007", name = "Onboarding copy pass", repo = CESIUM_REPO, ageMillis = 5 * HOUR,
            runStatus = "FINISHED", branch = "cursor/onboarding-copy-c61e", prUrl = pr(211), prState = PullRequestState.Merged, durationMs = 7 * MIN,
            source = AgentSource.SLACK,
            prompt = "Tighten the onboarding copy. Shorter sentences, no jargon.",
            replies = listOf("Every onboarding screen is down to one line of copy, and the empty states say what to do next."),
        ),
        DemoData.Seed(
            id = "bc-promo-0008", name = "Weekly dependency bump", repo = CESIUM_REPO, ageMillis = DAY + 2 * HOUR,
            runStatus = "FINISHED", branch = "cursor/deps-2026-39", prUrl = pr(208), prState = PullRequestState.Merged, durationMs = 6 * MIN,
            source = AgentSource.API,
            prompt = "Bump this week's dependencies and make sure the build still passes.",
            replies = listOf("Bumped 14 packages; the build and the tests pass."),
        ),
        DemoData.Seed(
            id = "bc-promo-0009", name = "Refactor billing service", repo = CESIUM_REPO, ageMillis = 2 * DAY + 3 * HOUR,
            runStatus = "FINISHED", branch = "cursor/billing-refactor-47aa", prUrl = pr(205), prState = PullRequestState.Open, durationMs = 38 * MIN,
            prompt = "Split the billing service into invoicing and metering, without changing behavior.",
            replies = listOf("Billing is two services now, invoicing and metering, behind the same interface. Every existing test passes unchanged."),
        ),
    )

    /** Pinned before the demo is entered, which pins its own showcase chats only when nothing is pinned yet. */
    val pinned: List<String> = listOf("bc-promo-0001", "bc-promo-0009")

    private val pullRequestStates: Map<String, PullRequestState> =
        seeds.mapNotNull { seed -> seed.prUrl?.let { url -> seed.prState?.let { url to it } } }.toMap()

    /**
     * What the account's list says about these chats, in place of what it says about the demo's: where each was
     * started, and that none of them is a Project. And where their pull requests stand; one the capture's run opens
     * is open.
     */
    fun install(graph: AppGraph) {
        set(AgentRepository::class.java, graph.agents, "demoSources", seeds.associate { it.id to it.source })
        set(AgentRepository::class.java, graph.agents, "demoComposers", emptyList<ComposerSnapshot>())
        set(PullRequestRepository::class.java, graph.pullRequests, "demo", object : PullRequestSource {
            override suspend fun lookup(url: String): PullRequestLookup = PullRequestLookup.Found(pullRequestStates[url] ?: PullRequestState.Open)
        })
    }

    private fun set(type: Class<*>, target: Any, field: String, value: Any) {
        type.getDeclaredField(field).apply { isAccessible = true }.set(target, value)
    }
}
