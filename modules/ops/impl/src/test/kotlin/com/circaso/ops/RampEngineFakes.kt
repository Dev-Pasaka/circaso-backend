package com.circaso.ops

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsEngineSettings
import com.circaso.ramp.api.OpsEngineSettingsChange
import com.circaso.ramp.api.OpsEngineSettingsVersion
import com.circaso.ramp.api.OpsMerchant
import com.circaso.ramp.api.OpsMerchantPerformance
import com.circaso.ramp.api.OpsMerchantSignals
import com.circaso.ramp.api.OpsMerchantStats
import com.circaso.ramp.api.OpsRoutingCandidate
import com.circaso.ramp.api.OpsRoutingCursor
import com.circaso.ramp.api.OpsRoutingDecision
import com.circaso.ramp.api.OpsRoutingQuery
import com.circaso.ramp.api.OpsRoutingScore
import com.circaso.ramp.api.OpsRoutingStep
import com.circaso.ramp.api.OpsSimulatedShare
import com.circaso.ramp.api.OpsSimulation
import com.circaso.ramp.api.OpsSimulationCommand
import com.circaso.ramp.api.RampEngineAdmin
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind
import com.circaso.shared.FieldError
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private data class EngineRefusal(
    override val code: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    override val message: String = code
}

/**
 * The merchant engine's staff contract in memory: it keeps what it is given and says what it was asked, so the ops
 * routes' own work (who may, the shapes, the reason, the audit line) is what a test sees. The ramp's own rules
 * (ranges, the version check) are the ramp's tests'; here a weight above 1 stands in for "the ramp refused it".
 */
class FakeRampEngineAdmin(private val merchants: ConcurrentHashMap<MerchantId, OpsMerchant>) : RampEngineAdmin {
    val rows = ConcurrentHashMap<Pair<Network, String>, OpsEngineSettingsVersion>()
    val updates = CopyOnWriteArrayList<Triple<OpsEngineSettingsVersion, String, UUID>>()
    val decisions = CopyOnWriteArrayList<OpsRoutingDecision>()
    val asked = CopyOnWriteArrayList<OpsRoutingQuery>()
    val simulated = CopyOnWriteArrayList<OpsSimulationCommand>()
    val tiers = CopyOnWriteArrayList<Triple<MerchantId, String, Long?>>()

    private fun row(network: Network, direction: String) =
        rows.getOrPut(network to direction) { OpsEngineSettingsVersion(network, direction, 1, SEED, null, null, AT) }

    override suspend fun settings(network: Network, direction: String): Resource<OpsEngineSettingsVersion> =
        Resource.success(row(network, direction))

    override suspend fun updateSettings(
        network: Network,
        direction: String,
        settings: OpsEngineSettings,
        version: Long?,
        reason: String,
        by: UUID,
    ): Resource<OpsEngineSettingsChange> {
        val before = row(network, direction)
        if (settings.weightCompletion > 1) {
            val details = ErrorDetails(fields = listOf(FieldError("weightCompletion", "must be 0 to 1")))
            return Resource.Error(EngineRefusal("ramp.settings_invalid", ErrorKind.INVALID, details))
        }
        if (version != null && version != before.version) {
            return Resource.Error(EngineRefusal("ramp.settings_changed", ErrorKind.CONFLICT))
        }
        val after =
            before.copy(
                version = before.version + 1,
                settings = settings,
                updatedBy = by.toString(),
                reason = reason,
            )
        rows[network to direction] = after
        updates += Triple(after, reason, by)
        return Resource.success(OpsEngineSettingsChange(before, after))
    }

    override suspend fun decisions(
        query: OpsRoutingQuery,
        after: OpsRoutingCursor?,
        limit: Int,
    ): Resource<List<OpsRoutingDecision>> {
        asked += query
        return Resource.success(
            decisions
                .filter { it.network == query.network }
                .filter { query.merchantId == null || it.merchantId == query.merchantId }
                .sortedByDescending { it.createdAt }
                .filter { after == null || it.createdAt < after.at }
                .take(limit),
        )
    }

    override suspend fun decision(decisionId: UUID): Resource<OpsRoutingDecision> =
        decisions.firstOrNull { it.decisionId == decisionId }?.let { Resource.success(it) }
            ?: Resource.Error(EngineRefusal("ramp.routing_not_found", ErrorKind.NOT_FOUND))

    override suspend fun simulate(request: OpsSimulationCommand): Resource<OpsSimulation> {
        simulated += request
        val mode = request.draft?.mode ?: SEED.mode
        return Resource.success(
            OpsSimulation(
                network = request.network,
                direction = request.direction,
                mode = mode,
                currentMode = SEED.mode,
                orders = request.lastOrders ?: 1,
                unroutable = 0,
                shares =
                    listOf(
                        OpsSimulatedShare(MERCHANT_A, "Crynce", 0.625, 0.5, 4, emptyMap(), 0.91),
                        OpsSimulatedShare(MERCHANT_B, "Bidii", 0.375, 0.5, 3, mapOf("FLOAT_SHORT" to 1), 0.6),
                    ),
            ),
        )
    }

    override suspend fun setMerchantTier(
        merchantId: MerchantId,
        merchantType: String,
        orderLimitMinor: Long?,
        by: UUID,
    ): Resource<OpsMerchant> {
        val merchant =
            merchants[merchantId]
                ?: return Resource.Error(EngineRefusal("ramp.merchant_not_found", ErrorKind.NOT_FOUND))
        val changed = merchant.copy(merchantType = merchantType, orderLimitMinor = orderLimitMinor)
        merchants[merchantId] = changed
        tiers += Triple(merchantId, merchantType, orderLimitMinor)
        return Resource.success(changed)
    }

    override suspend fun merchantSignals(merchantId: MerchantId, network: Network): Resource<OpsMerchantSignals> {
        val merchant =
            merchants[merchantId]
                ?: return Resource.Error(EngineRefusal("ramp.merchant_not_found", ErrorKind.NOT_FOUND))
        val buying = OpsMerchantPerformance("ON_RAMP", true, 3_000_000, STATS, 4, 20, 16, 300, 900, 50, 30)
        val selling = OpsMerchantPerformance("OFF_RAMP", false, 3_000_000, NO_STATS, 4, 20, 16, 300, 900, 50, 30)
        return Resource.success(OpsMerchantSignals(merchantId, network, merchant.merchantType, listOf(buying, selling)))
    }

    /** A routing that tried [MERCHANT_A] (refused) and ended with [MERCHANT_B], left one merchant out. */
    fun routed(at: Instant = AT, network: Network = Network.TESTNET): OpsRoutingDecision {
        val orderId = RampOrderId.new()
        val decision =
            OpsRoutingDecision(
                decisionId = UUID.randomUUID(),
                quoteId = QUOTE,
                orderId = orderId,
                merchantId = MERCHANT_B,
                network = network,
                direction = "ON_RAMP",
                fiatAmountMinor = 130_000,
                needMinor = 9_800_000,
                settingsVersion = 3,
                mode = "ACTIVE",
                outcome = "ROUTED",
                candidates =
                    listOf(
                        candidate(MERCHANT_A, null, 100_000_000, SCORE),
                        candidate(MERCHANT_B, null, 50_000_000, SCORE.copy(weight = 0.5)),
                        candidate(MERCHANT_C, "FLOAT_SHORT", 1_000_000, null),
                    ),
                ranked = listOf(MERCHANT_A, MERCHANT_B),
                engineRanked = listOf(MERCHANT_A, MERCHANT_B),
                steps =
                    listOf(
                        OpsRoutingStep(MERCHANT_A, FIRST_ORDER, "prompt_refused", at),
                        OpsRoutingStep(MERCHANT_B, orderId, "prompted", at.plusSeconds(1)),
                    ),
                createdAt = at,
            )
        decisions += decision
        return decision
    }

    private fun candidate(
        merchantId: MerchantId,
        excluded: String?,
        floatMinor: Long,
        score: OpsRoutingScore?,
    ) = OpsRoutingCandidate(
        merchantId = merchantId,
        excluded = excluded,
        approved = true,
        kycCleared = true,
        online = true,
        hasKeys = true,
        paymentsIssue = false,
        offersDirection = true,
        limitMinor = 3_000_000,
        floatMinor = floatMinor,
        lastAssignedAt = null,
        completedTrades = 4,
        stats = STATS.takeIf { excluded == null },
        score = score,
    )

    companion object {
        val AT: Instant = Instant.parse("2026-11-23T09:00:00Z")
        val MERCHANT_A = MerchantId(UUID.fromString("0193a000-0000-7000-8000-00000000000a"))
        val MERCHANT_B = MerchantId(UUID.fromString("0193a000-0000-7000-8000-00000000000b"))
        val MERCHANT_C = MerchantId(UUID.fromString("0193a000-0000-7000-8000-00000000000c"))
        val QUOTE: UUID = UUID.fromString("0193a000-0000-7000-8000-0000000000f0")
        val FIRST_ORDER = RampOrderId(UUID.fromString("0193a000-0000-7000-8000-0000000000e1"))
        val STATS = OpsMerchantStats(6, 4, 1, 0, 0, 5, 1, 12, 40)
        val NO_STATS = OpsMerchantStats(0, 0, 0, 0, 0, 0, 0, null, null)
        val SCORE = OpsRoutingScore(0.9, 1.0, 0.1, 0.93, 1.0, 0.4, 1.0, 2.604)

        /** The agreed seed, as the ramp hands it out on the testnet. */
        val SEED =
            OpsEngineSettings(
                mode = "ACTIVE",
                weightCompletion = 0.5,
                weightSpeed = 0.3,
                weightFailure = 0.2,
                priorStrength = 10.0,
                completionPrior = 0.9,
                failurePrior = 0.1,
                speedPrior = 0.9,
                qualityFloor = 0.1,
                alpha = 1.0,
                hungerMax = 1.0,
                hungerWindowMinutes = 120,
                newcomerMax = 0.5,
                newcomerTrades = 20,
                smallOrderMinor = 200_000,
                floatCover = 3.0,
                shareThreshold = 0.5,
                sharePenalty = 0.25,
                slaSeconds = 900,
                targetSeconds = 300,
                statsOrders = 50,
                statsDays = 30,
                maxAttempts = 3,
                individualLimitMinor = 3_000_000,
                businessLimitMinor = 15_000_000,
                logRetentionDays = 90,
            )
    }
}
