package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.usecases.ChangeFeeRule
import com.circaso.ops.domain.usecases.ChangeLimitRule
import com.circaso.ops.domain.usecases.CreateFeeRule
import com.circaso.ops.domain.usecases.CreateLimitRule
import com.circaso.ops.domain.usecases.EndFeeRule
import com.circaso.ops.domain.usecases.EndLimitRule
import com.circaso.ops.domain.usecases.ListFeeRules
import com.circaso.ops.domain.usecases.ListLimitRules
import com.circaso.ops.domain.usecases.UpdateFeeBounds
import com.circaso.shared.Resource
import com.circaso.wallet.api.FeeBoundsChange
import com.circaso.wallet.api.FeeRuleBook
import com.circaso.wallet.api.FeeRuleChange
import com.circaso.wallet.api.FeeRuleScope
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.api.LimitRuleBook
import com.circaso.wallet.api.LimitRuleChange
import com.circaso.wallet.api.LimitRuleScope
import com.circaso.wallet.api.LimitRuleTerms
import java.time.Instant
import java.util.UUID

/** Fees and limits: FINANCE and ADMIN read, ADMIN changes (decision 2026-11-08). */
class FeeService(
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
    suspend fun feeRules(): Resource<FeeRuleBook> = listFeeRules()

    suspend fun createFeeRule(
        actor: Actor,
        scope: FeeRuleScope,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        reason: String,
    ): Resource<FeeRuleChange> = createFeeRule.invoke(actor, scope, terms, effectiveFrom, reason)

    suspend fun changeFeeRule(
        actor: Actor,
        ruleId: UUID,
        baseVersion: Int,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        reason: String,
    ): Resource<FeeRuleChange> = changeFeeRule.invoke(actor, ruleId, baseVersion, terms, effectiveFrom, reason)

    suspend fun endFeeRule(
        actor: Actor,
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
        reason: String,
    ): Resource<FeeRuleChange> = endFeeRule.invoke(actor, ruleId, baseVersion, effectiveTo, reason)

    suspend fun updateFeeBounds(
        actor: Actor,
        minRateBps: Int,
        maxRateBps: Int,
        reason: String,
    ): Resource<FeeBoundsChange> = updateFeeBounds.invoke(actor, minRateBps, maxRateBps, reason)

    suspend fun limitRules(): Resource<LimitRuleBook> = listLimitRules()

    suspend fun createLimitRule(
        actor: Actor,
        scope: LimitRuleScope,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        reason: String,
    ): Resource<LimitRuleChange> = createLimitRule.invoke(actor, scope, terms, effectiveFrom, reason)

    suspend fun changeLimitRule(
        actor: Actor,
        ruleId: UUID,
        baseVersion: Int,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        reason: String,
    ): Resource<LimitRuleChange> = changeLimitRule.invoke(actor, ruleId, baseVersion, terms, effectiveFrom, reason)

    suspend fun endLimitRule(
        actor: Actor,
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
        reason: String,
    ): Resource<LimitRuleChange> = endLimitRule.invoke(actor, ruleId, baseVersion, effectiveTo, reason)
}
