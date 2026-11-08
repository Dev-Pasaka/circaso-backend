package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.wallet.api.LimitRuleTerms
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.RuleChange
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.RuleAdminRepository
import com.circaso.wallet.domain.support.RuleChecks
import com.circaso.wallet.domain.support.RuleTimeline
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Staff: a new version of a limit rule (decision 2026-11-08), as [ChangeFeeRule] does for fees: the rule's versions are
 * locked first, then read again, so a second writer waits and then sees the first one's version.
 */
class ChangeLimitRule(
    private val admin: RuleAdminRepository,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        ruleId: UUID,
        baseVersion: Int,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<RuleChange<LimitRule>> {
        val now = clock.instant()
        return RuleChecks.note(note).flatMap { why ->
            RuleChecks.limit(terms).flatMap {
                RuleTimeline.at(effectiveFrom, now, "effectiveFrom").flatMap { from ->
                    write(ruleId, baseVersion, terms, from, why, now)
                }
            }
        }
    }

    private suspend fun write(
        ruleId: UUID,
        baseVersion: Int,
        terms: LimitRuleTerms,
        from: Instant,
        note: String,
        now: Instant,
    ): Resource<RuleChange<LimitRule>> =
        transactor.attempt("limit-rules.change") {
            if (!admin.lockLimitRule(ruleId)) return@attempt Resource.error(WalletError.RuleNotFound)
            RuleTimeline.superseded(admin.limitVersions(ruleId), baseVersion, from, now).flatMap { latest ->
                val next =
                    latest.copy(
                        version = latest.version + 1,
                        perPaymentMaxMinor = terms.perPaymentMaxUsdMicros,
                        dailyMaxMinor = terms.dailyMaxUsdMicros,
                        window = Duration.ofHours(terms.windowHours.toLong()),
                        effectiveFrom = from,
                        effectiveTo = latest.effectiveTo,
                        note = note,
                        createdAt = now,
                    )
                admin.endLimitVersion(ruleId, latest.version, from)
                RuleChecks.written(admin.insertLimitVersion(next)).map { RuleChange(latest, next) }
            }
        }
}
