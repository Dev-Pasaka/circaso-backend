package com.circaso.wallet.presentation.mappers

import com.circaso.wallet.api.FeeBoundsChange
import com.circaso.wallet.api.FeeRateBounds
import com.circaso.wallet.api.FeeRuleBook
import com.circaso.wallet.api.FeeRuleChange
import com.circaso.wallet.api.FeeRuleHistory
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.api.FeeRuleVersion
import com.circaso.wallet.api.LimitRuleBook
import com.circaso.wallet.api.LimitRuleChange
import com.circaso.wallet.api.LimitRuleHistory
import com.circaso.wallet.api.LimitRuleVersion
import com.circaso.wallet.api.NetworkCostNow
import com.circaso.wallet.api.NetworkCostSource
import com.circaso.wallet.domain.models.BoundsChange
import com.circaso.wallet.domain.models.FeeBounds
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.FeeRuleSet
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.LimitRuleSet
import com.circaso.wallet.domain.models.RuleChange
import com.circaso.wallet.domain.support.RuleTimeline
import com.circaso.wallet.domain.support.scope
import com.circaso.wallet.domain.support.terms

// The wallet's fees-and-limits staff contract (decision 2026-11-08) from its domain models: each rule with every
// version newest first, rules ordered live first and then by when they were created.

fun FeeBounds.toOps() = FeeRateBounds(minRateBps, maxRateBps, updatedAt)

fun FeeRule.toOps() =
    FeeRuleVersion(
        ruleId = ruleId,
        version = version,
        scope = scope(),
        terms = FeeRuleTerms(rateBps, capBaseMinor, minFeeMinor, gasShareMaxBps, gasMarginBps),
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
        note = note,
        createdAt = createdAt,
    )

fun FeeRuleSet.toOps(): FeeRuleBook {
    val histories =
        rules.groupBy { it.ruleId }.map { (ruleId, versions) ->
            val (status, current) = RuleTimeline.status(versions, at)
            FeeRuleHistory(ruleId, status, current, versions.sortedByDescending { it.version }.map { it.toOps() })
        }
    val recent =
        networkCosts.map {
            NetworkCostNow(it.blockchain, it.asset, it.gasMinor, NetworkCostSource.RECENT, it.samples, it.at)
        }
    val pooled = recent.filter { it.asset == null }.map { it.blockchain }.toSet()
    val estimated =
        estimates.filter { it.blockchain !in pooled }.map {
            NetworkCostNow(it.blockchain, null, it.gasMinor, NetworkCostSource.ESTIMATE, 0, it.at)
        }
    return FeeRuleBook(
        at,
        bounds.toOps(),
        histories.sortedWith(compareBy({ it.status }, { it.ruleId })),
        (recent + estimated).sortedWith(compareBy({ it.blockchain }, { it.asset ?: "" })),
    )
}

fun RuleChange<FeeRule>.toFeeOps() = FeeRuleChange(before?.toOps(), after.toOps())

fun BoundsChange.toOps() = FeeBoundsChange(before.toOps(), after.toOps())

fun LimitRule.toOps() =
    LimitRuleVersion(
        ruleId = ruleId,
        version = version,
        scope = scope(),
        terms = terms(),
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
        note = note,
        createdAt = createdAt,
    )

fun LimitRuleSet.toOps(): LimitRuleBook {
    val histories =
        rules.groupBy { it.ruleId }.map { (ruleId, versions) ->
            val (status, current) = RuleTimeline.status(versions, at)
            LimitRuleHistory(ruleId, status, current, versions.sortedByDescending { it.version }.map { it.toOps() })
        }
    return LimitRuleBook(at, histories.sortedWith(compareBy({ it.status }, { it.ruleId })))
}

fun RuleChange<LimitRule>.toLimitOps() = LimitRuleChange(before?.toOps(), after.toOps())
