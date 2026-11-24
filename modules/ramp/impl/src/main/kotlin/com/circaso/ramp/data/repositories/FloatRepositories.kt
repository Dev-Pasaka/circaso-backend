package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.data.tables.CommissionsTable
import com.circaso.ramp.data.tables.FeeSettlementsTable
import com.circaso.ramp.data.tables.OrdersTable
import com.circaso.ramp.data.tables.ReservationsTable
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.FeeSplit
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.Settlement
import com.circaso.ramp.domain.models.SettlementState
import com.circaso.ramp.domain.models.Stamped
import com.circaso.ramp.domain.models.StatementLine
import com.circaso.ramp.domain.models.StatementTotals
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.ramp.domain.repositories.SettlementRepository
import com.circaso.ramp.domain.repositories.StatementRepository
import com.circaso.ramp.domain.support.Periods
import com.circaso.shared.Network
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private const val OPEN = "OPEN"
private const val CONSUMED = "CONSUMED"
private const val RELEASED = "RELEASED"
private const val FLOAT = "FLOAT"
private const val HOLD = "HOLD"
private const val WITHDRAWAL = "WITHDRAWAL"

class ReservationRepositoryImpl(private val database: Database) : ReservationRepository {
    override suspend fun openTotal(merchantId: MerchantId, network: Network): Long =
        database.tx("reservations.open-total") {
            val total = ReservationsTable.amountMinor.sum()
            ReservationsTable
                .select(total)
                .where {
                    (ReservationsTable.merchantId eq merchantId.value) and
                        (ReservationsTable.network eq network.name) and
                        (ReservationsTable.state eq OPEN)
                }.single()[total] ?: 0
        }

    override suspend fun insert(order: Order, at: Instant) {
        database.tx("reservations.insert") {
            ReservationsTable.insertIgnore {
                it[orderId] = order.orderId.value
                it[merchantId] = order.merchantId.value
                it[network] = order.network.name
                it[amountMinor] = order.assetMinor
                it[asset] = RampSettings.ASSET
                it[kind] = FLOAT
                it[state] = OPEN
                it[createdAt] = at
            }
        }
    }

    override suspend fun hold(
        order: Order,
        amountMinor: Long,
        at: Instant,
    ) {
        database.tx("reservations.hold") {
            ReservationsTable.insertIgnore {
                it[orderId] = order.orderId.value
                it[merchantId] = order.merchantId.value
                it[network] = order.network.name
                it[ReservationsTable.amountMinor] = amountMinor
                it[asset] = RampSettings.ASSET
                it[kind] = HOLD
                it[state] = OPEN
                it[createdAt] = at
            }
        }
    }

    override suspend fun release(orderId: RampOrderId, at: Instant): Boolean = close(orderId, RELEASED, at)

    override suspend fun reopen(orderId: RampOrderId, at: Instant): Boolean =
        database.tx("reservations.reopen") {
            ReservationsTable.update({
                (ReservationsTable.orderId eq orderId.value) and (ReservationsTable.state eq RELEASED)
            }) {
                it[state] = OPEN
                it[closedAt] = null
            } > 0
        }

    override suspend fun consume(orderId: RampOrderId, at: Instant): Boolean = close(orderId, CONSUMED, at)

    override suspend fun reserveWithdrawal(
        withdrawalId: UUID,
        merchantId: MerchantId,
        network: Network,
        amountMinor: Long,
        at: Instant,
    ) {
        database.tx("reservations.reserve-withdrawal") {
            ReservationsTable.insertIgnore {
                it[orderId] = withdrawalId
                it[ReservationsTable.merchantId] = merchantId.value
                it[ReservationsTable.network] = network.name
                it[ReservationsTable.amountMinor] = amountMinor
                it[asset] = RampSettings.ASSET
                it[kind] = WITHDRAWAL
                it[state] = OPEN
                it[createdAt] = at
            }
        }
    }

    override suspend fun closeWithdrawal(
        withdrawalId: UUID,
        consumed: Boolean,
        at: Instant,
    ): Boolean = closeRow(withdrawalId, if (consumed) CONSUMED else RELEASED, at)

    override suspend fun withdrawingTotal(merchantId: MerchantId, network: Network): Long =
        database.tx("reservations.withdrawing-total") {
            val total = ReservationsTable.amountMinor.sum()
            ReservationsTable
                .select(total)
                .where {
                    (ReservationsTable.merchantId eq merchantId.value) and
                        (ReservationsTable.network eq network.name) and
                        (ReservationsTable.kind eq WITHDRAWAL) and
                        (ReservationsTable.state eq OPEN)
                }.single()[total] ?: 0
        }

    private suspend fun close(
        orderId: RampOrderId,
        to: String,
        at: Instant,
    ): Boolean = closeRow(orderId.value, to, at)

    private suspend fun closeRow(
        id: UUID,
        to: String,
        at: Instant,
    ): Boolean =
        database.tx("reservations.close") {
            ReservationsTable.update({
                (ReservationsTable.orderId eq id) and (ReservationsTable.state eq OPEN)
            }) {
                it[state] = to
                it[closedAt] = at
            } > 0
        }
}

class CommissionRepositoryImpl(private val database: Database) : CommissionRepository {
    override suspend fun insert(
        order: Order,
        split: FeeSplit,
        at: Instant,
    ): Boolean =
        database.tx("commissions.insert") {
            CommissionsTable
                .insertIgnore {
                    it[orderId] = order.orderId.value
                    it[merchantId] = order.merchantId.value
                    it[network] = order.network.name
                    it[merchantMinor] = split.merchantMinor
                    it[platformMinor] = split.platformMinor
                    it[platformAssetMinor] = split.platformAssetMinor
                    // Nothing owed in USDC (the share rounds to nothing): settled as written, no sweep names it.
                    it[settledAt] = if (split.platformAssetMinor == 0L) at else null
                    it[currency] = order.fiat.currency
                    it[earnedAt] = at
                }.insertedCount > 0
        }

    override suspend fun owedTotal(merchantId: MerchantId, network: Network): Long =
        database.tx("commissions.owed-total") {
            val total = CommissionsTable.platformAssetMinor.sum()
            CommissionsTable
                .select(total)
                .where {
                    (CommissionsTable.merchantId eq merchantId.value) and
                        (CommissionsTable.network eq network.name) and
                        CommissionsTable.settledAt.isNull()
                }.single()[total] ?: 0
        }

    override suspend fun completedCount(merchantId: MerchantId, network: Network): Int =
        database.tx("commissions.completed-count") {
            val trades = CommissionsTable.orderId.count()
            CommissionsTable
                .select(trades)
                .where {
                    (CommissionsTable.merchantId eq merchantId.value) and (CommissionsTable.network eq network.name)
                }.single()[trades]
                .toInt()
        }
}

/**
 * `ramp.fee_settlements` and the commissions each sweep collects. Opening a sweep names it on the commissions no sweep
 * had taken (`settlement_id IS NULL`, in the caller's transaction); `fee_settlements_open_key` allows a merchant one
 * pending sweep per network. Confirming or failing is `WHERE state = 'PENDING'`, so only one caller does what follows.
 */
class SettlementRepositoryImpl(private val database: Database) : SettlementRepository {
    override suspend fun insert(settlement: Settlement) {
        database.tx("settlements.insert") {
            FeeSettlementsTable.insert {
                it[settlementId] = settlement.settlementId
                it[merchantId] = settlement.merchantId.value
                it[network] = settlement.network.name
                it[day] = settlement.day
                it[amountMinor] = settlement.amountMinor
                it[orders] = settlement.orders
                it[state] = settlement.state.name
                it[custodyTxId] = settlement.custodyTxId
                it[txHash] = settlement.txHash
                it[failureReason] = settlement.failureReason
                it[createdAt] = settlement.createdAt
                it[updatedAt] = settlement.createdAt
            }
        }
    }

    override suspend fun pending(network: Network, limit: Int): List<Settlement> =
        database.tx("settlements.pending") {
            FeeSettlementsTable
                .selectAll()
                .where {
                    (FeeSettlementsTable.network eq network.name) and
                        (FeeSettlementsTable.state eq SettlementState.PENDING.name)
                }.orderBy(FeeSettlementsTable.settlementId to SortOrder.ASC)
                .limit(limit)
                .map { it.toSettlement() }
                .toList()
        }

    override suspend fun hasRecent(
        merchantId: MerchantId,
        network: Network,
        since: Instant,
        failedSince: Instant,
    ): Boolean =
        database.tx("settlements.has-recent") {
            val failed = FeeSettlementsTable.state eq SettlementState.FAILED.name
            FeeSettlementsTable
                .select(FeeSettlementsTable.settlementId)
                .where {
                    (FeeSettlementsTable.merchantId eq merchantId.value) and
                        (FeeSettlementsTable.network eq network.name) and
                        (
                            (
                                (FeeSettlementsTable.state neq SettlementState.FAILED.name) and
                                    (FeeSettlementsTable.createdAt greaterEq since)
                            ) or
                                (failed and (FeeSettlementsTable.createdAt greaterEq failedSince))
                        )
                }.limit(1)
                .firstOrNull() != null
        }

    override suspend fun merchantsOwing(network: Network, limit: Int): List<MerchantId> =
        database.tx("settlements.merchants-owing") {
            CommissionsTable
                .select(CommissionsTable.merchantId)
                .where { unswept(network) }
                .withDistinct()
                .limit(limit)
                .map { MerchantId(it[CommissionsTable.merchantId]) }
                .toList()
        }

    override suspend fun stamp(
        merchantId: MerchantId,
        network: Network,
        settlementId: UUID,
    ): Stamped =
        database.tx("settlements.stamp") {
            val taken =
                CommissionsTable
                    .updateReturning(
                        returning = listOf(CommissionsTable.platformAssetMinor),
                        where = { (CommissionsTable.merchantId eq merchantId.value) and unswept(network) },
                    ) {
                        it[CommissionsTable.settlementId] = settlementId
                    }.map { it[CommissionsTable.platformAssetMinor] }
                    .toList()
            Stamped(taken.sum(), taken.size)
        }

    override suspend fun sent(
        settlementId: UUID,
        custodyTxId: String,
        at: Instant,
    ) {
        database.tx("settlements.sent") {
            FeeSettlementsTable.update({ FeeSettlementsTable.settlementId eq settlementId }) {
                it[FeeSettlementsTable.custodyTxId] = custodyTxId
                it[updatedAt] = at
            }
        }
    }

    override suspend fun confirm(
        settlementId: UUID,
        txHash: String?,
        at: Instant,
    ): Boolean =
        database.tx("settlements.confirm") {
            val confirmed =
                FeeSettlementsTable.update({ pending(settlementId) }) {
                    it[state] = SettlementState.CONFIRMED.name
                    it[FeeSettlementsTable.txHash] = txHash
                    it[updatedAt] = at
                } > 0
            if (confirmed) {
                CommissionsTable.update({ CommissionsTable.settlementId eq settlementId }) { it[settledAt] = at }
            }
            confirmed
        }

    override suspend fun fail(
        settlementId: UUID,
        reason: String,
        at: Instant,
    ): Boolean =
        database.tx("settlements.fail") {
            val failed =
                FeeSettlementsTable.update({ pending(settlementId) }) {
                    it[state] = SettlementState.FAILED.name
                    it[failureReason] = reason
                    it[updatedAt] = at
                } > 0
            if (failed) {
                CommissionsTable.update({ CommissionsTable.settlementId eq settlementId }) {
                    it[CommissionsTable.settlementId] = null
                }
            }
            failed
        }

    override suspend fun ofMerchant(
        merchantId: MerchantId,
        network: Network,
        period: Period,
        limit: Int,
    ): List<Settlement> =
        database.tx("settlements.of-merchant") {
            FeeSettlementsTable
                .selectAll()
                .where {
                    (FeeSettlementsTable.merchantId eq merchantId.value) and
                        (FeeSettlementsTable.network eq network.name) and
                        (FeeSettlementsTable.day greaterEq period.from) and
                        (FeeSettlementsTable.day lessEq period.to)
                }.orderBy(FeeSettlementsTable.day to SortOrder.DESC, FeeSettlementsTable.settlementId to SortOrder.DESC)
                .limit(limit)
                .map { it.toSettlement() }
                .toList()
        }

    private fun pending(settlementId: UUID): Op<Boolean> =
        (FeeSettlementsTable.settlementId eq settlementId) and
            (FeeSettlementsTable.state eq SettlementState.PENDING.name)

    /** Commissions on [network] that owe something and that no sweep has taken over. */
    private fun unswept(network: Network): Op<Boolean> =
        (CommissionsTable.network eq network.name) and
            CommissionsTable.settledAt.isNull() and
            CommissionsTable.settlementId.isNull()

    private fun ResultRow.toSettlement() =
        Settlement(
            settlementId = this[FeeSettlementsTable.settlementId],
            merchantId = MerchantId(this[FeeSettlementsTable.merchantId]),
            network = Network.valueOf(this[FeeSettlementsTable.network]),
            day = this[FeeSettlementsTable.day],
            amountMinor = this[FeeSettlementsTable.amountMinor],
            orders = this[FeeSettlementsTable.orders],
            state = SettlementState.valueOf(this[FeeSettlementsTable.state]),
            custodyTxId = this[FeeSettlementsTable.custodyTxId],
            txHash = this[FeeSettlementsTable.txHash],
            failureReason = this[FeeSettlementsTable.failureReason],
            createdAt = this[FeeSettlementsTable.createdAt],
        )
}

/**
 * A merchant's completed exchanges: `ramp.commissions` joined to its order, so the lines and the totals come from the
 * same rows and can't disagree. An exchange belongs to the Nairobi day its commission was earned (the release
 * confirmed). Served by `commissions_merchant_idx`.
 */
class StatementRepositoryImpl(private val database: Database) : StatementRepository {
    private val rows =
        CommissionsTable.join(OrdersTable, JoinType.INNER, CommissionsTable.orderId, OrdersTable.orderId)

    override suspend fun lines(
        merchantId: MerchantId,
        network: Network,
        period: Period,
        after: PageCursor?,
        limit: Int,
    ): List<StatementLine> =
        database.tx("statement.lines") {
            rows
                .selectAll()
                .where {
                    val inPeriod = inPeriod(merchantId, network, period)
                    if (after == null) {
                        inPeriod
                    } else {
                        inPeriod and after(after, CommissionsTable.earnedAt, CommissionsTable.orderId)
                    }
                }.orderBy(CommissionsTable.earnedAt to SortOrder.DESC, CommissionsTable.orderId to SortOrder.DESC)
                .limit(limit)
                .map { it.toLine() }
                .toList()
        }

    override suspend fun totals(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): StatementTotals =
        database.tx("statement.totals") {
            val orders = CommissionsTable.orderId.count()
            val fiat = OrdersTable.fiatAmountMinor.sum()
            val asset = OrdersTable.assetAmountMinor.sum()
            val fees = OrdersTable.feeMinor.sum()
            val merchant = CommissionsTable.merchantMinor.sum()
            val platform = CommissionsTable.platformMinor.sum()
            val platformAsset = CommissionsTable.platformAssetMinor.sum()
            // One row per direction: buying's shillings came in and its USDC went out; selling's the other way.
            val byDirection =
                rows
                    .select(OrdersTable.direction, orders, fiat, asset, fees, merchant, platform, platformAsset)
                    .where { inPeriod(merchantId, network, period) }
                    .groupBy(OrdersTable.direction)
                    .toList()
            val buying = byDirection.firstOrNull { it[OrdersTable.direction] == Direction.ON_RAMP.name }
            val selling = byDirection.firstOrNull { it[OrdersTable.direction] == Direction.OFF_RAMP.name }
            val swept =
                rows
                    .select(platformAsset)
                    .where { inPeriod(merchantId, network, period) and CommissionsTable.settledAt.isNotNull() }
                    .single()[platformAsset] ?: 0
            StatementTotals(
                orders = byDirection.sumOf { it[orders].toInt() },
                collectedMinor = buying?.get(fiat) ?: 0,
                soldMinor = buying?.get(asset) ?: 0,
                feesMinor = byDirection.sumOf { it[fees] ?: 0 },
                merchantMinor = byDirection.sumOf { it[merchant] ?: 0 },
                platformMinor = byDirection.sumOf { it[platform] ?: 0 },
                platformAssetMinor = byDirection.sumOf { it[platformAsset] ?: 0 },
                sweptAssetMinor = swept,
                paidOutMinor = selling?.get(fiat) ?: 0,
                boughtMinor = selling?.get(asset) ?: 0,
            )
        }

    private fun inPeriod(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): Op<Boolean> =
        (CommissionsTable.merchantId eq merchantId.value) and
            (CommissionsTable.network eq network.name) and
            (CommissionsTable.earnedAt greaterEq Periods.start(period)) and
            (CommissionsTable.earnedAt less Periods.end(period))

    private fun ResultRow.toLine() =
        StatementLine(
            orderId = RampOrderId(this[CommissionsTable.orderId]),
            completedAt = this[CommissionsTable.earnedAt],
            payerPhone = payer(this[OrdersTable.payerPhone]),
            paidMinor = this[OrdersTable.fiatAmountMinor],
            releasedMinor = this[OrdersTable.assetAmountMinor],
            rate = BigDecimal(this[OrdersTable.rate]),
            feeMinor = this[OrdersTable.feeMinor],
            merchantMinor = this[CommissionsTable.merchantMinor],
            platformMinor = this[CommissionsTable.platformMinor],
            platformAssetMinor = this[CommissionsTable.platformAssetMinor],
            // A sweep that has only been opened hasn't collected anything yet: the share is still held.
            settlementId = this[CommissionsTable.settlementId].takeIf { this[CommissionsTable.settledAt] != null },
            txHash = this[OrdersTable.txHash],
            direction = Direction.valueOf(this[OrdersTable.direction]),
        )
}
