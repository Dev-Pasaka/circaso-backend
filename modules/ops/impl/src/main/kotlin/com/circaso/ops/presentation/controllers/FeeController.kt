package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.presentation.dto.ChangeFeeRuleRequest
import com.circaso.ops.presentation.dto.ChangeLimitRuleRequest
import com.circaso.ops.presentation.dto.CreateFeeRuleRequest
import com.circaso.ops.presentation.dto.CreateLimitRuleRequest
import com.circaso.ops.presentation.dto.EndRuleRequest
import com.circaso.ops.presentation.dto.FeeBoundsChangeResponse
import com.circaso.ops.presentation.dto.FeeBoundsDto
import com.circaso.ops.presentation.dto.FeeRuleChangeResponse
import com.circaso.ops.presentation.dto.FeeRulesResponse
import com.circaso.ops.presentation.dto.LimitRuleChangeResponse
import com.circaso.ops.presentation.dto.LimitRulesResponse
import com.circaso.ops.presentation.dto.UpdateFeeBoundsRequest
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.TAG_FEES
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.parseUuid
import com.circaso.ops.presentation.mappers.parseWhen
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.mappers.toScope
import com.circaso.ops.presentation.mappers.toTerms
import com.circaso.ops.presentation.services.FeeService
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val REASON = "ops.reason_required"
private const val VERSIONED =
    "A change adds a version and never overwrites one: the latest version ends where the new one starts, in one " +
        "transaction under a lock. `effectiveFrom` null is now; it may be scheduled up to 366 days ahead, never in " +
        "the past. Quotes already shown keep their fee; every quote priced after the new version starts uses it. " +
        "`reason` (3 to 500 characters) is required and kept as the version's note. Audited with before and after."
private val RULE_WRITE_ERRORS =
    listOf(
        "wallet.rule_not_found",
        "wallet.rule_changed",
        "wallet.rule_ended",
        "wallet.rule_change_pending",
        "wallet.rule_invalid",
        REASON,
        MALFORMED,
        FORBIDDEN,
    )

/** Fees and limits (decision 2026-11-08): FINANCE and ADMIN read, ADMIN changes. The rules are the wallet's. */
fun Route.feeController(fees: FeeService) {
    route("/ops/fees") {
        staffOnly(Permission.FEES_READ.roles) { feeReads(fees) }
        staffOnly(Permission.FEES_MANAGE.roles) { feeWrites(fees) }
    }
    route("/ops/limits/rules") {
        staffOnly(Permission.FEES_READ.roles) { limitReads(fees) }
        staffOnly(Permission.FEES_MANAGE.roles) { limitWrites(fees) }
    }
}

private fun Route.feeReads(fees: FeeService) {
    get("rules") {
        call.respondDefault(fees.feeRules().toDefaultResponse { it.toResponse() })
    }.docs<NoBody, FeeRulesResponse>(
        "OPS · Fee rules, every version",
        TAG_FEES,
        listOf(FORBIDDEN),
        "${roles("FINANCE", "ADMIN")} Each rule with its status (ACTIVE, SCHEDULED, ENDED), the version in force " +
            "(`current`) and every version newest first; and the bounds every rate stays within. Rates in basis " +
            "points, dollars in micro-dollars.",
    )

    get("bounds") {
        call.respondDefault(fees.feeRules().toDefaultResponse { it.bounds.toDto() })
    }.docs<NoBody, FeeBoundsDto>("OPS · The fee bounds", TAG_FEES, listOf(FORBIDDEN), roles("FINANCE", "ADMIN"))
}

private fun Route.feeWrites(fees: FeeService) {
    post("rules") {
        val request = call.receive<CreateFeeRuleRequest>()
        val result =
            request.scope.toScope().flatMap { scope ->
                parseWhen(request.effectiveFrom, "effectiveFrom").flatMap { from ->
                    fees.createFeeRule(call.actor(), scope, request.terms.toTerms(), from, request.reason)
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<CreateFeeRuleRequest, FeeRuleChangeResponse>(
        "OPS · Create a fee rule",
        TAG_FEES,
        listOf("wallet.rule_scope_taken", "wallet.fee_rate_out_of_bounds", "wallet.rule_invalid", REASON, MALFORMED),
        "${roles("ADMIN")} Version 1 of a new rule. Refused when a live rule already covers exactly this scope " +
            "(change that one instead). $VERSIONED",
    )

    post("rules/{ruleId}/versions") {
        val request = call.receive<ChangeFeeRuleRequest>()
        val result =
            parseUuid(call.parameters["ruleId"], "ruleId").flatMap { ruleId ->
                parseWhen(request.effectiveFrom, "effectiveFrom").flatMap { from ->
                    fees.changeFeeRule(
                        call.actor(),
                        ruleId,
                        request.baseVersion,
                        request.terms.toTerms(),
                        from,
                        request.reason,
                    )
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<ChangeFeeRuleRequest, FeeRuleChangeResponse>(
        "OPS · Change a fee rule (a new version)",
        TAG_FEES,
        RULE_WRITE_ERRORS + "wallet.fee_rate_out_of_bounds",
        "${roles("ADMIN")} `baseVersion` is the version the edit started from; another admin's change since answers " +
            "`wallet.rule_changed`. The scope stays the rule's. $VERSIONED",
    )

    post("rules/{ruleId}/end") {
        val request = call.receive<EndRuleRequest>()
        val result =
            parseUuid(call.parameters["ruleId"], "ruleId").flatMap { ruleId ->
                parseWhen(request.effectiveTo, "effectiveTo").flatMap { to ->
                    fees.endFeeRule(call.actor(), ruleId, request.baseVersion, to, request.reason)
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<EndRuleRequest, FeeRuleChangeResponse>(
        "OPS · End a fee rule",
        TAG_FEES,
        RULE_WRITE_ERRORS,
        "${roles("ADMIN")} Sets the latest version's end (`effectiveTo`, null for now); nothing is deleted. The " +
            "payments it matched fall to the next most specific rule. Audited with before and after.",
    )

    patch("bounds") {
        val request = call.receive<UpdateFeeBoundsRequest>()
        val result = fees.updateFeeBounds(call.actor(), request.minRateBps, request.maxRateBps, request.reason)
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<UpdateFeeBoundsRequest, FeeBoundsChangeResponse>(
        "OPS · Change the fee bounds",
        TAG_FEES,
        listOf("wallet.fee_bounds_exclude_rules", "wallet.rule_invalid", REASON, MALFORMED, FORBIDDEN),
        "${roles("ADMIN")} 0 to 10 000 bps. Refused while a rule in force now or scheduled charges a rate outside " +
            "the new bounds; the error names those rules. Audited with before and after.",
    )
}

private fun Route.limitReads(fees: FeeService) {
    get {
        call.respondDefault(fees.limitRules().toDefaultResponse { it.toResponse() })
    }.docs<NoBody, LimitRulesResponse>(
        "OPS · Limit rules, every version",
        TAG_FEES,
        listOf(FORBIDDEN),
        "${roles("FINANCE", "ADMIN")} Per owner type, wallet and coin: a cap on one payment and on what leaves the " +
            "wallet, fees included, over a rolling window. Dollars in micro-dollars; a null cap is no cap.",
    )
}

private fun Route.limitWrites(fees: FeeService) {
    post {
        val request = call.receive<CreateLimitRuleRequest>()
        val result =
            request.scope.toScope().flatMap { scope ->
                parseWhen(request.effectiveFrom, "effectiveFrom").flatMap { from ->
                    fees.createLimitRule(call.actor(), scope, request.terms.toTerms(), from, request.reason)
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<CreateLimitRuleRequest, LimitRuleChangeResponse>(
        "OPS · Create a limit rule",
        TAG_FEES,
        listOf("wallet.rule_scope_taken", "wallet.rule_invalid", REASON, MALFORMED, FORBIDDEN),
        "${roles("ADMIN")} $VERSIONED",
    )

    post("{ruleId}/versions") {
        val request = call.receive<ChangeLimitRuleRequest>()
        val result =
            parseUuid(call.parameters["ruleId"], "ruleId").flatMap { ruleId ->
                parseWhen(request.effectiveFrom, "effectiveFrom").flatMap { from ->
                    fees.changeLimitRule(
                        call.actor(),
                        ruleId,
                        request.baseVersion,
                        request.terms.toTerms(),
                        from,
                        request.reason,
                    )
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<ChangeLimitRuleRequest, LimitRuleChangeResponse>(
        "OPS · Change a limit rule (a new version)",
        TAG_FEES,
        RULE_WRITE_ERRORS,
        "${roles("ADMIN")} `baseVersion` as for fees. Payments already prepared keep counting as they were. $VERSIONED",
    )

    post("{ruleId}/end") {
        val request = call.receive<EndRuleRequest>()
        val result =
            parseUuid(call.parameters["ruleId"], "ruleId").flatMap { ruleId ->
                parseWhen(request.effectiveTo, "effectiveTo").flatMap { to ->
                    fees.endLimitRule(call.actor(), ruleId, request.baseVersion, to, request.reason)
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<EndRuleRequest, LimitRuleChangeResponse>(
        "OPS · End a limit rule",
        TAG_FEES,
        RULE_WRITE_ERRORS,
        "${roles("ADMIN")} Sets the latest version's end; nothing is deleted. Audited with before and after.",
    )
}
