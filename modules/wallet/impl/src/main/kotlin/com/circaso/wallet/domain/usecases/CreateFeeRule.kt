package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.wallet.api.FeeRuleScope
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.RuleChange
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.RuleAdminRepository
import com.circaso.wallet.domain.repositories.RuleRepository
import com.circaso.wallet.domain.support.RuleChecks
import com.circaso.wallet.domain.support.RuleTimeline
import com.circaso.wallet.domain.support.scope
import com.circaso.wallet.domain.support.toFeeKind
import java.time.Clock
import java.time.Instant

/**
 * Staff: a new fee rule, version 1 (decision 2026-11-08). Refused when another live rule covers exactly the same
 * payments: the two would only tie, and the tie-break (oldest start, then id) is nobody's intent.
 */
class CreateFeeRule(
    private val rules: RuleRepository,
    private val admin: RuleAdminRepository,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        scope: FeeRuleScope,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<RuleChange<FeeRule>> {
        val now = clock.instant()
        return RuleChecks.note(note).flatMap { why ->
            RuleChecks.feeScope(scope).flatMap { wanted ->
                RuleChecks.fee(terms).flatMap {
                    RuleTimeline.at(effectiveFrom, now, "effectiveFrom").flatMap { from ->
                        write(draft(wanted, terms, from, why, now), now)
                    }
                }
            }
        }
    }

    private suspend fun write(rule: FeeRule, now: Instant): Resource<RuleChange<FeeRule>> =
        transactor.attempt("fee-rules.create") {
            val bounds = admin.lockFeeBounds()
            val taken =
                rules
                    .feeRules()
                    .groupBy { it.ruleId }
                    .values
                    .filter { RuleTimeline.isLive(it, now) }
                    .map { versions -> versions.maxBy { it.version } }
                    .firstOrNull { it.scope() == rule.scope() }
            if (taken != null) return@attempt Resource.error(WalletError.RuleScopeTaken(taken.ruleId))
            RuleChecks
                .rate(rule.rateBps, bounds)
                .flatMap { RuleChecks.written(admin.insertFeeVersion(rule), bounds) }
                .map { RuleChange(null, rule) }
        }

    private fun draft(
        scope: FeeRuleScope,
        terms: FeeRuleTerms,
        from: Instant,
        note: String,
        now: Instant,
    ) = FeeRule(
        ruleId = Uuid7.generate(now),
        version = 1,
        kind = scope.kind?.toFeeKind(),
        senderType = scope.senderType,
        receiverType = scope.receiverType,
        blockchain = scope.blockchain,
        asset = scope.asset,
        walletId = scope.walletId,
        rateBps = terms.rateBps,
        capBaseMinor = terms.capBaseUsdMicros,
        minFeeMinor = terms.minFeeUsdMicros,
        gasShareMaxBps = terms.gasShareMaxBps,
        gasMarginBps = terms.gasMarginBps,
        effectiveFrom = from,
        effectiveTo = null,
        note = note,
        createdAt = now,
    )
}
