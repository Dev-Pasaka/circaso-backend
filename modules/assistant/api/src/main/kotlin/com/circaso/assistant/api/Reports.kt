package com.circaso.assistant.api

import com.circaso.shared.UserId
import java.math.BigDecimal
import java.time.LocalDate

// The assistant's staff-facing contract (decision 2026-11-08), for the ops module behind the ops gateway only: what
// Asha used, by day, by model and by user, from `assistant.usage_daily` and `assistant.usage_daily_tokens`. Read-only,
// and facts only: pricing is the caller's (ops keeps the price table), except the order of [AssistantReports.topUsers].

/**
 * One model's tokens, split as the provider prices them: [inputTokens] fresh input, [cacheReadTokens] input read from
 * the prompt cache, [cacheWriteTokens] input written to it, [outputTokens] what the model wrote.
 */
data class ModelTokenSums(
    val model: String,
    val inputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
)

/**
 * One UTC day across every user. [activeUsers] counts those who used Asha that day: a typed turn, a voice clip or a
 * live conversation.
 */
data class AshaUsageDay(
    val day: LocalDate,
    val textTurns: Long,
    val voiceSeconds: Long,
    val webSearches: Long,
    val activeUsers: Long,
    val tokens: List<ModelTokenSums>,
)

/** One user's use over a range. */
data class AshaUsageUser(
    val userId: UserId,
    val textTurns: Long,
    val voiceSeconds: Long,
    val webSearches: Long,
    val tokens: List<ModelTokenSums>,
)

/** US dollars per token, by kind, for one model. */
data class TokenRates(
    val input: BigDecimal,
    val cacheRead: BigDecimal,
    val cacheWrite: BigDecimal,
    val output: BigDecimal,
)

/**
 * The weights [AssistantReports.topUsers] orders by: dollars per token by model (a model missing here weighs nothing)
 * and dollars per web search.
 */
data class UsageRates(val byModel: Map<String, TokenRates>, val perWebSearch: BigDecimal)

interface AssistantReports {
    /**
     * Every day of `[from, until)` that has any use, oldest first. Days are UTC; the range is at most 366 days (wider
     * or reversed is `request.malformed`, thrown).
     */
    suspend fun days(from: LocalDate, until: LocalDate): List<AshaUsageDay>

    /** How many different users used Asha in `[from, until)`, as [AshaUsageDay.activeUsers]. Same range rule. */
    suspend fun activeUsers(from: LocalDate, until: LocalDate): Long

    /**
     * The [limit] users (1-200) whose use over `[from, until)` costs most at [rates], most first; ties by user id.
     * Same range rule.
     */
    suspend fun topUsers(
        from: LocalDate,
        until: LocalDate,
        rates: UsageRates,
        limit: Int,
    ): List<AshaUsageUser>
}
