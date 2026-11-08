package com.circaso.integrations.api

import com.circaso.shared.Resource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * What the AI provider itself billed and counted over a range (decision 2026-11-08), for the staff cost report to set
 * beside our own estimate. Off unless an admin key is configured: then [isEnabled] is false and nothing is asked.
 * Dollars are exact decimals, never floating point.
 */
interface AiBilling {
    /** Whether the provider's billing can be asked at all (its admin key is set). */
    val isEnabled: Boolean

    /**
     * The provider's figures for the UTC days `[from, until)`. Fails with [IntegrationError.Unavailable] when the
     * provider can't be read (down, the key refused, an answer we can't read); never throws for that.
     */
    suspend fun billed(from: LocalDate, until: LocalDate): Resource<AiBill>
}

/**
 * [costUsd] is everything the provider billed in the range (tokens, web searches and any other line). [models] are its
 * own token counts by model; [days] its cost by day. [fetchedAt] is when the provider was asked (answers are kept a few
 * minutes). [scope] names what was counted: the whole organisation, or one workspace.
 */
data class AiBill(
    val provider: String,
    val scope: String,
    val costUsd: BigDecimal,
    val days: List<AiBilledDay>,
    val models: List<AiBilledModel>,
    val webSearches: Long,
    val fetchedAt: Instant,
)

data class AiBilledDay(val day: LocalDate, val costUsd: BigDecimal)

/** One model's billed cost and token counts; [cacheWriteTokens] covers every cache lifetime. */
data class AiBilledModel(
    val model: String,
    val costUsd: BigDecimal,
    val inputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
    val webSearches: Long,
)
