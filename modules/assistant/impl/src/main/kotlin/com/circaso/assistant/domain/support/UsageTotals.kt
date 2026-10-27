package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.shared.UserId
import java.time.Instant

/** A day's usage as the live counters hold it, for the cost report (`assistant.usage_daily`). */
data class UsageTotals(
    val textTurns: Int,
    val voiceSeconds: Int,
    val inputTokens: Long,
    val outputTokens: Long,
)

/** Read before the transaction that stores them: a transaction holds database work only. */
suspend fun QuotaCounters.totals(userId: UserId, day: String): UsageTotals {
    val (input, output) = tokens(userId, day)
    return UsageTotals(textTurns(userId, day).toInt(), voiceSeconds(userId, day).toInt(), input, output)
}

/** Through the same `GREATEST` upsert as every writer, so totals read earlier never lower ones stored since. */
suspend fun AssistantStore.saveUsage(
    userId: UserId,
    day: String,
    totals: UsageTotals,
    at: Instant,
) = saveUsage(userId, day, totals.textTurns, totals.voiceSeconds, totals.inputTokens, totals.outputTokens, at)
