package com.circaso.ops.domain.models

import com.circaso.integrations.api.AiBill
import com.circaso.ops.domain.support.AiPricing
import com.circaso.shared.UserId
import java.math.BigDecimal
import java.time.LocalDate

// The AI usage report (decision 2026-11-08): Asha's use of Claude from the assistant's daily totals, priced here with
// the configured table, and, when an Anthropic Admin key is set, Anthropic's own bill for the same days beside it.

enum class UsageBucket { DAY, WEEK, MONTH }

/** Tokens by kind: fresh input, cache reads, cache writes and output. */
data class TokenTotals(
    val input: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val output: Long = 0,
) {
    operator fun plus(other: TokenTotals) =
        TokenTotals(
            input + other.input,
            cacheRead + other.cacheRead,
            cacheWrite + other.cacheWrite,
            output + other.output,
        )

    val all: Long get() = input + cacheRead + cacheWrite + output
}

/** What happened in one period; [costUsd] is the estimate (tokens of priced models, and web searches). */
data class AiUsagePeriod(
    val period: LocalDate,
    val textTurns: Long,
    val voiceSeconds: Long,
    val webSearches: Long,
    val tokens: TokenTotals,
    val costUsd: BigDecimal,
)

/** One model over the range; [costUsd] null when the price table has no price for it. */
data class AiModelUsage(val model: String, val tokens: TokenTotals, val costUsd: BigDecimal?)

/** One of the costliest users; [name] null for an account that has gone. */
data class AiUserUsage(
    val userId: UserId,
    val name: String?,
    val textTurns: Long,
    val voiceSeconds: Long,
    val webSearches: Long,
    val tokens: TokenTotals,
    val costUsd: BigDecimal,
)

/** Anthropic's side: not asked (no admin key), asked and unreadable, or its bill and the gap to our token estimate. */
sealed interface AiReconciliation {
    data object Off : AiReconciliation

    data object Unavailable : AiReconciliation

    /** [differenceUsd] is billed minus our estimate of the same thing: model tokens, which Anthropic bills. */
    data class Billed(val bill: AiBill, val differenceUsd: BigDecimal) : AiReconciliation
}

data class AiUsageReport(
    /** The UTC days `[from, until)`. */
    val from: LocalDate,
    val until: LocalDate,
    val bucket: UsageBucket,
    val series: List<AiUsagePeriod>,
    val byModel: List<AiModelUsage>,
    val topUsers: List<AiUserUsage>,
    val textTurns: Long,
    val voiceSeconds: Long,
    val webSearches: Long,
    val tokens: TokenTotals,
    val activeUsers: Long,
    val tokenCostUsd: BigDecimal,
    val webSearchCostUsd: BigDecimal,
    /** Tokens of models the price table doesn't list: left out of every cost, and named on the model breakdown. */
    val unpricedTokens: Long,
    val pricing: AiPricing,
    val reconciliation: AiReconciliation,
) {
    val costUsd: BigDecimal get() = tokenCostUsd + webSearchCostUsd
}
