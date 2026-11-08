package com.circaso.wallet.domain.usecases

import com.circaso.shared.FieldError
import com.circaso.shared.Resource
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.BoundsChange
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.RuleAdminRepository
import com.circaso.wallet.domain.repositories.RuleRepository
import com.circaso.wallet.domain.support.RuleTimeline
import java.time.Clock

/**
 * Staff (ADMIN): new bounds for every fee rate (decision 2026-11-08). Refused while any version in force now, or
 * scheduled to start, charges a rate outside them; those rules are named so they can be changed first. Ended versions
 * don't count: they priced payments under the bounds of their day.
 */
class UpdateFeeBounds(
    private val rules: RuleRepository,
    private val admin: RuleAdminRepository,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend operator fun invoke(minRateBps: Int, maxRateBps: Int): Resource<BoundsChange> {
        val problems =
            buildList {
                if (minRateBps !in 0..BPS_MAX) add(FieldError("minRateBps", "must be 0 to $BPS_MAX"))
                if (maxRateBps !in 0..BPS_MAX) add(FieldError("maxRateBps", "must be 0 to $BPS_MAX"))
                if (minRateBps > maxRateBps) add(FieldError("maxRateBps", "must be at least the minimum"))
            }
        if (problems.isNotEmpty()) return Resource.error(WalletError.RuleInvalid(problems))
        val now = RuleTimeline.now(clock)
        return transactor.attempt("fee-bounds.update") {
            val before = admin.lockFeeBounds()
            val outside =
                rules
                    .feeRules()
                    .filter { it.liveAt(now) && it.rateBps !in minRateBps..maxRateBps }
                    .map { it.ruleId }
                    .distinct()
            if (outside.isNotEmpty()) return@attempt Resource.error(WalletError.FeeBoundsExcludeRules(outside))
            Resource.success(BoundsChange(before, admin.updateFeeBounds(minRateBps, maxRateBps, now)))
        }
    }

    private companion object {
        const val BPS_MAX = 10_000
    }
}
