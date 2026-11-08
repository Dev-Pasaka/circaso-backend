package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.RuleChange
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.RuleAdminRepository
import com.circaso.wallet.domain.support.RuleChecks
import com.circaso.wallet.domain.support.RuleTimeline
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Staff: a new version of a fee rule (decision 2026-11-08). In one transaction under the bounds lock: the latest
 * version must still be the one the caller edited, the rate within the bounds; the latest version then ends where the
 * new one starts and the new one carries any end already scheduled. The scope is the rule's and never changes. Quotes
 * already shown keep their fee; the next quote after [effectiveFrom] prices with the new version.
 */
class ChangeFeeRule(
    private val admin: RuleAdminRepository,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        ruleId: UUID,
        baseVersion: Int,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<RuleChange<FeeRule>> {
        val now = clock.instant()
        return RuleChecks.note(note).flatMap { why ->
            RuleChecks.fee(terms).flatMap {
                RuleTimeline.at(effectiveFrom, now, "effectiveFrom").flatMap { from ->
                    write(ruleId, baseVersion, terms, from, why, now)
                }
            }
        }
    }

    private suspend fun write(
        ruleId: UUID,
        baseVersion: Int,
        terms: FeeRuleTerms,
        from: Instant,
        note: String,
        now: Instant,
    ): Resource<RuleChange<FeeRule>> =
        transactor.attempt("fee-rules.change") {
            val bounds = admin.lockFeeBounds()
            RuleTimeline.superseded(admin.feeVersions(ruleId), baseVersion, from, now).flatMap { latest ->
                RuleChecks.rate(terms.rateBps, bounds).flatMap {
                    val next =
                        latest.copy(
                            version = latest.version + 1,
                            rateBps = terms.rateBps,
                            capBaseMinor = terms.capBaseUsdMicros,
                            minFeeMinor = terms.minFeeUsdMicros,
                            gasShareMaxBps = terms.gasShareMaxBps,
                            gasMarginBps = terms.gasMarginBps,
                            effectiveFrom = from,
                            effectiveTo = latest.effectiveTo,
                            note = note,
                            createdAt = now,
                        )
                    admin.endFeeVersion(ruleId, latest.version, from)
                    RuleChecks
                        .written(admin.insertFeeVersion(next), bounds)
                        .map { RuleChange(latest, next) }
                }
            }
        }
}
