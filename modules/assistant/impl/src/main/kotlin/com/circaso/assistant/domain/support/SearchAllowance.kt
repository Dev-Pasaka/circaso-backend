package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.shared.UserId
import java.time.Clock

/**
 * Whether Asha may run one more web search for a user (decision 2026-11-05): at most
 * [AssistantConfig.maxWebSearchesPerTurn] in a turn and the plan's `webSearchesPerDay` in a UTC day. Each search costs Circaso a vendor call, so it is counted before it runs, in the same daily counters
 * as turns and voice (`usage_daily.web_searches` keeps the day's total for cost reports).
 */
class SearchAllowance(
    private val entitlements: EntitlementRepository,
    private val quota: QuotaCounters,
    private val config: AssistantConfig,
    private val clock: Clock,
) {
    /** Takes one search, or says why not; [earlierThisTurn] is how many this turn already ran. */
    suspend fun take(userId: UserId, earlierThisTurn: Int): SearchVerdict {
        if (earlierThisTurn >= config.maxWebSearchesPerTurn) return SearchVerdict.TURN_LIMIT
        val cap = config.limitsOf(entitlements.planOf(userId)).webSearchesPerDay
        val count = quota.countWebSearch(userId, clock.today(), cap)
        return if (count > cap) SearchVerdict.DAY_LIMIT else SearchVerdict.ALLOWED
    }
}

enum class SearchVerdict { ALLOWED, TURN_LIMIT, DAY_LIMIT }
