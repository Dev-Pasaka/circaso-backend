package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.Usage
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.shared.UserId
import java.time.Clock
import java.time.ZoneOffset

/**
 * Today's allowance used and left, and the plan: A7's answer, what Asha's own usage tool reads, and what a web search
 * checks against. The period is the UTC day: it starts at midnight and resets at the next.
 */
class UsageReader(
    private val entitlements: EntitlementRepository,
    private val quota: QuotaCounters,
    private val config: AssistantConfig,
    private val clock: Clock,
) {
    suspend fun today(userId: UserId): Usage {
        val plan = entitlements.planOf(userId)
        val limits = config.limitsOf(plan)
        val day = clock.today()
        val text = quota.textTurns(userId, day).toInt()
        val voice = quota.voiceSeconds(userId, day).toInt()
        val searches = quota.webSearches(userId, day).toInt()
        val start =
            clock
                .instant()
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
        return Usage(
            plan = plan,
            textTurnsUsed = text,
            textTurnsLeft = (limits.textTurnsPerDay - text).coerceAtLeast(0),
            voiceSecondsUsed = voice,
            voiceSecondsLeft = (limits.voiceSecondsPerDay - voice).coerceAtLeast(0),
            resetsAt = start.plusDays(1).toInstant(),
            webSearchesUsed = searches,
            webSearchesLeft = (limits.webSearchesPerDay - searches).coerceAtLeast(0),
            periodStart = start.toInstant(),
            limits = limits,
            isUnlimited = config.isUnlimited,
        )
    }
}
