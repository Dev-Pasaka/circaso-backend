package com.circaso.ramp.domain.usecases

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.EngineSettingsChange
import com.circaso.ramp.domain.models.EngineSettingsVersion
import com.circaso.ramp.domain.models.MerchantOverview
import com.circaso.ramp.domain.models.MerchantPerformance
import com.circaso.ramp.domain.models.MerchantRecord
import com.circaso.ramp.domain.models.MerchantType
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Performance
import com.circaso.ramp.domain.models.RoutingDecision
import com.circaso.ramp.domain.models.RoutingFilter
import com.circaso.ramp.domain.models.RoutingQuery
import com.circaso.ramp.domain.models.Simulation
import com.circaso.ramp.domain.models.SimulationCommand
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.repositories.CredentialRepository
import com.circaso.ramp.domain.repositories.EngineSettingsRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.RoutingLogRepository
import com.circaso.ramp.domain.repositories.VerificationRepository
import com.circaso.ramp.domain.support.EngineBook
import com.circaso.ramp.domain.support.MerchantStatsBook
import com.circaso.ramp.domain.support.MerchantViews
import com.circaso.ramp.domain.support.RoutingSimulator
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The engine's settings for a network and direction, read from the table now (never the ten-second copy). */
class GetEngineSettings(private val rows: EngineSettingsRepository, private val book: EngineBook) {
    suspend operator fun invoke(network: Network, direction: Direction): Resource<EngineSettingsVersion> {
        // The book seeds a network and direction that have no row yet, so the table always answers after it.
        val inForce = book.of(network, direction)
        return Resource.success(rows.load()[network to direction] ?: inForce)
    }
}

/**
 * Replaces a network and direction's engine settings with the next version, version-checked. What is out of range is
 * refused first, naming the field (`ramp.settings_invalid`); a change made meanwhile is `ramp.settings_changed`.
 * Every version is kept with who made it and why. In force everywhere within ten seconds, with no restart.
 */
class UpdateEngineSettings(
    private val rows: EngineSettingsRepository,
    private val book: EngineBook,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        change: EngineSettingsVersion,
        expectedVersion: Long?,
        by: String,
        reason: String,
    ): Resource<EngineSettingsChange> {
        change.settings.problem()?.let { (field, what) ->
            return Resource.error(RampError.SettingsInvalid(field, what))
        }
        book.of(change.network, change.direction)
        val before =
            rows.load()[change.network to change.direction] ?: return Resource.error(RampError.SettingsChanged)
        val after =
            rows.update(change.network, change.direction, change.settings, expectedVersion, by, reason, clock.instant())
                ?: return Resource.error(RampError.SettingsChanged)
        book.invalidate()
        return Resource.success(EngineSettingsChange(before, after))
    }
}

/**
 * The routing log for staff, newest first: a merchant's decisions (the ones that ended with them), an order's (found
 * through its quote, so any attempt of a purchase finds the purchase's routing), a stretch of time, or everything.
 */
class ListRoutingDecisions(private val log: RoutingLogRepository, private val orders: OrderRepository) {
    suspend operator fun invoke(
        query: RoutingQuery,
        after: PageCursor?,
        limit: Int,
    ): Resource<List<RoutingDecision>> {
        val quoteId =
            query.orderId?.let { orderId ->
                orders.find(orderId)?.takeIf { it.network == query.network }?.quoteId
                    ?: return Resource.success(emptyList())
            }
        val filter = RoutingFilter(query.network, query.merchantId, quoteId, query.from, query.to)
        return Resource.success(log.list(filter, after, limit.coerceIn(1, MAX_LIMIT)))
    }

    private companion object {
        const val MAX_LIMIT = 200
    }
}

/** One routing decision in full: "why this merchant". */
class GetRoutingDecision(private val log: RoutingLogRepository) {
    suspend operator fun invoke(decisionId: UUID): Resource<RoutingDecision> =
        log.find(decisionId)?.let { Resource.success(it) } ?: Resource.error(RampError.RoutingNotFound)
}

/** RO10: what a draft of the engine's settings would do to the last real orders ([RoutingSimulator]). */
class SimulateRouting(private val simulator: RoutingSimulator) {
    suspend operator fun invoke(command: SimulationCommand): Resource<Simulation> = simulator.run(command)
}

/**
 * Staff's call on a merchant: their type (which sets the order limit they start with) and, when given, a limit of
 * their own in KES minor units (null: the type's default from the engine settings).
 */
class SetMerchantTier(
    private val merchants: MerchantRepository,
    private val wallets: MerchantWalletRepository,
    private val credentials: CredentialRepository,
    private val verifications: VerificationRepository,
) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        type: MerchantType,
        orderLimitMinor: Long?,
    ): Resource<MerchantRecord> {
        if (orderLimitMinor != null && orderLimitMinor <= 0) {
            return Resource.error(RampError.SettingsInvalid("orderLimitMinor", "must be above zero, or left out"))
        }
        if (!merchants.setTier(merchantId, type, orderLimitMinor)) return Resource.error(RampError.MerchantNotFound)
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        return Resource.success(
            MerchantRecord(
                merchant = merchant,
                wallets = wallets.ofMerchant(merchantId),
                paymentsConnected = credentials.find(merchantId) != null,
                verifications = verifications.ofSubject(Subject.merchant(merchantId)),
            ),
        )
    }
}

/**
 * A merchant's own ranking signals on a network, per direction (RM16, and the same read for staff): the record the
 * engine scores them on, their trades, their limit, and the settings those are measured against. Never anything
 * about another merchant.
 */
class GetMerchantPerformance(
    private val merchants: MerchantRepository,
    private val book: EngineBook,
    private val statsBook: MerchantStatsBook,
) {
    suspend operator fun invoke(merchantId: MerchantId, network: Network): Resource<MerchantPerformance> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        val trades = statsBook.trades(merchantId, network)
        val directions =
            Direction.entries.map { direction ->
                val engine = book.of(network, direction).settings
                Performance(
                    direction = direction,
                    offered = merchant.offers(direction),
                    orderLimitMinor = merchant.orderLimit(engine),
                    stats = statsBook.stats(merchantId, network, direction, engine),
                    trades = trades,
                    settings = engine,
                )
            }
        return Resource.success(MerchantPerformance(merchantId, network, merchant.merchantType, directions))
    }
}

/**
 * RM17: the merchant's own switches, which directions they take orders in; one left out (null) stays as it is.
 * Answers the account as RM2 does.
 */
class SetMerchantOffers(private val merchants: MerchantRepository, private val views: MerchantViews) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        onRamp: Boolean?,
        offRamp: Boolean?,
    ): Resource<MerchantOverview> {
        val before = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        merchants.setOffers(merchantId, onRamp ?: before.offersOnramp, offRamp ?: before.offersOfframp)
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        return Resource.success(views.overview(merchant, network))
    }
}

/**
 * `ramp.prune-routing`: deletes routing decisions older than each network and direction's own retention
 * ([EngineSettings.logRetentionDays]), a chunk at a time. Safe on two nodes and safe to run twice.
 */
class PruneRoutingLog(private val log: RoutingLogRepository, private val book: EngineBook, private val clock: Clock) {
    suspend operator fun invoke(): Int {
        val now = clock.instant()
        var pruned = 0
        Network.entries.forEach { network ->
            Direction.entries.forEach { direction ->
                val days = book.of(network, direction).settings.logRetentionDays
                pruned += prune(network, direction, now.minus(Duration.ofDays(days.toLong())))
            }
        }
        return pruned
    }

    private suspend fun prune(
        network: Network,
        direction: Direction,
        before: Instant,
    ): Int {
        var pruned = 0
        repeat(MAX_CHUNKS) {
            val deleted = log.prune(network, direction, before, CHUNK)
            pruned += deleted
            if (deleted < CHUNK) return pruned
        }
        return pruned
    }

    private companion object {
        const val CHUNK = 500
        const val MAX_CHUNKS = 20
    }
}
