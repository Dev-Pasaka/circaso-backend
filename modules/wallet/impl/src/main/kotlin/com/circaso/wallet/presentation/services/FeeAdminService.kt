package com.circaso.wallet.presentation.services

import com.circaso.shared.Resource
import com.circaso.wallet.api.FeeRuleScope
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.api.LimitRuleScope
import com.circaso.wallet.api.LimitRuleTerms
import com.circaso.wallet.domain.models.BoundsChange
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.FeeRuleSet
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.LimitRuleSet
import com.circaso.wallet.domain.models.RuleChange
import com.circaso.wallet.domain.usecases.ChangeFeeRule
import com.circaso.wallet.domain.usecases.ChangeLimitRule
import com.circaso.wallet.domain.usecases.CreateFeeRule
import com.circaso.wallet.domain.usecases.CreateLimitRule
import com.circaso.wallet.domain.usecases.EndFeeRule
import com.circaso.wallet.domain.usecases.EndLimitRule
import com.circaso.wallet.domain.usecases.ListFeeRules
import com.circaso.wallet.domain.usecases.ListLimitRules
import com.circaso.wallet.domain.usecases.UpdateFeeBounds
import java.time.Instant
import java.util.UUID

/** Staff on the ops gateway (decision 2026-11-08): fee and limit rules, versioned, and the fee bounds. */
class FeeAdminService(
    private val listFeeRules: ListFeeRules,
    private val createFeeRule: CreateFeeRule,
    private val changeFeeRule: ChangeFeeRule,
    private val endFeeRule: EndFeeRule,
    private val updateFeeBounds: UpdateFeeBounds,
    private val listLimitRules: ListLimitRules,
    private val createLimitRule: CreateLimitRule,
    private val changeLimitRule: ChangeLimitRule,
    private val endLimitRule: EndLimitRule,
) {
    suspend fun feeRules(): Resource<FeeRuleSet> = listFeeRules()

    suspend fun createFeeRule(
        scope: FeeRuleScope,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<RuleChange<FeeRule>> = createFeeRule.invoke(scope, terms, effectiveFrom, note)

    suspend fun changeFeeRule(
        ruleId: UUID,
        baseVersion: Int,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<RuleChange<FeeRule>> = changeFeeRule.invoke(ruleId, baseVersion, terms, effectiveFrom, note)

    suspend fun endFeeRule(
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
    ): Resource<RuleChange<FeeRule>> = endFeeRule.invoke(ruleId, baseVersion, effectiveTo)

    suspend fun updateFeeBounds(minRateBps: Int, maxRateBps: Int): Resource<BoundsChange> =
        updateFeeBounds.invoke(minRateBps, maxRateBps)

    suspend fun limitRules(): Resource<LimitRuleSet> = listLimitRules()

    suspend fun createLimitRule(
        scope: LimitRuleScope,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<RuleChange<LimitRule>> = createLimitRule.invoke(scope, terms, effectiveFrom, note)

    suspend fun changeLimitRule(
        ruleId: UUID,
        baseVersion: Int,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<RuleChange<LimitRule>> = changeLimitRule.invoke(ruleId, baseVersion, terms, effectiveFrom, note)

    suspend fun endLimitRule(
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
    ): Resource<RuleChange<LimitRule>> = endLimitRule.invoke(ruleId, baseVersion, effectiveTo)
}
