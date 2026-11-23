package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.data.support.EngineDocuments
import com.circaso.ramp.data.tables.EngineSettingsHistoryTable
import com.circaso.ramp.data.tables.EngineSettingsTable
import com.circaso.ramp.data.tables.OrderEventsTable
import com.circaso.ramp.data.tables.RoutingDecisionsTable
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineMode
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.EngineSettingsVersion
import com.circaso.ramp.domain.models.FinishedOrder
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.RoutingDecision
import com.circaso.ramp.domain.models.RoutingFilter
import com.circaso.ramp.domain.models.RoutingOrder
import com.circaso.ramp.domain.models.RoutingOutcome
import com.circaso.ramp.domain.models.RoutingStep
import com.circaso.ramp.domain.repositories.EngineSettingsRepository
import com.circaso.ramp.domain.repositories.OrderEvent
import com.circaso.ramp.domain.repositories.OrderEventRepository
import com.circaso.ramp.domain.repositories.RoutingLogRepository
import com.circaso.ramp.domain.support.Stats
import com.circaso.shared.Network
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Reads of `ramp.order_events`. The rows are written by [OrderRepositoryImpl], in the transaction of each move. A
 * merchant's finished orders are served by `order_events_finished_idx` (bounded by the statistics' days), the steps
 * of those orders by `order_events_order_idx`: three small reads per merchant, whatever the size of the table.
 */
class OrderEventRepositoryImpl(private val database: Database) : OrderEventRepository {
    override suspend fun timeline(orderId: RampOrderId): List<OrderEvent> =
        database.tx("order-events.timeline") {
            OrderEventsTable
                .selectAll()
                .where { OrderEventsTable.orderId eq orderId.value }
                .orderBy(OrderEventsTable.at to SortOrder.ASC, OrderEventsTable.eventId to SortOrder.ASC)
                .map { it.toEvent() }
                .toList()
                .filterNotNull()
        }

    override suspend fun counted(
        merchantId: MerchantId,
        network: Network,
        direction: Direction,
        since: Instant,
        limit: Int,
    ): List<FinishedOrder> =
        database.tx("order-events.counted") {
            val ends =
                OrderEventsTable
                    .selectAll()
                    .where { finishedSince(merchantId, network, direction, since) and counted() }
                    .orderBy(OrderEventsTable.at to SortOrder.DESC)
                    .limit(limit)
                    .map { it.toEvent() }
                    .toList()
                    .filterNotNull()
                    .distinctBy { it.orderId }
            val completed = ends.filter { it.state == OrderState.COMPLETED }.map { it.orderId.value }
            val starts = handlingStarts(completed, direction)
            ends.map { end ->
                val started = starts[end.orderId.value]
                FinishedOrder(
                    orderId = end.orderId,
                    state = end.state,
                    reason = end.detail,
                    finishedAt = end.at,
                    handlingSeconds = started?.let { Duration.between(it, end.at).seconds },
                )
            }
        }

    override suspend fun notCounted(
        merchantId: MerchantId,
        network: Network,
        direction: Direction,
        since: Instant,
    ): Int =
        database.tx("order-events.not-counted") {
            val orders = OrderEventsTable.eventId.count()
            OrderEventsTable
                .select(orders)
                .where { finishedSince(merchantId, network, direction, since) and not(counted()) }
                .single()[orders]
                .toInt()
        }

    /** The merchant's finishing steps on [network] in [direction] since [since]: `order_events_finished_idx`. */
    private fun finishedSince(
        merchantId: MerchantId,
        network: Network,
        direction: Direction,
        since: Instant,
    ): Op<Boolean> =
        (OrderEventsTable.merchantId eq merchantId.value) and
            (OrderEventsTable.network eq network.name) and
            (OrderEventsTable.direction eq direction.name) and
            (OrderEventsTable.state inList Stats.FINISHED_NAMES) and
            (OrderEventsTable.at greaterEq since)

    /** What enters a merchant's rates ([Stats.isCounted]), as a condition: completed, or ended because of them. */
    private fun counted(): Op<Boolean> {
        val failed =
            (OrderEventsTable.state eq OrderState.FAILED.name) and
                OrderEventsTable.detail.isNotNull() and
                (OrderEventsTable.detail inList Stats.MERCHANT_FAILURES)
        val expired =
            (OrderEventsTable.state eq OrderState.EXPIRED.name) and
                OrderEventsTable.detail.isNotNull() and
                (OrderEventsTable.detail inList Stats.MERCHANT_EXPIRIES)
        return (OrderEventsTable.state eq OrderState.COMPLETED.name) or failed or expired
    }

    /** When each of [orderIds] reached the step its handling time is counted from; the first time, if more than once. */
    private suspend fun handlingStarts(orderIds: List<UUID>, direction: Direction): Map<UUID, Instant> {
        if (orderIds.isEmpty()) return emptyMap()
        val from =
            if (direction == Direction.ON_RAMP) Stats.HANDLING_FROM_ON_RAMP else Stats.HANDLING_FROM_OFF_RAMP
        return OrderEventsTable
            .select(OrderEventsTable.orderId, OrderEventsTable.at)
            .where { (OrderEventsTable.orderId inList orderIds) and (OrderEventsTable.state eq from) }
            .map { it[OrderEventsTable.orderId] to it[OrderEventsTable.at] }
            .toList()
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, times) -> times.min() }
    }

    /** Null for a state this build doesn't know yet (one a later slice writes): it is left out, never guessed. */
    private fun ResultRow.toEvent(): OrderEvent? {
        val state = OrderState.entries.firstOrNull { it.name == this[OrderEventsTable.state] } ?: return null
        return OrderEvent(
            orderId = RampOrderId(this[OrderEventsTable.orderId]),
            state = state,
            detail = this[OrderEventsTable.detail],
            at = this[OrderEventsTable.at],
        )
    }
}

/**
 * `ramp.engine_settings` (the version in force) and `ramp.engine_settings_history` (every version). A change is
 * `SET version = version + 1 WHERE version = ?` and the history row of the new version, in one transaction: two staff
 * saving at once can't overwrite each other, and no version is ever in force without being kept.
 */
class EngineSettingsRepositoryImpl(private val database: Database) : EngineSettingsRepository {
    override suspend fun load(): Map<Pair<Network, Direction>, EngineSettingsVersion> =
        database.tx("engine-settings.load") {
            EngineSettingsTable
                .selectAll()
                .map { it.toRow() }
                .toList()
                .filterNotNull()
                .associateBy { it.network to it.direction }
        }

    override suspend fun seed(row: EngineSettingsVersion) {
        database.tx("engine-settings.seed") {
            val seeded =
                EngineSettingsTable
                    .insertIgnore {
                        it[network] = row.network.name
                        it[direction] = row.direction.name
                        it[version] = row.version
                        it.write(row.settings, row.updatedBy, row.reason, row.updatedAt)
                    }.insertedCount > 0
            if (seeded) keep(row)
        }
    }

    override suspend fun update(
        network: Network,
        direction: Direction,
        settings: EngineSettings,
        expectedVersion: Long?,
        by: String,
        reason: String,
        at: Instant,
    ): EngineSettingsVersion? =
        database.tx("engine-settings.update") {
            val updated =
                EngineSettingsTable
                    .updateReturning(where = {
                        val row =
                            (EngineSettingsTable.network eq network.name) and
                                (EngineSettingsTable.direction eq direction.name)
                        if (expectedVersion == null) row else row and (EngineSettingsTable.version eq expectedVersion)
                    }) {
                        it.write(settings, by, reason, at)
                        it[version] = version + 1
                    }.map { it.toRow() }
                    .singleOrNull()
            updated?.also { keep(it) }
        }

    override suspend fun version(
        network: Network,
        direction: Direction,
        version: Long,
    ): EngineSettingsVersion? =
        database.tx("engine-settings.version") {
            EngineSettingsHistoryTable
                .selectAll()
                .where {
                    (EngineSettingsHistoryTable.network eq network.name) and
                        (EngineSettingsHistoryTable.direction eq direction.name) and
                        (EngineSettingsHistoryTable.version eq version)
                }.map { it.toHistoryRow() }
                .singleOrNull()
        }

    private suspend fun keep(row: EngineSettingsVersion) {
        EngineSettingsHistoryTable.insertIgnore {
            it[network] = row.network.name
            it[direction] = row.direction.name
            it[version] = row.version
            it[document] = EngineDocuments.encode(row.settings)
            it[updatedBy] = row.updatedBy
            it[reason] = row.reason
            it[updatedAt] = row.updatedAt
        }
    }

    private fun UpdateBuilder<*>.write(
        settings: EngineSettings,
        by: String?,
        reason: String?,
        at: Instant,
    ) {
        this[EngineSettingsTable.document] = EngineDocuments.encode(settings)
        this[EngineSettingsTable.updatedBy] = by
        this[EngineSettingsTable.reason] = reason
        this[EngineSettingsTable.updatedAt] = at
    }

    private fun ResultRow.toRow(): EngineSettingsVersion? {
        val network = Network.parse(this[EngineSettingsTable.network]) ?: return null
        return EngineSettingsVersion(
            network = network,
            direction = Direction.valueOf(this[EngineSettingsTable.direction]),
            version = this[EngineSettingsTable.version],
            settings = EngineDocuments.decode(this[EngineSettingsTable.document], network),
            updatedBy = this[EngineSettingsTable.updatedBy],
            reason = this[EngineSettingsTable.reason],
            updatedAt = this[EngineSettingsTable.updatedAt],
        )
    }

    private fun ResultRow.toHistoryRow(): EngineSettingsVersion? {
        val network = Network.parse(this[EngineSettingsHistoryTable.network]) ?: return null
        return EngineSettingsVersion(
            network = network,
            direction = Direction.valueOf(this[EngineSettingsHistoryTable.direction]),
            version = this[EngineSettingsHistoryTable.version],
            settings = EngineDocuments.decode(this[EngineSettingsHistoryTable.document], network),
            updatedBy = this[EngineSettingsHistoryTable.updatedBy],
            reason = this[EngineSettingsHistoryTable.reason],
            updatedAt = this[EngineSettingsHistoryTable.updatedAt],
        )
    }
}

/**
 * `ramp.routing_decisions`. A row is written before the first attempt and finished when the routing ends, so a
 * request that dies halfway still leaves what was decided. Lists page by keyset on `(created_at, decision_id)`.
 */
class RoutingLogRepositoryImpl(private val database: Database) : RoutingLogRepository {
    override suspend fun insert(decision: RoutingDecision) {
        database.tx("routing.insert") {
            RoutingDecisionsTable.insert {
                it[decisionId] = decision.decisionId
                it[quoteId] = decision.quoteId.value
                it[orderId] = decision.orderId?.value
                it[merchantId] = decision.merchantId?.value
                it[network] = decision.network.name
                it[direction] = decision.order.direction.name
                it[fiatAmountMinor] = decision.order.fiatMinor
                it[needMinor] = decision.order.needMinor
                it[settingsVersion] = decision.settingsVersion
                it[mode] = decision.plan.mode.name
                it[outcome] = decision.outcome.name
                it[candidates] = EngineDocuments.encodeCandidates(decision.plan.candidates)
                it[ranked] = EngineDocuments.encodeRanked(decision.plan)
                it[steps] = EngineDocuments.encodeSteps(decision.steps)
                it[createdAt] = decision.createdAt
            }
        }
    }

    override suspend fun finish(
        decisionId: UUID,
        orderId: RampOrderId?,
        merchantId: MerchantId?,
        outcome: RoutingOutcome,
        steps: List<RoutingStep>,
    ) {
        database.tx("routing.finish") {
            RoutingDecisionsTable.update({ RoutingDecisionsTable.decisionId eq decisionId }) {
                it[RoutingDecisionsTable.orderId] = orderId?.value
                it[RoutingDecisionsTable.merchantId] = merchantId?.value
                it[RoutingDecisionsTable.outcome] = outcome.name
                it[RoutingDecisionsTable.steps] = EngineDocuments.encodeSteps(steps)
            }
        }
    }

    override suspend fun find(decisionId: UUID): RoutingDecision? =
        database.tx("routing.find") {
            RoutingDecisionsTable
                .selectAll()
                .where { RoutingDecisionsTable.decisionId eq decisionId }
                .map { it.toDecision() }
                .singleOrNull()
        }

    override suspend fun list(
        filter: RoutingFilter,
        after: PageCursor?,
        limit: Int,
    ): List<RoutingDecision> =
        database.tx("routing.list") {
            RoutingDecisionsTable
                .selectAll()
                .where {
                    val asked = matching(filter)
                    val time = RoutingDecisionsTable.createdAt
                    if (after == null) asked else asked and after(after, time, RoutingDecisionsTable.decisionId)
                }.orderBy(
                    RoutingDecisionsTable.createdAt to SortOrder.DESC,
                    RoutingDecisionsTable.decisionId to SortOrder.DESC,
                ).limit(limit)
                .map { it.toDecision() }
                .toList()
        }

    override suspend fun prune(
        network: Network,
        direction: Direction,
        before: Instant,
        limit: Int,
    ): Int =
        database.tx("routing.prune") {
            val old =
                RoutingDecisionsTable
                    .select(RoutingDecisionsTable.decisionId)
                    .where {
                        (RoutingDecisionsTable.network eq network.name) and
                            (RoutingDecisionsTable.direction eq direction.name) and
                            (RoutingDecisionsTable.createdAt less before)
                    }.limit(limit)
                    .map { it[RoutingDecisionsTable.decisionId] }
                    .toList()
            if (old.isEmpty()) 0 else RoutingDecisionsTable.deleteWhere { RoutingDecisionsTable.decisionId inList old }
        }

    private fun matching(filter: RoutingFilter): Op<Boolean> {
        var asked: Op<Boolean> = RoutingDecisionsTable.network eq filter.network.name
        filter.merchantId?.let { asked = asked and (RoutingDecisionsTable.merchantId eq it.value) }
        filter.quoteId?.let { asked = asked and (RoutingDecisionsTable.quoteId eq it.value) }
        filter.from?.let { asked = asked and (RoutingDecisionsTable.createdAt greaterEq it) }
        filter.to?.let { asked = asked and (RoutingDecisionsTable.createdAt less it) }
        return asked
    }

    private fun ResultRow.toDecision(): RoutingDecision {
        val mode = EngineMode.valueOf(this[RoutingDecisionsTable.mode])
        return RoutingDecision(
            decisionId = this[RoutingDecisionsTable.decisionId],
            quoteId = QuoteId(this[RoutingDecisionsTable.quoteId]),
            orderId = this[RoutingDecisionsTable.orderId]?.let(::RampOrderId),
            merchantId = this[RoutingDecisionsTable.merchantId]?.let(::MerchantId),
            network = Network.valueOf(this[RoutingDecisionsTable.network]),
            order =
                RoutingOrder(
                    direction = Direction.valueOf(this[RoutingDecisionsTable.direction]),
                    fiatMinor = this[RoutingDecisionsTable.fiatAmountMinor],
                    needMinor = this[RoutingDecisionsTable.needMinor],
                ),
            settingsVersion = this[RoutingDecisionsTable.settingsVersion],
            plan =
                EngineDocuments.decodePlan(
                    mode,
                    this[RoutingDecisionsTable.candidates],
                    this[RoutingDecisionsTable.ranked],
                ),
            outcome = RoutingOutcome.valueOf(this[RoutingDecisionsTable.outcome]),
            steps = EngineDocuments.decodeSteps(this[RoutingDecisionsTable.steps]),
            createdAt = this[RoutingDecisionsTable.createdAt],
        )
    }
}
