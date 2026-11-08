package com.circaso.ops.domain.usecases

import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.Reasons
import com.circaso.ops.domain.support.RuleAudit
import com.circaso.shared.Resource
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
import java.time.Instant
import java.util.UUID

// Fees and limits (decision 2026-11-08): FINANCE and ADMIN read, ADMIN changes. The rules are the wallet's; ops only
// asks, and writes the audit line around every change with what the wallet says was there before and after.

class ListFeeRules(private val fees: FeeAdmin) {
    suspend operator fun invoke(): Resource<FeeRuleBook> = Resource.success(fees.feeRules())
}

class ListLimitRules(private val fees: FeeAdmin) {
    suspend operator fun invoke(): Resource<LimitRuleBook> = Resource.success(fees.limitRules())
}

/** A new fee rule; the reason is required, kept as the version's note and in the audit line. */
class CreateFeeRule(private val fees: FeeAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        scope: FeeRuleScope,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        reason: String,
    ): Resource<FeeRuleChange> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<FeeRuleChange>(
            actor,
            AuditAction.FEE_RULE_CREATE,
            targetId = null,
            reason = why,
            detail = RuleAudit.requested(terms, effectiveFrom),
            outcome = { RuleAudit.of(it) },
        ) { fees.createFeeRule(scope, terms, effectiveFrom, why) }
    }
}

/** A new version of a fee rule, built on [baseVersion]. */
class ChangeFeeRule(private val fees: FeeAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        ruleId: UUID,
        baseVersion: Int,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        reason: String,
    ): Resource<FeeRuleChange> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<FeeRuleChange>(
            actor,
            AuditAction.FEE_RULE_CHANGE,
            ruleId.toString(),
            reason = why,
            detail = RuleAudit.requested(terms, effectiveFrom),
            outcome = { RuleAudit.of(it) },
        ) { fees.changeFeeRule(ruleId, baseVersion, terms, effectiveFrom, why) }
    }
}

class EndFeeRule(private val fees: FeeAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
        reason: String,
    ): Resource<FeeRuleChange> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<FeeRuleChange>(
            actor,
            AuditAction.FEE_RULE_END,
            ruleId.toString(),
            reason = why,
            detail = RuleAudit.ending(baseVersion, effectiveTo),
            outcome = { RuleAudit.of(it) },
        ) { fees.endFeeRule(ruleId, baseVersion, effectiveTo) }
    }
}

/** ADMIN only, by the permission table: the bounds every fee rate stays within. */
class UpdateFeeBounds(private val fees: FeeAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        minRateBps: Int,
        maxRateBps: Int,
        reason: String,
    ): Resource<FeeBoundsChange> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<FeeBoundsChange>(
            actor,
            AuditAction.FEE_BOUNDS_UPDATE,
            targetId = null,
            reason = why,
            detail = "requested {$minRateBps-$maxRateBps bps}",
            outcome = { RuleAudit.of(it) },
        ) { fees.updateFeeBounds(minRateBps, maxRateBps) }
    }
}

class CreateLimitRule(private val fees: FeeAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        scope: LimitRuleScope,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        reason: String,
    ): Resource<LimitRuleChange> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<LimitRuleChange>(
            actor,
            AuditAction.LIMIT_RULE_CREATE,
            targetId = null,
            reason = why,
            detail = RuleAudit.requested(terms, effectiveFrom),
            outcome = { RuleAudit.of(it) },
        ) { fees.createLimitRule(scope, terms, effectiveFrom, why) }
    }
}

class ChangeLimitRule(private val fees: FeeAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        ruleId: UUID,
        baseVersion: Int,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        reason: String,
    ): Resource<LimitRuleChange> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<LimitRuleChange>(
            actor,
            AuditAction.LIMIT_RULE_CHANGE,
            ruleId.toString(),
            reason = why,
            detail = RuleAudit.requested(terms, effectiveFrom),
            outcome = { RuleAudit.of(it) },
        ) { fees.changeLimitRule(ruleId, baseVersion, terms, effectiveFrom, why) }
    }
}

class EndLimitRule(private val fees: FeeAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
        reason: String,
    ): Resource<LimitRuleChange> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<LimitRuleChange>(
            actor,
            AuditAction.LIMIT_RULE_END,
            ruleId.toString(),
            reason = why,
            detail = RuleAudit.ending(baseVersion, effectiveTo),
            outcome = { RuleAudit.of(it) },
        ) { fees.endLimitRule(ruleId, baseVersion, effectiveTo) }
    }
}
