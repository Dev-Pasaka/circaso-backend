package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.tables.CommissionsTable
import com.circaso.ramp.data.tables.OrdersTable
import com.circaso.ramp.domain.models.CommissionFigures
import com.circaso.ramp.domain.models.DayTotals
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.StaffOrder
import com.circaso.ramp.domain.models.StaffOrderFilter
import com.circaso.ramp.domain.repositories.InsightRepository
import com.circaso.ramp.domain.support.Periods
import com.circaso.shared.Network
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.time.Instant
import java.time.LocalDate

/**
 * A merchant's activity for staff. Completed exchanges are `ramp.commissions` joined to its order, bounded by when the
 * commission was earned: exactly the rows, and the bounds, the merchant's own statement reads
 * ([StatementRepositoryImpl]), here grouped by Nairobi day. Orders that didn't complete are bounded by when they were
 * made. Served by `commissions_merchant_idx` and `orders_merchant_idx`.
 */
class InsightRepositoryImpl(private val database: Database) : InsightRepository {
    private val exchanges =
        CommissionsTable.join(OrdersTable, JoinType.INNER, CommissionsTable.orderId, OrdersTable.orderId)

    override suspend fun unfinished(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): Map<OrderState, Int> =
        database.tx("insights.unfinished") {
            val orders = OrdersTable.orderId.count()
            OrdersTable
                .select(OrdersTable.state, orders)
                .where { unfinishedIn(merchantId, network, period) }
                .groupBy(OrdersTable.state)
                .map { OrderState.valueOf(it[OrdersTable.state]) to it[orders].toInt() }
                .toList()
                .toMap()
        }

    override suspend fun unfinishedByDay(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): Map<LocalDate, Int> =
        database.tx("insights.unfinished-by-day") {
            val day = NairobiDay(OrdersTable.createdAt)
            val orders = OrdersTable.orderId.count()
            OrdersTable
                .select(day, orders)
                .where { unfinishedIn(merchantId, network, period) }
                .groupBy(day)
                .map { it[day] to it[orders].toInt() }
                .toList()
                .toMap()
        }

    override suspend fun completedByDay(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): List<DayTotals> =
        database.tx("insights.completed-by-day") {
            val day = NairobiDay(CommissionsTable.earnedAt)
            val completed = CommissionsTable.orderId.count()
            val collected = OrdersTable.fiatAmountMinor.sum()
            val sold = OrdersTable.assetAmountMinor.sum()
            val fees = OrdersTable.feeMinor.sum()
            val merchant = CommissionsTable.merchantMinor.sum()
            val platform = CommissionsTable.platformMinor.sum()
            exchanges
                .select(day, completed, collected, sold, fees, merchant, platform)
                .where {
                    (CommissionsTable.merchantId eq merchantId.value) and
                        (CommissionsTable.network eq network.name) and
                        (CommissionsTable.earnedAt greaterEq Periods.start(period)) and
                        (CommissionsTable.earnedAt less Periods.end(period))
                }.groupBy(day)
                .orderBy(day to SortOrder.ASC)
                .map {
                    DayTotals(
                        day = it[day],
                        completed = it[completed].toInt(),
                        collectedMinor = it[collected] ?: 0,
                        soldMinor = it[sold] ?: 0,
                        feesMinor = it[fees] ?: 0,
                        merchantMinor = it[merchant] ?: 0,
                        platformMinor = it[platform] ?: 0,
                    )
                }.toList()
        }

    override suspend fun orders(
        filter: StaffOrderFilter,
        after: PageCursor?,
        limit: Int,
    ): List<StaffOrder> =
        database.tx("insights.orders") {
            OrdersTable
                .join(CommissionsTable, JoinType.LEFT, OrdersTable.orderId, CommissionsTable.orderId)
                .selectAll()
                .where {
                    val asked = matching(filter)
                    if (after == null) asked else asked and after(after, OrdersTable.createdAt, OrdersTable.orderId)
                }.orderBy(OrdersTable.createdAt to SortOrder.DESC, OrdersTable.orderId to SortOrder.DESC)
                .limit(limit)
                .map { StaffOrder(it.toOrder(), it.toCommission()) }
                .toList()
        }

    private fun matching(filter: StaffOrderFilter): Op<Boolean> {
        var asked =
            (OrdersTable.merchantId eq filter.merchantId.value) and (OrdersTable.network eq filter.network.name)
        filter.period?.let { asked = asked and madeIn(it) }
        filter.state?.let { asked = asked and (OrdersTable.state eq it.name) }
        return asked
    }

    private fun unfinishedIn(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): Op<Boolean> =
        (OrdersTable.merchantId eq merchantId.value) and
            (OrdersTable.network eq network.name) and
            madeIn(period) and
            (OrdersTable.state neq OrderState.COMPLETED.name)

    private fun madeIn(period: Period): Op<Boolean> =
        (OrdersTable.createdAt greaterEq Periods.start(period)) and (OrdersTable.createdAt less Periods.end(period))

    /** The joined commission, when the order has one (the join is a left one: an unfinished order has none). */
    private fun ResultRow.toCommission(): CommissionFigures? {
        val earnedAt = getOrNull(CommissionsTable.earnedAt) ?: return null
        return CommissionFigures(
            earnedAt = earnedAt,
            merchantMinor = this[CommissionsTable.merchantMinor],
            platformMinor = this[CommissionsTable.platformMinor],
            platformAssetMinor = this[CommissionsTable.platformAssetMinor],
            // As on the statement: a sweep that has only been opened hasn't collected anything yet.
            settlementId = this[CommissionsTable.settlementId].takeIf { this[CommissionsTable.settledAt] != null },
        )
    }
}

/**
 * `(<instant> AT TIME ZONE 'Africa/Nairobi')::date`: the Nairobi day of an instant, whatever the session's time zone
 * (East Africa Time, UTC+3 with no daylight saving: [Periods.MERCHANT_ZONE]). The DSL has no such function; the
 * expression inside is the DSL's own column, so nothing is spliced in.
 */
private class NairobiDay(private val instant: Expression<Instant>) : ExpressionWithColumnType<LocalDate>() {
    override val columnType: IColumnType<LocalDate> = JavaLocalDateColumnType()

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("((")
        queryBuilder.append(instant)
        queryBuilder.append(") AT TIME ZONE 'Africa/Nairobi')::date")
    }
}
