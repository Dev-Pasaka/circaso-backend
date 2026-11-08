package com.circaso.wallet.api

import com.circaso.shared.Resource
import java.time.Instant
import java.util.UUID

// The wallet's staff contract for fees and limits (decision 2026-11-08), for the ops module behind the ops gateway
// only. Rules are versioned (phase 2F, §7a/§7b): a change adds a version and never overwrites one, so every payment
// can still be traced to the version that priced it. Dollar figures are integer micro-dollars (1 USD = 1_000_000), the
// unit the rules already reach the domain in; rates and gas shares are integer basis points.

/** Between two Circaso wallets, or to an address outside Circaso. */
enum class FeeRuleKind { INTERNAL, EXTERNAL }

/** Where a rule stands now: a version in force, only a version that starts later, or none any more. */
enum class RuleStatus { ACTIVE, SCHEDULED, ENDED }

/** What a fee rule matches; a null field matches anything. Fixed for the life of the rule. */
data class FeeRuleScope(
    val kind: FeeRuleKind?,
    val senderType: WalletOwnerType?,
    val receiverType: WalletOwnerType?,
    val blockchain: String?,
    val asset: String?,
    val walletId: WalletId?,
)

/**
 * What a fee rule charges: `max(rate × min(amount, capBase), minFee, gas floor)`, the gas floor being the estimated
 * network cost plus [gasMarginBps], over [gasShareMaxBps] of the fee.
 */
data class FeeRuleTerms(
    val rateBps: Int,
    val capBaseUsdMicros: Long,
    val minFeeUsdMicros: Long?,
    val gasShareMaxBps: Int,
    val gasMarginBps: Int,
)

data class FeeRuleVersion(
    val ruleId: UUID,
    val version: Int,
    val scope: FeeRuleScope,
    val terms: FeeRuleTerms,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val note: String?,
    val createdAt: Instant?,
)

/** One rule and every version of it, newest first; [current] is the version in force at [FeeRuleBook.at]. */
data class FeeRuleHistory(
    val ruleId: UUID,
    val status: RuleStatus,
    val current: Int?,
    val versions: List<FeeRuleVersion>,
)

/** The bounds every fee rate must stay within (`wallet.fee_bounds`). */
data class FeeRateBounds(val minRateBps: Int, val maxRateBps: Int, val updatedAt: Instant)

data class FeeRuleBook(val at: Instant, val bounds: FeeRateBounds, val rules: List<FeeRuleHistory>)

/** A change as it landed: [before] is the version it superseded or ended (null for a new rule), [after] the result. */
data class FeeRuleChange(val before: FeeRuleVersion?, val after: FeeRuleVersion)

data class FeeBoundsChange(val before: FeeRateBounds, val after: FeeRateBounds)

/** What a limit rule matches; a null field matches anything. */
data class LimitRuleScope(val ownerType: WalletOwnerType?, val walletId: WalletId?, val asset: String?)

/** A null cap is no cap; [dailyMaxUsdMicros] counts what leaves the wallet, fees included, over [windowHours]. */
data class LimitRuleTerms(val perPaymentMaxUsdMicros: Long?, val dailyMaxUsdMicros: Long?, val windowHours: Int)

data class LimitRuleVersion(
    val ruleId: UUID,
    val version: Int,
    val scope: LimitRuleScope,
    val terms: LimitRuleTerms,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val note: String?,
    val createdAt: Instant?,
)

data class LimitRuleHistory(
    val ruleId: UUID,
    val status: RuleStatus,
    val current: Int?,
    val versions: List<LimitRuleVersion>,
)

data class LimitRuleBook(val at: Instant, val rules: List<LimitRuleHistory>)

data class LimitRuleChange(val before: LimitRuleVersion?, val after: LimitRuleVersion)

/**
 * Staff edits of fees and limits. Every write runs in one wallet transaction under a lock, so two staff can't both
 * build on the same version: the second answers `wallet.rule_changed`. [baseVersion] is the version the caller edited
 * from. A time left null means now; a time in the past is refused. Quotes already shown keep the fee they quoted;
 * every quote and payment priced after the new version starts uses it (rules are read fresh on every quote).
 *
 * Errors: `wallet.rule_not_found`, `wallet.rule_changed`, `wallet.rule_ended`, `wallet.rule_change_pending`,
 * `wallet.rule_scope_taken`, `wallet.rule_invalid`, `wallet.fee_rate_out_of_bounds`,
 * `wallet.fee_bounds_exclude_rules`.
 */
interface FeeAdmin {
    suspend fun feeRules(): FeeRuleBook

    suspend fun createFeeRule(
        scope: FeeRuleScope,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<FeeRuleChange>

    /** A new version of [ruleId] with [terms]; the scope stays the rule's. */
    suspend fun changeFeeRule(
        ruleId: UUID,
        baseVersion: Int,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<FeeRuleChange>

    /** Sets the latest version's end; nothing is deleted. */
    suspend fun endFeeRule(
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
    ): Resource<FeeRuleChange>

    /** Refused while a rule in force now or later would fall outside the new bounds. */
    suspend fun updateFeeBounds(minRateBps: Int, maxRateBps: Int): Resource<FeeBoundsChange>

    suspend fun limitRules(): LimitRuleBook

    suspend fun createLimitRule(
        scope: LimitRuleScope,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<LimitRuleChange>

    suspend fun changeLimitRule(
        ruleId: UUID,
        baseVersion: Int,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<LimitRuleChange>

    suspend fun endLimitRule(
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
    ): Resource<LimitRuleChange>
}
