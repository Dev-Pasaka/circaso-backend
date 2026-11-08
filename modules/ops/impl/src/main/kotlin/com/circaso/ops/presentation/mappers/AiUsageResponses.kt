package com.circaso.ops.presentation.mappers

import com.circaso.ops.domain.models.AiReconciliation
import com.circaso.ops.domain.models.AiUsageReport
import com.circaso.ops.domain.models.TokenTotals
import com.circaso.ops.domain.support.AiPricing
import com.circaso.ops.domain.support.AiPricing.Companion.micros
import com.circaso.ops.presentation.dto.AiBilledDayDto
import com.circaso.ops.presentation.dto.AiBilledModelDto
import com.circaso.ops.presentation.dto.AiBillingDto
import com.circaso.ops.presentation.dto.AiModelPriceDto
import com.circaso.ops.presentation.dto.AiModelUsageDto
import com.circaso.ops.presentation.dto.AiPricesDto
import com.circaso.ops.presentation.dto.AiUsagePeriodDto
import com.circaso.ops.presentation.dto.AiUsageResponse
import com.circaso.ops.presentation.dto.AiUsageTotalsDto
import com.circaso.ops.presentation.dto.AiUserUsageDto
import com.circaso.ops.presentation.dto.TokensDto
import java.time.ZoneOffset

fun AiUsageReport.toResponse() =
    AiUsageResponse(
        from = from.atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
        to = until.atStartOfDay().toInstant(ZoneOffset.UTC).toString(),
        bucket = bucket.name,
        totals =
            AiUsageTotalsDto(
                textTurns = textTurns,
                voiceSeconds = voiceSeconds,
                webSearches = webSearches,
                tokens = tokens.toDto(),
                activeUsers = activeUsers,
                costUsdMicros = micros(costUsd),
                tokenCostUsdMicros = micros(tokenCostUsd),
                webSearchCostUsdMicros = micros(webSearchCostUsd),
                costPerActiveUserUsdMicros = AiPricing.microsPer(costUsd, activeUsers),
                unpricedTokens = unpricedTokens,
            ),
        series =
            series.map {
                AiUsagePeriodDto(
                    period = it.period.toString(),
                    textTurns = it.textTurns,
                    voiceSeconds = it.voiceSeconds,
                    webSearches = it.webSearches,
                    tokens = it.tokens.toDto(),
                    costUsdMicros = micros(it.costUsd),
                )
            },
        byModel =
            byModel.map {
                AiModelUsageDto(
                    it.model,
                    it.costUsd != null,
                    it.tokens.toDto(),
                    it.costUsd?.let(::micros),
                )
            },
        topUsers =
            topUsers.map {
                AiUserUsageDto(
                    userId = it.userId.toString(),
                    displayName = it.name,
                    textTurns = it.textTurns,
                    voiceSeconds = it.voiceSeconds,
                    webSearches = it.webSearches,
                    tokens = it.tokens.toDto(),
                    costUsdMicros = micros(it.costUsd),
                )
            },
        prices = pricing.toDto(),
        anthropic = reconciliation.toDto(this),
    )

private fun TokenTotals.toDto() = TokensDto(input, cacheRead, cacheWrite, output)

private fun AiPricing.toDto() =
    AiPricesDto(
        asOf = asOf.toString(),
        models =
            models.map {
                AiModelPriceDto(
                    model = it.model,
                    inputPerMTokUsd = it.inputPerMTok.toPlainString(),
                    cacheWritePerMTokUsd = cacheWritePerMTok(it).stripTrailingZeros().toPlainString(),
                    cacheReadPerMTokUsd = cacheReadPerMTok(it).stripTrailingZeros().toPlainString(),
                    outputPerMTokUsd = it.outputPerMTok.toPlainString(),
                )
            },
        cacheWriteMultiplier = cacheWriteMultiplier.toPlainString(),
        cacheReadMultiplier = cacheReadMultiplier.toPlainString(),
        webSearchPer1000Usd = webSearchPer1000.toPlainString(),
        assumptions =
            listOf(
                "Prompts under 100K tokens: longer prompts cost more, and daily totals can't tell them apart.",
                "Cache writes at the 5-minute rate, as Asha caches.",
                "Standard tier, global inference; no batch or regional pricing.",
            ),
    )

private fun AiReconciliation.toDto(report: AiUsageReport): AiBillingDto =
    when (this) {
        AiReconciliation.Off -> {
            AiBillingDto(status = "off")
        }

        AiReconciliation.Unavailable -> {
            AiBillingDto(status = "unavailable", estimatedUsdMicros = micros(report.tokenCostUsd))
        }

        is AiReconciliation.Billed -> {
            AiBillingDto(
                status = "ok",
                provider = bill.provider,
                scope = bill.scope,
                billedUsdMicros = micros(bill.costUsd),
                estimatedUsdMicros = micros(report.tokenCostUsd),
                differenceUsdMicros = micros(differenceUsd),
                fetchedAt = bill.fetchedAt.toString(),
                models =
                    bill.models.map {
                        AiBilledModelDto(
                            model = it.model,
                            costUsdMicros = micros(it.costUsd),
                            tokens =
                                TokensDto(
                                    it.inputTokens,
                                    it.cacheReadTokens,
                                    it.cacheWriteTokens,
                                    it.outputTokens,
                                ),
                            webSearches = it.webSearches,
                        )
                    },
                days = bill.days.map { AiBilledDayDto(it.day.toString(), micros(it.costUsd)) },
            )
        }
    }
