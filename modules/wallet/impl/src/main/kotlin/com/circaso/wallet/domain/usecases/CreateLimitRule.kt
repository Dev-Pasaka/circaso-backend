package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.wallet.api.LimitRuleScope
import com.circaso.wallet.api.LimitRuleTerms
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.RuleChange
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.RuleAdminRepository
import com.circaso.wallet.domain.repositories.RuleRepository
import com.circaso.wallet.domain.support.RuleChecks
import com.circaso.wallet.domain.support.RuleTimeline
import com.circaso.wallet.domain.support.scope
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Staff: a new limit rule, version 1 (decision 2026-11-08); refused when a live rule has exactly this scope. */
class CreateLimitRule(
    private val rules: RuleRepository,
    private val admin: RuleAdminRepository,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        scope: LimitRuleScope,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<RuleChange<LimitRule>> {
        val now = RuleTimeline.now(clock)
        return RuleChecks.note(note).flatMap { why ->
            RuleChecks.limitScope(scope).flatMap { wanted ->
                RuleChecks.limit(terms).flatMap {
                    RuleTimeline.at(effectiveFrom, now, "effectiveFrom").flatMap { from ->
                        write(draft(wanted, terms, from, why, now), now)
                    }
                }
            }
        }
    }

    private suspend fun write(rule: LimitRule, now: Instant): Resource<RuleChange<LimitRule>> =
        transactor.attempt("limit-rules.create") {
            val taken =
                rules
                    .limitRules()
                    .groupBy { it.ruleId }
                    .values
                    .filter { RuleTimeline.isLive(it, now) }
                    .map { versions -> versions.maxBy { it.version } }
                    .firstOrNull { it.scope() == rule.scope() }
            if (taken != null) return@attempt Resource.error(WalletError.RuleScopeTaken(taken.ruleId))
            RuleChecks.written(admin.insertLimitVersion(rule)).map { RuleChange(null, rule) }
        }

    private fun draft(
        scope: LimitRuleScope,
        terms: LimitRuleTerms,
        from: Instant,
        note: String,
        now: Instant,
    ) = LimitRule(
        ruleId = Uuid7.generate(now),
        version = 1,
        ownerType = scope.ownerType,
        walletId = scope.walletId,
        asset = scope.asset,
        perPaymentMaxMinor = terms.perPaymentMaxUsdMicros,
        dailyMaxMinor = terms.dailyMaxUsdMicros,
        window = Duration.ofHours(terms.windowHours.toLong()),
        effectiveFrom = from,
        effectiveTo = null,
        note = note,
        createdAt = now,
    )
}
