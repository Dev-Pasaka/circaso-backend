package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.TokenCounts
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.ports.Transactor
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.shared.UserId
import java.time.Clock
import java.time.Instant

/** A day's usage as the live counters hold it, for the cost report (`assistant.usage_daily`). */
data class UsageTotals(
    val textTurns: Int,
    val voiceSeconds: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val webSearches: Int = 0,
    /** The day's tokens by model, priced apart in the staff cost report (decision 2026-11-08). */
    val byModel: Map<String, TokenCounts> = emptyMap(),
)

/** Read before the transaction that stores them: a transaction holds database work only. */
suspend fun QuotaCounters.totals(userId: UserId, day: String): UsageTotals {
    val (input, output) = tokens(userId, day)
    return UsageTotals(
        textTurns(userId, day).toInt(),
        voiceSeconds(userId, day).toInt(),
        input,
        output,
        webSearches(userId, day).toInt(),
        modelTokens(userId, day),
    )
}

/**
 * Through the same `GREATEST` upserts as every writer, so totals read earlier never lower ones stored since: the day's
 * row, then one row per model. Callers run it inside their transaction, so both land together.
 */
suspend fun AssistantStore.saveUsage(
    userId: UserId,
    day: String,
    totals: UsageTotals,
    at: Instant,
) {
    saveUsage(
        userId,
        day,
        totals.textTurns,
        totals.voiceSeconds,
        totals.inputTokens,
        totals.outputTokens,
        at,
        totals.webSearches,
    )
    saveModelTokens(userId, day, totals.byModel, at)
}

/**
 * Stores the day's totals when usage changes outside a text turn: a clip transcribed (A3) or spoken (A4), a live
 * conversation ended (A11). A text turn stores them with its messages instead ([TurnRecorder]).
 */
class DailyUsage(
    private val quota: QuotaCounters,
    private val store: AssistantStore,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend fun save(userId: UserId, day: String = clock.today()) {
        val totals = quota.totals(userId, day)
        transactor.run("usage.save") { store.saveUsage(userId, day, totals, clock.instant()) }
    }
}
