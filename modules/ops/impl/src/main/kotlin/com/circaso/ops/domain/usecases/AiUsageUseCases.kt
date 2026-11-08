package com.circaso.ops.domain.usecases

import com.circaso.assistant.api.AssistantReports
import com.circaso.assistant.api.ModelTokenSums
import com.circaso.integrations.api.AiBill
import com.circaso.integrations.api.AiBilling
import com.circaso.ops.domain.models.AiModelUsage
import com.circaso.ops.domain.models.AiReconciliation
import com.circaso.ops.domain.models.AiUsagePeriod
import com.circaso.ops.domain.models.AiUsageReport
import com.circaso.ops.domain.models.AiUserUsage
import com.circaso.ops.domain.models.TokenTotals
import com.circaso.ops.domain.models.UsageBucket
import com.circaso.ops.domain.support.AiPricing
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.domain.support.Periods
import com.circaso.ops.domain.support.UserViews
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.getOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What Asha's model use cost over the UTC days `[from, until)`, by period, by model and for the [limit] costliest users
 * (decision 2026-11-08): the assistant's daily totals priced with [pricing], and, when an Anthropic Admin key is set,
 * Anthropic's own bill for the same days beside it. Anthropic being unreadable never fails the report: it says so.
 * The range is 1 to [OpsSettings.maxRangeDays] days. Read-only, so not audited (as every staff read).
 */
class GetAiUsageReport(
    private val reports: AssistantReports,
    private val billing: AiBilling,
    private val views: UserViews,
    private val pricing: AiPricing,
    private val settings: OpsSettings,
) {
    suspend operator fun invoke(
        from: LocalDate,
        until: LocalDate,
        bucket: UsageBucket,
        limit: Int,
    ): Resource<AiUsageReport> {
        val days = ChronoUnit.DAYS.between(from, until)
        if (days < 1 || days > settings.maxRangeDays) {
            return Resource.error(
                SharedError.Malformed("to", "must be after from, at most ${settings.maxRangeDays} days"),
            )
        }
        return coroutineScope {
            val billed = async { reconcile(from, until) }
            val usage = reports.days(from, until)
            val active = reports.activeUsers(from, until)
            val top = reports.topUsers(from, until, pricing.rates(), limit)

            // Each day's cost by model, exact; periods, models and totals add these up.
            val byPeriod = LinkedHashMap<LocalDate, AiUsagePeriod>()
            Periods.of(from, until, bucket).forEach {
                byPeriod[it] = AiUsagePeriod(it, 0, 0, 0, TokenTotals(), BigDecimal.ZERO)
            }
            val byModel = sortedMapOf<String, TokenTotals>()
            for (day in usage) {
                val period = Periods.periodOf(day.day, bucket)
                val tokens = day.tokens.fold(TokenTotals()) { sum, t -> sum + t.totals() }
                val cost = day.tokens.sumCost() + pricing.webSearchCost(day.webSearches)
                day.tokens.forEach { byModel.merge(it.model, it.totals(), TokenTotals::plus) }
                byPeriod[period]?.let {
                    byPeriod[period] =
                        it.copy(
                            textTurns = it.textTurns + day.textTurns,
                            voiceSeconds = it.voiceSeconds + day.voiceSeconds,
                            webSearches = it.webSearches + day.webSearches,
                            tokens = it.tokens + tokens,
                            costUsd = it.costUsd + cost,
                        )
                }
            }
            val models =
                byModel
                    .map { (model, t) ->
                        AiModelUsage(model, t, pricing.tokenCost(model, t.input, t.cacheRead, t.cacheWrite, t.output))
                    }.sortedWith(
                        compareByDescending<AiModelUsage> { it.costUsd ?: BigDecimal.ZERO }.thenBy { it.model },
                    )
            val names = views.names(top.map { it.userId }.toSet())
            val users =
                top.map { user ->
                    AiUserUsage(
                        userId = user.userId,
                        name = names[user.userId],
                        textTurns = user.textTurns,
                        voiceSeconds = user.voiceSeconds,
                        webSearches = user.webSearches,
                        tokens = user.tokens.fold(TokenTotals()) { sum, t -> sum + t.totals() },
                        costUsd = user.tokens.sumCost() + pricing.webSearchCost(user.webSearches),
                    )
                }
            val tokenCost = models.mapNotNull { it.costUsd }.fold(BigDecimal.ZERO, BigDecimal::add)
            val webSearches = usage.sumOf { it.webSearches }
            Resource.success(
                AiUsageReport(
                    from = from,
                    until = until,
                    bucket = bucket,
                    series = byPeriod.values.toList(),
                    byModel = models,
                    topUsers = users,
                    textTurns = usage.sumOf { it.textTurns },
                    voiceSeconds = usage.sumOf { it.voiceSeconds },
                    webSearches = webSearches,
                    tokens = models.fold(TokenTotals()) { sum, m -> sum + m.tokens },
                    activeUsers = active,
                    tokenCostUsd = tokenCost,
                    webSearchCostUsd = pricing.webSearchCost(webSearches),
                    unpricedTokens = models.filter { it.costUsd == null }.sumOf { it.tokens.all },
                    pricing = pricing,
                    reconciliation = billed.await().against(tokenCost),
                ),
            )
        }
    }

    /** Anthropic's bill, asked while the totals are read; null when it is off or couldn't be read. */
    private suspend fun reconcile(from: LocalDate, until: LocalDate): AiBill? {
        if (!billing.isEnabled) return null
        return try {
            billing.billed(from, until).getOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Billed minus our estimate of the same thing (model tokens, which is what Anthropic bills). */
    private fun AiBill?.against(estimate: BigDecimal): AiReconciliation =
        when {
            !billing.isEnabled -> AiReconciliation.Off
            this == null -> AiReconciliation.Unavailable
            else -> AiReconciliation.Billed(this, costUsd - estimate)
        }

    private fun ModelTokenSums.totals() = TokenTotals(inputTokens, cacheReadTokens, cacheWriteTokens, outputTokens)

    /** Priced models only: an unpriced model's tokens are counted, never costed (the model breakdown names them). */
    private fun List<ModelTokenSums>.sumCost(): BigDecimal =
        mapNotNull {
            pricing.tokenCost(
                it.model,
                it.inputTokens,
                it.cacheReadTokens,
                it.cacheWriteTokens,
                it.outputTokens,
            )
        }.fold(BigDecimal.ZERO, BigDecimal::add)
}
