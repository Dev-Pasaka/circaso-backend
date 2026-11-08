package com.circaso.wallet.presentation.api

import com.circaso.shared.Resource
import com.circaso.shared.getOrThrow
import com.circaso.shared.map
import com.circaso.wallet.api.FeeAdmin
import com.circaso.wallet.api.FeeBoundsChange
import com.circaso.wallet.api.FeeRuleBook
import com.circaso.wallet.api.FeeRuleChange
import com.circaso.wallet.api.FeeRuleScope
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.api.LimitRuleBook
import com.circaso.wallet.api.LimitRuleChange
import com.circaso.wallet.api.LimitRuleScope
import com.circaso.wallet.api.LimitRuleTerms
import com.circaso.wallet.presentation.mappers.toFeeOps
import com.circaso.wallet.presentation.mappers.toLimitOps
import com.circaso.wallet.presentation.mappers.toOps
import com.circaso.wallet.presentation.services.FeeAdminService
import java.time.Instant
import java.util.UUID

/** wallet:api's fees-and-limits staff contract (decision 2026-11-08), bound for the ops module only. */
class FeeAdminImpl(private val fees: FeeAdminService) : FeeAdmin {
    override suspend fun feeRules(): FeeRuleBook = fees.feeRules().getOrThrow().toOps()

    override suspend fun createFeeRule(
        scope: FeeRuleScope,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<FeeRuleChange> = fees.createFeeRule(scope, terms, effectiveFrom, note).map { it.toFeeOps() }

    override suspend fun changeFeeRule(
        ruleId: UUID,
        baseVersion: Int,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<FeeRuleChange> =
        fees.changeFeeRule(ruleId, baseVersion, terms, effectiveFrom, note).map { it.toFeeOps() }

    override suspend fun endFeeRule(
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
    ): Resource<FeeRuleChange> = fees.endFeeRule(ruleId, baseVersion, effectiveTo).map { it.toFeeOps() }

    override suspend fun updateFeeBounds(minRateBps: Int, maxRateBps: Int): Resource<FeeBoundsChange> =
        fees.updateFeeBounds(minRateBps, maxRateBps).map { it.toOps() }

    override suspend fun limitRules(): LimitRuleBook = fees.limitRules().getOrThrow().toOps()

    override suspend fun createLimitRule(
        scope: LimitRuleScope,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<LimitRuleChange> = fees.createLimitRule(scope, terms, effectiveFrom, note).map { it.toLimitOps() }

    override suspend fun changeLimitRule(
        ruleId: UUID,
        baseVersion: Int,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<LimitRuleChange> =
        fees.changeLimitRule(ruleId, baseVersion, terms, effectiveFrom, note).map { it.toLimitOps() }

    override suspend fun endLimitRule(
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
    ): Resource<LimitRuleChange> = fees.endLimitRule(ruleId, baseVersion, effectiveTo).map { it.toLimitOps() }
}
