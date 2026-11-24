package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.presentation.dto.RampPricingResponse
import com.circaso.ops.presentation.dto.RampRatesResponse
import com.circaso.ops.presentation.dto.UpdateRampPricingRequest
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.TAG_RAMP
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.malformed
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.mappers.toUpdate
import com.circaso.ops.presentation.services.RampRatesService
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route

private const val SETTINGS_INVALID = "ramp.settings_invalid"
private const val NETWORK = "`?network=MAINNET|TESTNET` is required."

private const val HOW =
    "Buy rate (the user buys USDC with KES) = reference × (1 + buySpreadBps / 10 000); sell rate (the user sells " +
        "USDC for KES) = reference × (1 − sellSpreadBps / 10 000). In MANUAL mode the rates are staff's own until " +
        "`manualExpiresAt`. Rates are KES per USDC."

private suspend fun pricingNetwork(): Resource<Network> =
    networkFilter()?.let { Resource.success(it) } ?: malformed("network", "is required: MAINNET or TESTNET")

/**
 * The ramp's rates for staff (decision 2026-11-24): each network's pricing settings (a spread on each side of the
 * reference rate, or exact manual rates with an expiry) and the rates in force now. FINANCE and ADMIN read
 * (`ramp.read`); ADMIN changes (`ramp.manage`), with a reason, audited by name.
 */
fun Route.rampRatesController(rates: RampRatesService) {
    route("/ops/ramp") {
        staffOnly(Permission.RAMP_READ.roles) { ratesReads(rates) }
        staffOnly(Permission.RAMP_MANAGE.roles) { ratesWrites(rates) }
    }
}

private fun Route.ratesReads(rates: RampRatesService) {
    get("pricing") {
        val result = pricingNetwork().flatMap { rates.pricing(it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, RampPricingResponse>(
        "OPS · A network's pricing settings (the rates)",
        TAG_RAMP,
        listOf(MALFORMED, FORBIDDEN),
        notes =
            "${roles("FINANCE", "ADMIN")} $NETWORK $HOW Spreads start at 0 (the reference on both sides). " +
                "`maxDeviationBps`: how far a manual rate may be from the reference when saved; " +
                "`maxReferenceAgeHours`: a reference older than this pauses reference-based quoting.",
    )

    get("rates") {
        val result = pricingNetwork().flatMap { rates.rates(it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, RampRatesResponse>(
        "OPS · The ramp's buy and sell rates now",
        TAG_RAMP,
        listOf(MALFORMED, FORBIDDEN),
        notes =
            "${roles("FINANCE", "ADMIN")} $NETWORK The reference rate as last read (open.er-api.com, about daily), " +
                "when it was published and its age; `referenceStale` when it is older than `maxReferenceAgeHours` " +
                "(or missing). `buy` and `sell` are the rates quotes are made at now, or null with `paused`: why " +
                "quoting that side stops with `ramp.pricing_unavailable` (`NO_REFERENCE`, `REFERENCE_STALE`, " +
                "`MANUAL_MISSING`, `MANUAL_EXPIRED`). `spreadBps` is the margin over the reference.",
    )
}

private fun Route.ratesWrites(rates: RampRatesService) {
    put("pricing") {
        val request = call.receive<UpdateRampPricingRequest>()
        val result =
            pricingNetwork().flatMap { network ->
                request.toUpdate(network).flatMap { rates.updatePricing(call.actor(), it) }
            }
        call.respondDefault(result.map { it.after }.toDefaultResponse { it.toResponse() })
    }.docs<UpdateRampPricingRequest, RampPricingResponse>(
        "OPS · Change a network's pricing settings (the rates)",
        TAG_RAMP,
        listOf("ops.reason_required", SETTINGS_INVALID, "ramp.settings_changed", MALFORMED, FORBIDDEN),
        notes =
            "${roles("ADMIN")} $NETWORK $HOW Replaces the whole settings with the next version; audited with the " +
                "reason and what changed. Spreads are 0 to 2000 basis points. MANUAL needs `manualBuyRate` at or " +
                "above `manualSellRate`, both within `maxDeviationBps` of the reference rate now (else " +
                "`$SETTINGS_INVALID` naming the field), and `manualExpiresAt` (left out: 24 hours from now; at " +
                "most 30 days). Once it passes, quoting stops until staff set new rates: never a silent fallback. " +
                "A `version` that is no longer the one in force is `ramp.settings_changed`. In force everywhere " +
                "within about ten seconds; a quote already made keeps its rate.",
    )
}
