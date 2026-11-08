package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.FeeRuleSet
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.RuleAdminRepository
import com.circaso.wallet.domain.repositories.RuleRepository
import java.time.Clock

/** Staff: every version of every fee rule and the bounds, read together (decision 2026-11-08). */
class ListFeeRules(
    private val rules: RuleRepository,
    private val admin: RuleAdminRepository,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Resource<FeeRuleSet> {
        val now = clock.instant()
        return Resource.success(
            transactor.run("fee-rules.list") { FeeRuleSet(now, admin.feeBounds(), rules.feeRules()) },
        )
    }
}
