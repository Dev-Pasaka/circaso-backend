package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

// The AI usage report (decision 2026-11-08). Dollars are integer micro-dollars (`…UsdMicros`, 1 USD = 1_000_000),
// computed exactly and rounded once, as every money figure the portal gets; prices are decimal strings.

@Serializable
data class TokensDto(
    val input: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val output: Long,
)

@Serializable
data class AiUsageTotalsDto(
    val textTurns: Long,
    val voiceSeconds: Long,
    val webSearches: Long,
    val tokens: TokensDto,
    /** Different users who used Asha in the range. */
    val activeUsers: Long,
    val costUsdMicros: Long,
    val tokenCostUsdMicros: Long,
    val webSearchCostUsdMicros: Long,
    /** The cost over the active users; null when there were none. */
    val costPerActiveUserUsdMicros: Long? = null,
    /** Tokens of models without a price: counted, never costed. */
    val unpricedTokens: Long,
)

@Serializable
data class AiUsagePeriodDto(
    val period: String,
    val textTurns: Long,
    val voiceSeconds: Long,
    val webSearches: Long,
    val tokens: TokensDto,
    val costUsdMicros: Long,
)

@Serializable
data class AiModelUsageDto(
    val model: String,
    val priced: Boolean,
    val tokens: TokensDto,
    val costUsdMicros: Long? = null,
)

@Serializable
data class AiUserUsageDto(
    val userId: String,
    val displayName: String? = null,
    val textTurns: Long,
    val voiceSeconds: Long,
    val webSearches: Long,
    val tokens: TokensDto,
    val costUsdMicros: Long,
)

@Serializable
data class AiModelPriceDto(
    val model: String,
    val inputPerMTokUsd: String,
    val cacheWritePerMTokUsd: String,
    val cacheReadPerMTokUsd: String,
    val outputPerMTokUsd: String,
)

@Serializable
data class AiPricesDto(
    val asOf: String,
    val models: List<AiModelPriceDto>,
    val cacheWriteMultiplier: String,
    val cacheReadMultiplier: String,
    val webSearchPer1000Usd: String,
    /** What the estimate takes for granted, in a sentence for the page. */
    val assumptions: List<String>,
)

@Serializable
data class AiBilledModelDto(
    val model: String,
    val costUsdMicros: Long,
    val tokens: TokensDto,
    val webSearches: Long,
)

@Serializable
data class AiBilledDayDto(val day: String, val costUsdMicros: Long)

/**
 * Anthropic's own figures. [status]: `off` (no admin key; the rest is null), `unavailable` (asked, couldn't be read),
 * `ok`. [differenceUsdMicros] is billed minus our token estimate.
 */
@Serializable
data class AiBillingDto(
    val status: String,
    val provider: String = "anthropic",
    val scope: String? = null,
    val billedUsdMicros: Long? = null,
    val estimatedUsdMicros: Long? = null,
    val differenceUsdMicros: Long? = null,
    val fetchedAt: String? = null,
    val models: List<AiBilledModelDto> = emptyList(),
    val days: List<AiBilledDayDto> = emptyList(),
)

@Serializable
data class AiUsageResponse(
    val from: String,
    val to: String,
    val bucket: String,
    val totals: AiUsageTotalsDto,
    val series: List<AiUsagePeriodDto>,
    val byModel: List<AiModelUsageDto>,
    val topUsers: List<AiUserUsageDto>,
    val prices: AiPricesDto,
    val anthropic: AiBillingDto,
)
