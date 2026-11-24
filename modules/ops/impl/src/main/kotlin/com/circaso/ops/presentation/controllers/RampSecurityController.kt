package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.presentation.dto.RampMerchantSecurityResponse
import com.circaso.ops.presentation.dto.RampSecuritySettingsResponse
import com.circaso.ops.presentation.dto.ReasonRequest
import com.circaso.ops.presentation.dto.UpdateRampSecuritySettingsRequest
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.TAG_RAMP
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.parseMerchantId
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.services.RampSecurityService
import com.circaso.shared.flatMap
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val NOT_FOUND = "ramp.merchant_not_found"

/**
 * Ramp merchant security for staff (2026-11-24): a merchant's withdrawals lock ("This wasn't me"), passkeys and recent
 * emails (`ramp.read`), lifting a lock and the `merchantPasskeyRequired` setting (`ramp.manage`, ADMIN), each change
 * with a reason and audited.
 */
fun Route.rampSecurityController(security: RampSecurityService) {
    route("/ops/ramp") {
        staffOnly(Permission.RAMP_READ.roles) {
            get("merchants/{merchantId}/security") {
                val result = parseMerchantId(call.parameters["merchantId"]).flatMap { security.merchant(it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, RampMerchantSecurityResponse>(
                "OPS · A ramp merchant's security",
                TAG_RAMP,
                listOf(NOT_FOUND, MALFORMED, FORBIDDEN),
                notes =
                    "${roles("FINANCE", "ADMIN")} Whether withdrawals are locked (`lockedByLink`: the merchant's " +
                        "own \"This wasn't me\" link; `lockedCancelled`: withdrawals it cancelled), the last unlock, " +
                        "their email choices, their passkeys (label, added, last used) and their last 50 emails " +
                        "(`kind`, `state`: PENDING, SENT, SKIPPED with why, e.g. `no_template` or `turned_off`, " +
                        "FAILED; `linkUsedAt` when that email's lock link was followed). Never an email's content.",
            )

            get("security-settings") {
                val result = security.settings()
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, RampSecuritySettingsResponse>(
                "OPS · Ramp merchant security settings",
                TAG_RAMP,
                listOf(FORBIDDEN),
                notes =
                    "${roles("FINANCE", "ADMIN")} `merchantPasskeyRequired`: a merchant without a passkey can't " +
                        "add or remove a withdrawal address, withdraw, change their PIN or change their payment or " +
                        "payout account until they add one (`ramp.passkey_required`). Off to start with.",
            )
        }
        staffOnly(Permission.RAMP_MANAGE.roles) {
            post("merchants/{merchantId}/unlock-withdrawals") {
                val request = call.receive<ReasonRequest>()
                val result =
                    parseMerchantId(call.parameters["merchantId"]).flatMap {
                        security.unlock(call.actor(), it, request.reason)
                    }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<ReasonRequest, RampMerchantSecurityResponse>(
                "OPS · Unlock a ramp merchant's withdrawals",
                TAG_RAMP,
                listOf(NOT_FOUND, "ramp.not_locked", "ops.reason_required", MALFORMED, FORBIDDEN),
                notes =
                    "${roles("ADMIN")} `{ reason }`. Lifts a lock set by the merchant's \"This wasn't me\" link, " +
                        "after you have spoken to them. Withdrawals it cancelled stay cancelled; addresses it froze " +
                        "stay frozen (the merchant removes them and adds them again, with the delay). Audited.",
            )

            put("security-settings") {
                val request = call.receive<UpdateRampSecuritySettingsRequest>()
                val result =
                    security.updateSettings(
                        call.actor(),
                        request.merchantPasskeyRequired,
                        request.version,
                        request.reason,
                    )
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<UpdateRampSecuritySettingsRequest, RampSecuritySettingsResponse>(
                "OPS · Change ramp merchant security settings",
                TAG_RAMP,
                listOf("ramp.settings_changed", "ops.reason_required", MALFORMED, FORBIDDEN),
                notes =
                    "${roles("ADMIN")} `{ merchantPasskeyRequired, version, reason }`; `version` as read " +
                        "(else `ramp.settings_changed`). Audited. In force at once.",
            )
        }
    }
}
