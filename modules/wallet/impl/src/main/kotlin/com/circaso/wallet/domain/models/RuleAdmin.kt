package com.circaso.wallet.domain.models

import java.time.Instant
import java.util.UUID

// Staff edits of fees and limits (decision 2026-11-08). A rule's versions form a timeline: each version runs from its
// effectiveFrom until the next version starts or its own effectiveTo, and a change only ever adds to the end of it.

/** One version of a fee or limit rule, as the timeline sees it. */
interface VersionedRule {
    val ruleId: UUID
    val version: Int
    val effectiveFrom: Instant
    val effectiveTo: Instant?

    fun inForce(at: Instant): Boolean = !effectiveFrom.isAfter(at) && (effectiveTo == null || at.isBefore(effectiveTo))

    /** Applies now or will later: not ended by [at]. */
    fun liveAt(at: Instant): Boolean = effectiveTo == null || at.isBefore(effectiveTo)
}

/** `wallet.fee_bounds`: every fee rate stays within these. */
data class FeeBounds(val minRateBps: Int, val maxRateBps: Int, val updatedAt: Instant) {
    fun allows(rateBps: Int): Boolean = rateBps in minRateBps..maxRateBps
}

/** A change as it landed: the version superseded or ended ([before], null for a new rule) and the result ([after]). */
data class RuleChange<T : VersionedRule>(val before: T?, val after: T)

data class BoundsChange(val before: FeeBounds, val after: FeeBounds)

/**
 * Every fee rule version, the bounds, and what the network costs now on each chain (decision 2026-11-08): [networkCosts]
 * where there is recent history, [estimates] (the last Circle estimate quoted) for chains without it.
 */
data class FeeRuleSet(
    val at: Instant,
    val bounds: FeeBounds,
    val rules: List<FeeRule>,
    val networkCosts: List<RecentGas> = emptyList(),
    val estimates: List<GasEstimate> = emptyList(),
)

data class LimitRuleSet(val at: Instant, val rules: List<LimitRule>)

/** How a new version's insert ended: written, its (rule, version) taken by a racing writer, or its rate refused. */
enum class VersionWrite { WRITTEN, TAKEN, OUT_OF_BOUNDS }
