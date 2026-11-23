package com.circaso.ramp.domain.repositories

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.EngineSettingsVersion
import com.circaso.ramp.domain.models.FinishedOrder
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.RoutingDecision
import com.circaso.ramp.domain.models.RoutingFilter
import com.circaso.ramp.domain.models.RoutingOutcome
import com.circaso.ramp.domain.models.RoutingStep
import com.circaso.shared.Network
import java.time.Instant
import java.util.UUID

/** One step of an order's timeline: the state it entered, when, and why when the move carried a reason. */
data class OrderEvent(
    val orderId: RampOrderId,
    val state: OrderState,
    val detail: String?,
    val at: Instant,
)

/**
 * The order timeline (`ramp.order_events`, decision 2026-11-23). It has no write of its own: [OrderRepository] records
 * an event in the same transaction as every insert and every move, so a state an order entered always has its time.
 */
interface OrderEventRepository {
    /** One order's steps, oldest first. */
    suspend fun timeline(orderId: RampOrderId): List<OrderEvent>

    /**
     * The merchant's last [limit] **counted** orders on [network] in [direction] that finished at or after [since],
     * newest first: the completed ones and the ones that ended because of the merchant (`Stats.COUNTED`), each with
     * its handling time when the timeline has both ends of it. Orders that ended for any other reason are not in it,
     * so however many buyers let a prompt lapse, they never push a merchant's own record out of the window.
     */
    suspend fun counted(
        merchantId: MerchantId,
        network: Network,
        direction: Direction,
        since: Instant,
        limit: Int,
    ): List<FinishedOrder>

    /** How many of the merchant's orders finished at or after [since] for a reason that isn't counted. */
    suspend fun notCounted(
        merchantId: MerchantId,
        network: Network,
        direction: Direction,
        since: Instant,
    ): Int
}

/**
 * The engine's settings (`ramp.engine_settings` and its history). A change is version-checked like the ramp's own
 * settings, and every version is kept so a routing decision can always be read against the settings that made it.
 */
interface EngineSettingsRepository {
    /** The row in force for every network and direction that has one. */
    suspend fun load(): Map<Pair<Network, Direction>, EngineSettingsVersion>

    /** Writes a network and direction's first version; does nothing when they already have one. */
    suspend fun seed(row: EngineSettingsVersion)

    /**
     * Replaces the settings in force with the next version. With [expectedVersion], only when the row is still at
     * it; the row afterwards, or null when someone changed it meanwhile.
     */
    suspend fun update(
        network: Network,
        direction: Direction,
        settings: EngineSettings,
        expectedVersion: Long?,
        by: String,
        reason: String,
        at: Instant,
    ): EngineSettingsVersion?

    /** One past (or the current) version. */
    suspend fun version(
        network: Network,
        direction: Direction,
        version: Long,
    ): EngineSettingsVersion?
}

/** The routing log (`ramp.routing_decisions`): written for every routing, read by staff, pruned by a job. */
interface RoutingLogRepository {
    suspend fun insert(decision: RoutingDecision)

    /** Records how the routing ended: who took the order, if anybody, and each step of the failover. */
    suspend fun finish(
        decisionId: UUID,
        orderId: RampOrderId?,
        merchantId: MerchantId?,
        outcome: RoutingOutcome,
        steps: List<RoutingStep>,
    )

    suspend fun find(decisionId: UUID): RoutingDecision?

    /** Decisions matching [filter], newest first, after [after]. */
    suspend fun list(
        filter: RoutingFilter,
        after: PageCursor?,
        limit: Int,
    ): List<RoutingDecision>

    /** Deletes up to [limit] decisions of [network] and [direction] made before [before]; how many. */
    suspend fun prune(
        network: Network,
        direction: Direction,
        before: Instant,
        limit: Int,
    ): Int
}
