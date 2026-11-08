package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.RuleChange
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.RuleAdminRepository
import com.circaso.wallet.domain.support.RuleTimeline
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Staff: ends a fee rule at [effectiveTo], now when null (decision 2026-11-08). Only the latest version's end is set;
 * no version is deleted. Payments it matched fall to the next most specific rule, or carry no fee.
 */
class EndFeeRule(
    private val admin: RuleAdminRepository,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
    ): Resource<RuleChange<FeeRule>> {
        val now = clock.instant()
        return RuleTimeline.at(effectiveTo, now, "effectiveTo").flatMap { to ->
            transactor.attempt("fee-rules.end") {
                admin.lockFeeBounds()
                RuleTimeline.ended(admin.feeVersions(ruleId), baseVersion, to, now).flatMap { latest ->
                    admin.endFeeVersion(ruleId, latest.version, to)
                    Resource.success(RuleChange(latest, latest.copy(effectiveTo = to)))
                }
            }
        }
    }
}
