package com.circaso.integrations.api

import com.circaso.shared.Resource
import java.time.Instant

/**
 * Searching the public web, for Asha's answers about public facts: how USDC or M-Pesa work, fees, what moved an
 * exchange rate (decision 2026-10-31), and, before she asserts something time-sensitive or uncertain, a check
 * (decision 2026-11-05). Never for the price of an asset Circaso holds: that is the wallet's own rate. Only the
 * query leaves Circaso, never who asked or anything of theirs. A vendor that is down or refuses is
 * [IntegrationError.Unavailable]; a query it can't take is [IntegrationError.Rejected].
 */
interface WebSearch {
    /** At most [limit] results (the vendor's own cap applies too), best first. */
    suspend fun search(query: String, limit: Int): Resource<List<WebResult>>
}

/**
 * One result: its page [title], [url], a short [snippet] of plain text (no markup) and, when the vendor knows it, when
 * the page was [publishedAt] (so the model can tell news from an old page; decision 2026-11-05).
 */
data class WebResult(
    val title: String,
    val url: String,
    val snippet: String,
    val publishedAt: Instant? = null,
)
