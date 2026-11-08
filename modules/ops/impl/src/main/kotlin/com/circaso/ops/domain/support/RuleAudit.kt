package com.circaso.ops.domain.support

import com.circaso.wallet.api.FeeBoundsChange
import com.circaso.wallet.api.FeeRuleChange
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.api.FeeRuleVersion
import com.circaso.wallet.api.LimitRuleChange
import com.circaso.wallet.api.LimitRuleTerms
import com.circaso.wallet.api.LimitRuleVersion
import java.math.BigDecimal
import java.time.Instant

/**
 * The audit line's detail for a fee or limit change (decision 2026-11-08: every write with its before and after). It
 * is the wallet's answer, so it is what was actually written, read under the wallet's lock: `before v2 {…} → after v3
 * {…}`. Rates in basis points, dollars exact, times ISO-8601; nothing personal.
 */
object RuleAudit {
    fun requested(terms: FeeRuleTerms, from: Instant?): String = "requested {${fee(terms)}, from ${from ?: "now"}}"

    fun requested(terms: LimitRuleTerms, from: Instant?): String = "requested {${limit(terms)}, from ${from ?: "now"}}"

    fun ending(baseVersion: Int, to: Instant?): String = "end v$baseVersion at ${to ?: "now"}"

    fun of(change: FeeRuleChange): String =
        pair(change.before?.let(::version), version(change.after), change.after.ruleId.toString())

    fun of(change: LimitRuleChange): String =
        pair(change.before?.let(::version), version(change.after), change.after.ruleId.toString())

    fun of(change: FeeBoundsChange): String =
        "before {${change.before.minRateBps}-${change.before.maxRateBps} bps} → " +
            "after {${change.after.minRateBps}-${change.after.maxRateBps} bps}"

    /** A new rule has no before: its line names the rule instead, since the audit line's target was not known yet. */
    private fun pair(
        before: String?,
        after: String,
        ruleId: String,
    ): String = if (before == null) "new rule $ruleId: after $after" else "before $before → after $after"

    private fun version(rule: FeeRuleVersion): String =
        "v${rule.version} {${fee(rule.terms)}, ${window(rule.effectiveFrom, rule.effectiveTo)}}"

    private fun version(rule: LimitRuleVersion): String =
        "v${rule.version} {${limit(rule.terms)}, ${window(rule.effectiveFrom, rule.effectiveTo)}}"

    private fun fee(terms: FeeRuleTerms): String =
        "rate ${terms.rateBps} bps, cap ${usd(terms.capBaseUsdMicros)}, min ${usd(terms.minFeeUsdMicros)}, " +
            "gas share ${terms.gasShareMaxBps} bps, margin ${terms.gasMarginBps} bps"

    private fun limit(terms: LimitRuleTerms): String =
        "per payment ${usd(terms.perPaymentMaxUsdMicros)}, per window ${usd(terms.dailyMaxUsdMicros)}, " +
            "window ${terms.windowHours} h"

    private fun window(from: Instant, to: Instant?): String = "$from → ${to ?: "open"}"

    private fun usd(micros: Long?): String =
        micros?.let { "$" + BigDecimal.valueOf(it, MICRO_DIGITS).stripTrailingZeros().toPlainString() } ?: "none"

    private const val MICRO_DIGITS = 6
}
