package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.Usage
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.shared.UserId
import java.time.Clock
import java.time.ZoneOffset

/** Today's allowance used and left, and the plan: A7's answer, and what Asha's own usage tool reads. */
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
        val resets =
            clock
                .instant()
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
        return Usage(
            plan = plan,
            textTurnsUsed = text,
            textTurnsLeft = (limits.textTurnsPerDay - text).coerceAtLeast(0),
            voiceSecondsUsed = voice,
            voiceSecondsLeft = (limits.voiceSecondsPerDay - voice).coerceAtLeast(0),
            resetsAt = resets,
        )
    }
}
