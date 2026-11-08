package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

// Fees and limits (decision 2026-11-08). Dollar figures are integer micro-dollars (`…UsdMicros`, 1 USD = 1_000_000),
// rates integer basis points (100 bps = 1 %), times ISO-8601 instants. A null scope field matches anything.

/** `kind`: INTERNAL | EXTERNAL; `senderType`, `receiverType`: PERSON | GROUP | SPACE. */
@Serializable
data class FeeScopeDto(
    val kind: String? = null,
    val senderType: String? = null,
    val receiverType: String? = null,
    val blockchain: String? = null,
    val asset: String? = null,
    val walletId: String? = null,
)

/** fee = max(rate × min(amount, capBase), minFee, network cost × (1 + margin) ÷ gasShare), in dollars, to the cent. */
@Serializable
data class FeeTermsDto(
    val rateBps: Int,
    val capBaseUsdMicros: Long = 10_000_000_000,
    val minFeeUsdMicros: Long? = null,
    val gasShareMaxBps: Int = 2_500,
    val gasMarginBps: Int = 2_000,
)

@Serializable
data class FeeRuleVersionDto(
    val ruleId: String,
    val version: Int,
    val scope: FeeScopeDto,
    val terms: FeeTermsDto,
    val effectiveFrom: String,
    val effectiveTo: String? = null,
    val note: String? = null,
    val createdAt: String? = null,
)

/** `status`: ACTIVE (a version in force), SCHEDULED (one starts later), ENDED. `versions` newest first. */
@Serializable
data class FeeRuleDto(
    val ruleId: String,
    val status: String,
    val current: Int? = null,
    val versions: List<FeeRuleVersionDto>,
)

@Serializable
data class FeeBoundsDto(val minRateBps: Int, val maxRateBps: Int, val updatedAt: String)

@Serializable
data class FeeRulesResponse(val at: String, val bounds: FeeBoundsDto, val rules: List<FeeRuleDto>)

@Serializable
data class FeeRuleChangeResponse(val before: FeeRuleVersionDto? = null, val after: FeeRuleVersionDto)

@Serializable
data class FeeBoundsChangeResponse(val before: FeeBoundsDto, val after: FeeBoundsDto)

/** `effectiveFrom` null starts now; it may be scheduled, never in the past. `reason` (3-500) is the version's note. */
@Serializable
data class CreateFeeRuleRequest(
    val scope: FeeScopeDto,
    val terms: FeeTermsDto,
    val effectiveFrom: String? = null,
    val reason: String,
)

/** `baseVersion`: the version this edit started from; answers `wallet.rule_changed` if it isn't the latest. */
@Serializable
data class ChangeFeeRuleRequest(
    val baseVersion: Int,
    val terms: FeeTermsDto,
    val effectiveFrom: String? = null,
    val reason: String,
)

/** `effectiveTo` null ends the rule now. */
@Serializable
data class EndRuleRequest(val baseVersion: Int, val effectiveTo: String? = null, val reason: String)

@Serializable
data class UpdateFeeBoundsRequest(val minRateBps: Int, val maxRateBps: Int, val reason: String)

/** `ownerType`: PERSON | GROUP | SPACE. */
@Serializable
data class LimitScopeDto(val ownerType: String? = null, val walletId: String? = null, val asset: String? = null)

/** A null cap is no cap; the window cap counts what leaves the wallet, fees included, over `windowHours`. */
@Serializable
data class LimitTermsDto(
    val perPaymentMaxUsdMicros: Long? = null,
    val dailyMaxUsdMicros: Long? = null,
    val windowHours: Int = 24,
)

@Serializable
data class LimitRuleVersionDto(
    val ruleId: String,
    val version: Int,
    val scope: LimitScopeDto,
    val terms: LimitTermsDto,
    val effectiveFrom: String,
    val effectiveTo: String? = null,
    val note: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class LimitRuleDto(
    val ruleId: String,
    val status: String,
    val current: Int? = null,
    val versions: List<LimitRuleVersionDto>,
)

@Serializable
data class LimitRulesResponse(val at: String, val rules: List<LimitRuleDto>)

@Serializable
data class LimitRuleChangeResponse(val before: LimitRuleVersionDto? = null, val after: LimitRuleVersionDto)

@Serializable
data class CreateLimitRuleRequest(
    val scope: LimitScopeDto,
    val terms: LimitTermsDto,
    val effectiveFrom: String? = null,
    val reason: String,
)

@Serializable
data class ChangeLimitRuleRequest(
    val baseVersion: Int,
    val terms: LimitTermsDto,
    val effectiveFrom: String? = null,
    val reason: String,
)
