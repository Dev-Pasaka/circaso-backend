package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.LimitRuleSet
import com.circaso.wallet.domain.repositories.RuleRepository
import java.time.Clock

/** Staff: every version of every limit rule (decision 2026-11-08). */
class ListLimitRules(private val rules: RuleRepository, private val clock: Clock) {
    suspend operator fun invoke(): Resource<LimitRuleSet> =
        Resource.success(LimitRuleSet(clock.instant(), rules.limitRules()))
}
