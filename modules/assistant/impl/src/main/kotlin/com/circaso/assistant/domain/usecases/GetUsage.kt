package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.models.Usage
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.today
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.time.ZoneOffset

/** A7: today's allowance used and left, and the plan. */
class GetUsage(
    private val entitlements: EntitlementRepository,
    private val quota: QuotaCounters,
    private val config: AssistantConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: UserId): Resource<Usage> {
        val plan = entitlements.planOf(userId)
        val limits = config.limitsOf(plan)
        val day = clock.today()
        val text = quota.textTurns(userId, day).toInt()
        val voice = quota.voiceSeconds(userId, day).toInt()
        val resets = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        return Resource.success(
            Usage(plan, text, (limits.textTurnsPerDay - text).coerceAtLeast(0), voice, (limits.voiceSecondsPerDay - voice).coerceAtLeast(0), resets),
        )
    }
}
