package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.presentation.dto.RampEngineSettingsResponse
import com.circaso.ops.presentation.dto.RampMerchantDto
import com.circaso.ops.presentation.dto.RampMerchantSignalsResponse
import com.circaso.ops.presentation.dto.RampMerchantTierRequest
import com.circaso.ops.presentation.dto.RampRoutingDecisionResponse
import com.circaso.ops.presentation.dto.RampRoutingListResponse
import com.circaso.ops.presentation.dto.RampSimulationRequest
import com.circaso.ops.presentation.dto.RampSimulationResponse
import com.circaso.ops.presentation.dto.UpdateRampEngineSettingsRequest
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.PAGED
import com.circaso.ops.presentation.mappers.TAG_RAMP
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.malformed
import com.circaso.ops.presentation.mappers.optionalMerchantId
import com.circaso.ops.presentation.mappers.pageLimit
import com.circaso.ops.presentation.mappers.parseMerchantId
import com.circaso.ops.presentation.mappers.parseUuid
import com.circaso.ops.presentation.mappers.query
import com.circaso.ops.presentation.mappers.rampDirection
import com.circaso.ops.presentation.mappers.rampInstant
import com.circaso.ops.presentation.mappers.rampOrderId
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.routingCursor
import com.circaso.ops.presentation.mappers.toCommand
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.mappers.toRoutingResponse
import com.circaso.ops.presentation.mappers.toUpdate
import com.circaso.ops.presentation.services.RampEngineService
import com.circaso.ramp.api.OpsRoutingQuery
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
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Clock

private const val REASON = "ops.reason_required"
private const val MERCHANT_NOT_FOUND = "ramp.merchant_not_found"
private const val SETTINGS_INVALID = "ramp.settings_invalid"
private const val NETWORK_AND_DIRECTION = "`?network=MAINNET|TESTNET` and `?direction=ON_RAMP|OFF_RAMP` are required."

private const val FORMULA =
    "Quality is `Q = wc·C + ws·S + wf·(1 − F)`; a merchant is drawn with the weight " +
        "`max(Q, floor)^alpha × (1 + hunger) × (1 + newcomer) × sizeFit`."

/** The network named by `?network=`, which these routes need. */
private suspend fun requiredNetwork(): Resource<Network> =
    networkFilter()?.let { Resource.success(it) } ?: malformed("network", "is required: MAINNET or TESTNET")

/** The `?network=` and `?direction=` a settings route is for. */
private suspend fun ApplicationCall.scope(): Resource<Pair<Network, String>> =
    requiredNetwork().flatMap { network -> rampDirection(query("direction")).map { network to it } }

/**
 * The merchant engine for staff (decision 2026-11-23): its settings per network and direction, the log of every
 * routing ("why this merchant"), a simulation of a draft of the settings, a merchant's type and limit, and a
 * merchant's ranking signals. FINANCE and ADMIN read (`ramp.read`); ADMIN changes (`ramp.manage`). Every change is
 * audited with a reason.
 */
fun Route.rampEngineController(
    engine: RampEngineService,
    settings: OpsSettings,
    clock: Clock,
) {
    route("/ops/ramp") {
        staffOnly(Permission.RAMP_READ.roles) {
            engineSettingsReads(engine)
            routingReads(engine, settings)
        }
        staffOnly(Permission.RAMP_MANAGE.roles) { engineWrites(engine, clock) }
    }
}

private fun Route.engineSettingsReads(engine: RampEngineService) {
    route("/engine-settings") {
        get {
            val result = call.scope().flatMap { (network, direction) -> engine.settings(network, direction) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<NoBody, RampEngineSettingsResponse>(
            "OPS · The merchant engine's settings",
            TAG_RAMP,
            listOf(MALFORMED, FORBIDDEN),
            notes =
                "${roles("FINANCE", "ADMIN")} $NETWORK_AND_DIRECTION The version in force, with who wrote it and " +
                    "why (both null on the seed). $FORMULA `mode`: ROUND_ROBIN (nothing scored), SHADOW (scored " +
                    "and logged, round-robin still picks), ACTIVE (the weighted draw picks). Who may take an " +
                    "order at all is the same hard filter in every mode.",
        )

        post("simulate") {
            val request = call.receive<RampSimulationRequest>()
            val result =
                call.scope().flatMap { (network, direction) ->
                    request.toCommand(network, direction).flatMap { engine.simulate(it) }
                }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<RampSimulationRequest, RampSimulationResponse>(
            "OPS · What a draft of the engine's settings would do",
            TAG_RAMP,
            listOf(SETTINGS_INVALID, "ramp.pricing_unavailable", MALFORMED, FORBIDDEN),
            notes =
                "${roles("FINANCE", "ADMIN")} $NETWORK_AND_DIRECTION Nothing is written, reserved or sent, and " +
                    "nothing is audited. The last real orders (`lastOrders`, default 50) or one order of " +
                    "`amountMinor` are routed again among the merchants **as they are now** (who is online, their " +
                    "float, their record), once under `settings` (the draft; left out: what is in force) and once " +
                    "under what is in force. `sharePercent` is the part of those orders each merchant would be " +
                    "picked first for: exact, not sampled (under ACTIVE a merchant's chance is their weight over " +
                    "the eligible weights; under ROUND_ROBIN and SHADOW round-robin picks, an equal turn each). " +
                    "It can't know who was online when the orders were really made.",
        )
    }
}

private fun Route.routingReads(engine: RampEngineService, settings: OpsSettings) {
    route("/routing") {
        get {
            val result =
                call.pageLimit(settings).flatMap { limit ->
                    call.routingQuery().flatMap { query ->
                        routingCursor(call.query("cursor"))
                            .flatMap { engine.decisions(query, it, limit) }
                            .map { it.toRoutingResponse(limit) }
                    }
                }
            call.respondDefault(result.toDefaultResponse { it })
        }.docs<NoBody, RampRoutingListResponse>(
            "OPS · Routing decisions",
            TAG_RAMP,
            listOf(MALFORMED, FORBIDDEN),
            notes =
                "${roles("FINANCE", "ADMIN")} Newest first. `?network=MAINNET|TESTNET` is required. Narrow with " +
                    "`?merchantId=` (the decisions that ended with that merchant), `?orderId=` (the routing of " +
                    "the purchase the order belongs to, whichever attempt it was), and `?from=&to=` (times like " +
                    "`2026-11-23T09:00:00Z`; `from` inclusive, `to` exclusive). $PAGED One row per purchase " +
                    "routed: `candidates` looked at, `eligible` of them, `attempts` made. Decisions are deleted " +
                    "after the settings' `logRetentionDays`.",
        )

        get("{decisionId}") {
            val result = parseUuid(call.parameters["decisionId"], "decisionId").flatMap { engine.decision(it) }
            call.respondDefault(result.toDefaultResponse { it.toResponse() })
        }.docs<NoBody, RampRoutingDecisionResponse>(
            "OPS · One routing decision: why this merchant",
            TAG_RAMP,
            listOf("ramp.routing_not_found", MALFORMED, FORBIDDEN),
            notes =
                "${roles("FINANCE", "ADMIN")} Every merchant looked at, with the inputs read about them, why they " +
                    "were left out (`excluded`) or how they scored (`score`: every factor of the weight), the " +
                    "failover order used (`ranked`), the engine's own draw (`engineRanked`: what it would have " +
                    "done, when the mode is SHADOW), and each step. $FORMULA `settingsVersion` is the version of " +
                    "the engine's settings that made the decision.",
        )
    }

    get("merchants/{merchantId}/signals") {
        val result =
            parseMerchantId(call.parameters["merchantId"]).flatMap { merchantId ->
                requiredNetwork().flatMap { engine.merchantSignals(merchantId, it) }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, RampMerchantSignalsResponse>(
        "OPS · A ramp merchant's ranking signals",
        TAG_RAMP,
        listOf(MERCHANT_NOT_FOUND, MALFORMED, FORBIDDEN),
        notes =
            "${roles("FINANCE", "ADMIN")} `?network=MAINNET|TESTNET` is required. What the engine scores the " +
                "merchant on, per direction, and the settings it is measured against: the same figures the " +
                "merchant sees on their portal. `stats` covers their last `statsOrders` finished orders within " +
                "`statsDays` days; only `counted` enters a rate, so a buyer who ignored or cancelled a prompt " +
                "never counts against them. `trades` is every trade they have completed on the network.",
    )
}

/** The query's network, merchant, order and times, each checked. */
private suspend fun ApplicationCall.routingQuery(): Resource<OpsRoutingQuery> =
    requiredNetwork().flatMap { network ->
        optionalMerchantId(query("merchantId")).flatMap { merchantId ->
            rampOrderId(query("orderId")).flatMap { orderId ->
                rampInstant(query("from"), "from").flatMap { from ->
                    rampInstant(query("to"), "to").map { to ->
                        OpsRoutingQuery(network, merchantId, orderId, from, to)
                    }
                }
            }
        }
    }

private fun Route.engineWrites(engine: RampEngineService, clock: Clock) {
    put("engine-settings") {
        val request = call.receive<UpdateRampEngineSettingsRequest>()
        val result =
            call.scope().flatMap { (network, direction) ->
                request.toUpdate(network, direction).flatMap { engine.updateSettings(call.actor(), it) }
            }
        call.respondDefault(result.map { it.after }.toDefaultResponse { it.toResponse() })
    }.docs<UpdateRampEngineSettingsRequest, RampEngineSettingsResponse>(
        "OPS · Change the merchant engine's settings",
        TAG_RAMP,
        listOf(REASON, SETTINGS_INVALID, "ramp.settings_changed", MALFORMED, FORBIDDEN),
        notes =
            "${roles("ADMIN")} $NETWORK_AND_DIRECTION Replaces the whole document with the next version; " +
                "audited with the reason and what changed, and the reason is kept on the version. A value out of " +
                "range is `$SETTINGS_INVALID` naming the field; a `version` that is no longer the one in force " +
                "is `ramp.settings_changed`. Both servers use the new settings within about ten seconds, with " +
                "no restart; a routing already made keeps the version that made it. Try a change with the " +
                "simulation first.",
    )

    put("merchants/{merchantId}/tier") {
        val request = call.receive<RampMerchantTierRequest>()
        val result =
            parseMerchantId(call.parameters["merchantId"]).flatMap { merchantId ->
                request.toUpdate(merchantId).flatMap { engine.setMerchantTier(call.actor(), it) }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse(clock.instant()) })
    }.docs<RampMerchantTierRequest, RampMerchantDto>(
        "OPS · Set a ramp merchant's type and order limit",
        TAG_RAMP,
        listOf(REASON, MERCHANT_NOT_FOUND, SETTINGS_INVALID, MALFORMED, FORBIDDEN),
        notes =
            "${roles("ADMIN")} Audited with the reason and the type and limit before and after. `merchantType`: " +
                "INDIVIDUAL or BUSINESS, which decides the largest single order the merchant is sent (the engine " +
                "settings' `individualLimitMinor` or `businessLimitMinor`). `orderLimitMinor` (KES cents) is a " +
                "limit of their own instead; left out or null, the type's default applies again.",
    )
}
