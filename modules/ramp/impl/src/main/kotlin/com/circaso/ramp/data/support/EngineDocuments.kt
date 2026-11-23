package com.circaso.ramp.data.support

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.CandidateFacts
import com.circaso.ramp.domain.models.EngineMode
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.Exclusion
import com.circaso.ramp.domain.models.MerchantStats
import com.circaso.ramp.domain.models.RoutedCandidate
import com.circaso.ramp.domain.models.RoutingPlan
import com.circaso.ramp.domain.models.RoutingStep
import com.circaso.ramp.domain.models.Score
import com.circaso.shared.CircasoJson
import com.circaso.shared.Network
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.util.UUID

/**
 * The JSON the engine keeps in the database: its settings document, and a routing decision's candidates, ranking and
 * steps. Each is a typed class, read with unknown keys ignored and missing ones defaulted, so a document written by an
 * older or a newer build still opens: a setting added later reads as its default from a row written before it.
 */
object EngineDocuments {
    fun encode(settings: EngineSettings): String =
        CircasoJson.encodeToString(SettingsDocument.serializer(), SettingsDocument.of(settings))

    /**
     * The settings in [json]. A number the document doesn't have takes the built-in default; a mode it doesn't have
     * (or one this build doesn't know) takes [network]'s own default.
     */
    fun decode(json: String, network: Network): EngineSettings {
        val stored = CircasoJson.decodeFromString(SettingsDocument.serializer(), json)
        val mode = EngineMode.entries.firstOrNull { it.name == stored.mode } ?: EngineSettings.defaults(network).mode
        return stored.toSettings(mode)
    }

    fun encodeCandidates(candidates: List<RoutedCandidate>): String =
        CircasoJson.encodeToString(
            ListSerializer(CandidateDocument.serializer()),
            candidates.map(CandidateDocument::of),
        )

    fun encodeRanked(plan: RoutingPlan): String =
        CircasoJson.encodeToString(
            RankedDocument.serializer(),
            RankedDocument(plan.ranked.map { it.toString() }, plan.engineRanked?.map { it.toString() }),
        )

    fun encodeSteps(steps: List<RoutingStep>): String =
        CircasoJson.encodeToString(ListSerializer(StepDocument.serializer()), steps.map(StepDocument::of))

    fun decodePlan(
        mode: EngineMode,
        candidates: String,
        ranked: String,
    ): RoutingPlan {
        val order = CircasoJson.decodeFromString(RankedDocument.serializer(), ranked)
        return RoutingPlan(
            mode = mode,
            candidates =
                CircasoJson
                    .decodeFromString(ListSerializer(CandidateDocument.serializer()), candidates)
                    .map { it.toCandidate() },
            ranked = order.used.map(::merchant),
            engineRanked = order.engine?.map(::merchant),
        )
    }

    fun decodeSteps(steps: String): List<RoutingStep> =
        CircasoJson.decodeFromString(ListSerializer(StepDocument.serializer()), steps).map { it.toStep() }

    private fun merchant(id: String): MerchantId = MerchantId(UUID.fromString(id))
}

/** `ramp.engine_settings.document`. The defaults are the built-in seed, so a field added later is never missing. */
@Serializable
internal data class SettingsDocument(
    val mode: String? = null,
    val weightCompletion: Double = 0.5,
    val weightSpeed: Double = 0.3,
    val weightFailure: Double = 0.2,
    val priorStrength: Double = 10.0,
    val completionPrior: Double = 0.9,
    val failurePrior: Double = 0.1,
    val speedPrior: Double = 0.9,
    val qualityFloor: Double = 0.1,
    val alpha: Double = 1.0,
    val hungerMax: Double = 1.0,
    val hungerWindowMinutes: Int = 120,
    val newcomerMax: Double = 0.5,
    val newcomerTrades: Int = 20,
    val smallOrderMinor: Long = 200_000,
    val floatCover: Double = 3.0,
    val shareThreshold: Double = 0.5,
    val sharePenalty: Double = 0.25,
    val slaSeconds: Int = 900,
    val targetSeconds: Int = 300,
    val statsOrders: Int = 50,
    val statsDays: Int = 30,
    val maxAttempts: Int = 3,
    val individualLimitMinor: Long = 3_000_000,
    val businessLimitMinor: Long = 15_000_000,
    val logRetentionDays: Int = 90,
) {
    fun toSettings(mode: EngineMode): EngineSettings =
        EngineSettings(
            mode = mode,
            weightCompletion = weightCompletion,
            weightSpeed = weightSpeed,
            weightFailure = weightFailure,
            priorStrength = priorStrength,
            completionPrior = completionPrior,
            failurePrior = failurePrior,
            speedPrior = speedPrior,
            qualityFloor = qualityFloor,
            alpha = alpha,
            hungerMax = hungerMax,
            hungerWindowMinutes = hungerWindowMinutes,
            newcomerMax = newcomerMax,
            newcomerTrades = newcomerTrades,
            smallOrderMinor = smallOrderMinor,
            floatCover = floatCover,
            shareThreshold = shareThreshold,
            sharePenalty = sharePenalty,
            slaSeconds = slaSeconds,
            targetSeconds = targetSeconds,
            statsOrders = statsOrders,
            statsDays = statsDays,
            maxAttempts = maxAttempts,
            individualLimitMinor = individualLimitMinor,
            businessLimitMinor = businessLimitMinor,
            logRetentionDays = logRetentionDays,
        )

    companion object {
        fun of(settings: EngineSettings) =
            SettingsDocument(
                mode = settings.mode.name,
                weightCompletion = settings.weightCompletion,
                weightSpeed = settings.weightSpeed,
                weightFailure = settings.weightFailure,
                priorStrength = settings.priorStrength,
                completionPrior = settings.completionPrior,
                failurePrior = settings.failurePrior,
                speedPrior = settings.speedPrior,
                qualityFloor = settings.qualityFloor,
                alpha = settings.alpha,
                hungerMax = settings.hungerMax,
                hungerWindowMinutes = settings.hungerWindowMinutes,
                newcomerMax = settings.newcomerMax,
                newcomerTrades = settings.newcomerTrades,
                smallOrderMinor = settings.smallOrderMinor,
                floatCover = settings.floatCover,
                shareThreshold = settings.shareThreshold,
                sharePenalty = settings.sharePenalty,
                slaSeconds = settings.slaSeconds,
                targetSeconds = settings.targetSeconds,
                statsOrders = settings.statsOrders,
                statsDays = settings.statsDays,
                maxAttempts = settings.maxAttempts,
                individualLimitMinor = settings.individualLimitMinor,
                businessLimitMinor = settings.businessLimitMinor,
                logRetentionDays = settings.logRetentionDays,
            )
    }
}

/** A merchant's record as the engine read it for one routing. */
@Serializable
internal data class StatsDocument(
    val finished: Int,
    val completed: Int,
    val merchantFailures: Int,
    val merchantExpiries: Int,
    val reversals: Int,
    val medianHandlingSeconds: Long? = null,
    val p90HandlingSeconds: Long? = null,
)

/** The inputs of one merchant's eligibility and score. */
@Serializable
internal data class InputsDocument(
    val approved: Boolean,
    val kycCleared: Boolean,
    val online: Boolean,
    val hasKeys: Boolean,
    val paymentsIssue: Boolean,
    val offersDirection: Boolean,
    val limitMinor: Long,
    val floatMinor: Long? = null,
    val lastAssignedAt: String? = null,
    val completedTrades: Int = 0,
    val stats: StatsDocument? = null,
)

@Serializable
internal data class ScoreDocument(
    val completion: Double,
    val speed: Double,
    val failure: Double,
    val quality: Double,
    val hunger: Double,
    val newcomer: Double,
    val sizeFit: Double,
    val weight: Double,
)

/** One merchant in `routing_decisions.candidates`: left out with a reason, or eligible with its score. */
@Serializable
internal data class CandidateDocument(
    val merchantId: String,
    val excluded: String? = null,
    val inputs: InputsDocument,
    val score: ScoreDocument? = null,
) {
    fun toCandidate(): RoutedCandidate =
        RoutedCandidate(
            facts =
                CandidateFacts(
                    merchantId = MerchantId(UUID.fromString(merchantId)),
                    approved = inputs.approved,
                    kycCleared = inputs.kycCleared,
                    online = inputs.online,
                    hasKeys = inputs.hasKeys,
                    paymentsIssue = inputs.paymentsIssue,
                    offersDirection = inputs.offersDirection,
                    limitMinor = inputs.limitMinor,
                    floatMinor = inputs.floatMinor,
                    lastAssignedAt = inputs.lastAssignedAt?.let(Instant::parse),
                    completedTrades = inputs.completedTrades,
                    stats =
                        inputs.stats?.let {
                            MerchantStats(
                                it.finished,
                                it.completed,
                                it.merchantFailures,
                                it.merchantExpiries,
                                it.reversals,
                                it.medianHandlingSeconds,
                                it.p90HandlingSeconds,
                            )
                        },
                ),
            exclusion = excluded?.let { name -> Exclusion.entries.firstOrNull { it.name == name } },
            score =
                score?.let {
                    Score(
                        it.completion,
                        it.speed,
                        it.failure,
                        it.quality,
                        it.hunger,
                        it.newcomer,
                        it.sizeFit,
                        it.weight,
                    )
                },
        )

    companion object {
        fun of(candidate: RoutedCandidate): CandidateDocument {
            val facts = candidate.facts
            return CandidateDocument(
                merchantId = facts.merchantId.toString(),
                excluded = candidate.exclusion?.name,
                inputs =
                    InputsDocument(
                        approved = facts.approved,
                        kycCleared = facts.kycCleared,
                        online = facts.online,
                        hasKeys = facts.hasKeys,
                        paymentsIssue = facts.paymentsIssue,
                        offersDirection = facts.offersDirection,
                        limitMinor = facts.limitMinor,
                        floatMinor = facts.floatMinor,
                        lastAssignedAt = facts.lastAssignedAt?.toString(),
                        completedTrades = facts.completedTrades,
                        stats = facts.stats?.let(::statsOf),
                    ),
                score = candidate.score?.let(::scoreOf),
            )
        }

        private fun statsOf(stats: MerchantStats) =
            StatsDocument(
                finished = stats.finished,
                completed = stats.completed,
                merchantFailures = stats.merchantFailures,
                merchantExpiries = stats.merchantExpiries,
                reversals = stats.reversals,
                medianHandlingSeconds = stats.medianHandlingSeconds,
                p90HandlingSeconds = stats.p90HandlingSeconds,
            )

        private fun scoreOf(score: Score) =
            ScoreDocument(
                completion = score.completion,
                speed = score.speed,
                failure = score.failure,
                quality = score.quality,
                hunger = score.hunger,
                newcomer = score.newcomer,
                sizeFit = score.sizeFit,
                weight = score.weight,
            )
    }
}

/** `routing_decisions.ranked`: the failover order used, and the engine's own draw when the mode scored. */
@Serializable
internal data class RankedDocument(val used: List<String>, val engine: List<String>? = null)

/** One attempt in `routing_decisions.steps`. */
@Serializable
internal data class StepDocument(
    val merchantId: String,
    val orderId: String? = null,
    val result: String,
    val at: String,
) {
    fun toStep(): RoutingStep =
        RoutingStep(
            merchantId = MerchantId(UUID.fromString(merchantId)),
            orderId = orderId?.let { RampOrderId(UUID.fromString(it)) },
            result = result,
            at = Instant.parse(at),
        )

    companion object {
        fun of(step: RoutingStep) =
            StepDocument(step.merchantId.toString(), step.orderId?.toString(), step.result, step.at.toString())
    }
}
