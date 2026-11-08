package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.FeeRuleSet
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.RuleAdminRepository
import com.circaso.wallet.domain.repositories.RuleRepository
import com.circaso.wallet.domain.support.NetworkCosts
import com.circaso.wallet.domain.support.RuleTimeline
import java.time.Clock

/**
 * Staff: every version of every fee rule and the bounds, read together (decision 2026-11-08), with what the network
 * costs now on each chain so the editor can show the real fee: recent actual costs, else the last estimate quoted.
 */
class ListFeeRules(
    private val rules: RuleRepository,
    private val admin: RuleAdminRepository,
    private val transactor: Transactor,
    private val clock: Clock,
    private val networkCosts: NetworkCosts? = null,
) {
    suspend operator fun invoke(): Resource<FeeRuleSet> {
        val now = RuleTimeline.now(clock)
        val set = transactor.run("fee-rules.list") { FeeRuleSet(now, admin.feeBounds(), rules.feeRules()) }
        val costs = networkCosts ?: return Resource.success(set)
        return Resource.success(set.copy(networkCosts = costs.all(), estimates = costs.estimates()))
    }
}
