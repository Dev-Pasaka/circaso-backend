package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.RuleChange
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.RuleAdminRepository
import com.circaso.wallet.domain.support.RuleTimeline
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Staff: ends a limit rule at [effectiveTo], now when null (decision 2026-11-08); no version is deleted. */
class EndLimitRule(
    private val admin: RuleAdminRepository,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
    ): Resource<RuleChange<LimitRule>> {
        val now = clock.instant()
        return RuleTimeline.at(effectiveTo, now, "effectiveTo").flatMap { to ->
            transactor.attempt("limit-rules.end") {
                if (!admin.lockLimitRule(ruleId)) return@attempt Resource.error(WalletError.RuleNotFound)
                RuleTimeline.ended(admin.limitVersions(ruleId), baseVersion, to, now).flatMap { latest ->
                    admin.endLimitVersion(ruleId, latest.version, to)
                    Resource.success(RuleChange(latest, latest.copy(effectiveTo = to)))
                }
            }
        }
    }
}
