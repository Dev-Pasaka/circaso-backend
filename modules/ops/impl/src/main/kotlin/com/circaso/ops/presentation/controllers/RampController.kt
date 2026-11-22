package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.presentation.dto.RampMerchantDto
import com.circaso.ops.presentation.dto.RampMerchantsResponse
import com.circaso.ops.presentation.dto.RampOrdersResponse
import com.circaso.ops.presentation.dto.RampPreviewAccessRequest
import com.circaso.ops.presentation.dto.RampPreviewAccessResponse
import com.circaso.ops.presentation.dto.RampSettingsDto
import com.circaso.ops.presentation.dto.ReasonRequest
import com.circaso.ops.presentation.dto.UpdateRampSettingsRequest
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.PAGED
import com.circaso.ops.presentation.mappers.TAG_RAMP
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.malformed
import com.circaso.ops.presentation.mappers.merchantCursor
import com.circaso.ops.presentation.mappers.orderCursor
import com.circaso.ops.presentation.mappers.pageLimit
import com.circaso.ops.presentation.mappers.parseEnum
import com.circaso.ops.presentation.mappers.parseMerchantId
import com.circaso.ops.presentation.mappers.parseUserId
import com.circaso.ops.presentation.mappers.query
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.mappers.toSettings
import com.circaso.ops.presentation.services.RampService
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.shared.networkFilter
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
import java.time.Clock

private const val REASON = "ops.reason_required"
private const val MERCHANT_NOT_FOUND = "ramp.merchant_not_found"
private const val NETWORK_REQUIRED = "is required: MAINNET or TESTNET"
private const val REVIEW = "REVIEW"

/** The network named by `?network=`, which these routes need: settings and the preview list are one network's. */
private suspend fun requiredNetwork(): Resource<Network> =
    networkFilter()?.let { Resource.success(it) } ?: malformed("network", NETWORK_REQUIRED)

/**
 * The ramp for staff (decision 2026-11-22): the partner merchants and their approval, each network's ramp settings
 * (which live in the database, not the environment), who may trade while the ramp is in PREVIEW, and the orders
 * waiting for a person. FINANCE and ADMIN read (`ramp.read`); ADMIN changes (`ramp.manage`). Every change is audited.
 */
fun Route.rampController(
    ramp: RampService,
    settings: OpsSettings,
    clock: Clock,
) {
    route("/ops/ramp") {
        staffOnly(Permission.RAMP_READ.roles) { rampReads(ramp, settings, clock) }
        staffOnly(Permission.RAMP_MANAGE.roles) { rampWrites(ramp, clock) }
    }
}

private fun Route.rampReads(
    ramp: RampService,
    settings: OpsSettings,
    clock: Clock,
) {
    get("merchants") {
        val result =
            call.pageLimit(settings).flatMap { limit ->
                merchantCursor(call.query("cursor"))
                    .flatMap { ramp.merchants(it, limit) }
                    .map { it.toResponse(limit, clock.instant()) }
            }
        call.respondDefault(result.toDefaultResponse { it })
    }.docs<NoBody, RampMerchantsResponse>(
        "OPS · The ramp's merchants",
        TAG_RAMP,
        listOf(MALFORMED, FORBIDDEN),
        notes =
            "${roles("FINANCE", "ADMIN")} Newest first. $PAGED `state`: NEW (signed in only), APPLIED (waiting for " +
                "approval), APPROVED, SUSPENDED. `wallets` are the float wallets the ops server made, one per " +
                "network. A merchant's payment credentials are sealed under their own PIN and never shown.",
    )

    get("settings") {
        val result = requiredNetwork().flatMap { ramp.settings(it) }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<NoBody, RampSettingsDto>(
        "OPS · A network's ramp settings",
        TAG_RAMP,
        listOf(MALFORMED, FORBIDDEN),
        notes =
            "${roles("FINANCE", "ADMIN")} `?network=MAINNET|TESTNET` is required. `state`: OFF (nobody), PREVIEW " +
                "(only people on the preview list), ON.",
    )

    get("preview-access") {
        val result = requiredNetwork().flatMap { network -> ramp.previewAccess(network).map { it.toResponse(network) } }
        call.respondDefault(result.toDefaultResponse { it })
    }.docs<NoBody, RampPreviewAccessResponse>(
        "OPS · Who may use the ramp while it is in PREVIEW",
        TAG_RAMP,
        listOf(MALFORMED, FORBIDDEN),
        notes = "${roles("FINANCE", "ADMIN")} `?network=MAINNET|TESTNET` is required.",
    )

    get("orders") {
        val state = call.query("state") ?: REVIEW
        val result =
            if (!state.equals(REVIEW, ignoreCase = true)) {
                malformed("state", "must be REVIEW")
            } else {
                call.pageLimit(settings).flatMap { limit ->
                    orderCursor(call.query("cursor"))
                        .flatMap { ramp.reviewOrders(networkFilter(), it, limit) }
                        .map { it.toResponse(limit) }
                }
            }
        call.respondDefault(result.toDefaultResponse { it })
    }.docs<NoBody, RampOrdersResponse>(
        "OPS · Ramp orders waiting for a person",
        TAG_RAMP,
        listOf(MALFORMED, FORBIDDEN),
        notes =
            "${roles("FINANCE", "ADMIN")} `?state=REVIEW` (the only state listed), oldest first, both networks " +
                "unless `?network=` names one. $PAGED `failureReason` says why it stopped (`payment_mismatch`, " +
                "`paid_after_expiry`, `release_refused`, `release_failed`, …). Its float stays reserved.",
    )
}

private fun Route.rampWrites(ramp: RampService, clock: Clock) {
    route("/merchants/{merchantId}") {
        post("approve") {
            val request = call.receive<ReasonRequest>()
            val result =
                parseMerchantId(call.parameters["merchantId"]).flatMap {
                    ramp.decide(call.actor(), it, approve = true, reason = request.reason)
                }
            call.respondDefault(result.toDefaultResponse { it.toResponse(clock.instant()) })
        }.docs<ReasonRequest, RampMerchantDto>(
            "OPS · Approve a ramp merchant",
            TAG_RAMP,
            listOf(REASON, MERCHANT_NOT_FOUND, "ramp.merchant_not_ready", MALFORMED, FORBIDDEN),
            notes =
                "${roles("ADMIN")} Audited with the reason. The merchant must have applied. The ops server then " +
                    "makes their float wallet within seconds.",
        )

        post("suspend") {
            val request = call.receive<ReasonRequest>()
            val result =
                parseMerchantId(call.parameters["merchantId"]).flatMap {
                    ramp.decide(call.actor(), it, approve = false, reason = request.reason)
                }
            call.respondDefault(result.toDefaultResponse { it.toResponse(clock.instant()) })
        }.docs<ReasonRequest, RampMerchantDto>(
            "OPS · Suspend a ramp merchant",
            TAG_RAMP,
            listOf(REASON, MERCHANT_NOT_FOUND, MALFORMED, FORBIDDEN),
            notes =
                "${roles("ADMIN")} Audited with the reason. The merchant goes offline at once and gets no new " +
                    "order; orders already open still finish.",
        )
    }

    put("settings") {
        val request = call.receive<UpdateRampSettingsRequest>()
        val result =
            requiredNetwork().flatMap { network ->
                request.toSettings(network).flatMap { ramp.updateSettings(call.actor(), it, request.reason) }
            }
        call.respondDefault(result.map { it.after }.toDefaultResponse { it.toDto() })
    }.docs<UpdateRampSettingsRequest, RampSettingsDto>(
        "OPS · Change a network's ramp settings",
        TAG_RAMP,
        listOf(REASON, "ramp.settings_invalid", "ramp.settings_changed", MALFORMED, FORBIDDEN),
        notes =
            "${roles("ADMIN")} `?network=` is required. Replaces the settings; audited with the reason and what " +
                "changed. The fee is 0 to 500 basis points and the merchant's share 0 to 10000; KYC can't be " +
                "switched off on MAINNET; every chain must be the network's own. A change applies to quotes made " +
                "after it. Both servers use the new settings within about ten seconds.",
    )

    post("preview-access") {
        val request = call.receive<RampPreviewAccessRequest>()
        val result =
            parseUserId(request.userId).flatMap { userId ->
                parseEnum<Network>(request.network, "network").flatMap { network ->
                    if (network == null) {
                        malformed("network", NETWORK_REQUIRED)
                    } else {
                        ramp
                            .setPreviewAccess(call.actor(), userId, network, request.enabled, request.reason)
                            .map { it.toResponse(network) }
                    }
                }
            }
        call.respondDefault(result.toDefaultResponse { it })
    }.docs<RampPreviewAccessRequest, RampPreviewAccessResponse>(
        "OPS · Let a person use the ramp in PREVIEW, or stop them",
        TAG_RAMP,
        listOf(REASON, "ops.user_not_found", MALFORMED, FORBIDDEN),
        notes = "${roles("ADMIN")} Audited with the reason. Answers the network's list afterwards.",
    )
}
