package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseException
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.data.tables.OrdersTable
import com.circaso.ramp.data.tables.QuotesTable
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PaidOutcome
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.QuoteRepository
import com.circaso.shared.FiatAmount
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class QuoteRepositoryImpl(private val database: Database) : QuoteRepository {
    override suspend fun insert(quote: Quote) {
        database.tx("quotes.insert") {
            QuotesTable.insert {
                it[quoteId] = quote.quoteId.value
                it[userId] = quote.userId.value
                it[network] = quote.network.name
                it[direction] = quote.direction.name
                it[blockchain] = quote.blockchain
                it[fiatCurrency] = quote.fiat.currency
                it[fiatAmountMinor] = quote.fiat.amountMinor
                it[asset] = RampSettings.ASSET
                it[assetAmountMinor] = quote.assetMinor
                it[rate] = quote.rate.toPlainString()
                it[feeMinor] = quote.feeMinor
                it[feeBps] = quote.feeBps
                it[merchantShareBps] = quote.merchantShareBps
                it[expiresAt] = quote.expiresAt
            }
        }
    }

    override suspend fun find(quoteId: QuoteId, now: Instant): Quote? =
        database.tx("quotes.find") {
            QuotesTable
                .selectAll()
                .where { (QuotesTable.quoteId eq quoteId.value) and (QuotesTable.expiresAt greater now) }
                .map { it.toQuote() }
                .singleOrNull()
        }

    private fun ResultRow.toQuote() =
        Quote(
            quoteId = QuoteId(this[QuotesTable.quoteId]),
            userId = UserId(this[QuotesTable.userId]),
            network = Network.valueOf(this[QuotesTable.network]),
            direction = Direction.valueOf(this[QuotesTable.direction]),
            blockchain = this[QuotesTable.blockchain],
            fiat = FiatAmount(this[QuotesTable.fiatAmountMinor], this[QuotesTable.fiatCurrency]),
            assetMinor = this[QuotesTable.assetAmountMinor],
            rate = BigDecimal(this[QuotesTable.rate]),
            feeMinor = this[QuotesTable.feeMinor],
            feeBps = this[QuotesTable.feeBps],
            merchantShareBps = this[QuotesTable.merchantShareBps],
            expiresAt = this[QuotesTable.expiresAt],
        )
}

/** Rows after ([at], [id]) in a newest-first list ordered by ([time], [key]). */
internal fun after(
    cursor: PageCursor,
    time: Column<Instant>,
    key: Column<UUID>,
): Op<Boolean> = (time less cursor.at) or ((time eq cursor.at) and (key less cursor.id))

/**
 * `ramp.orders`. A quote makes one order (`quote_id` is unique) and a user has one open order
 * (`orders_user_open_key`), so an insert that finds either adds nothing. A state moves only by the guard
 * `WHERE order_id = ? AND state IN (<allowed predecessors>)`: whoever's update finds the row made the step.
 */
class OrderRepositoryImpl(private val database: Database) : OrderRepository {
    override suspend fun insert(order: Order): Boolean =
        database.tx("orders.insert") {
            OrdersTable
                .insertIgnore {
                    it[orderId] = order.orderId.value
                    it[userId] = order.userId.value
                    it[merchantId] = order.merchantId.value
                    it[quoteId] = order.quoteId.value
                    it[network] = order.network.name
                    it[direction] = order.direction.name
                    it[state] = order.state.name
                    it[blockchain] = order.blockchain
                    it[fiatCurrency] = order.fiat.currency
                    it[fiatAmountMinor] = order.fiat.amountMinor
                    it[asset] = RampSettings.ASSET
                    it[assetAmountMinor] = order.assetMinor
                    it[rate] = order.rate.toPlainString()
                    it[feeMinor] = order.feeMinor
                    it[feeBps] = order.feeBps
                    it[merchantShareBps] = order.merchantShareBps
                    it[collectionsProvider] = order.collectionsProvider
                    it[payerPhone] = order.payerPhone.e164
                    it[destinationAddress] = order.destinationAddress
                    it[paymentDeadline] = order.paymentDeadline
                    it[createdAt] = order.createdAt
                    it[updatedAt] = order.updatedAt
                }.insertedCount > 0
        }

    override suspend fun find(orderId: RampOrderId): Order? = database.tx("orders.find") { read(orderId) }

    override suspend fun byQuote(quoteId: QuoteId): Order? =
        database.tx("orders.by-quote") {
            OrdersTable
                .selectAll()
                .where { OrdersTable.quoteId eq quoteId.value }
                .map { it.toOrder() }
                .singleOrNull()
        }

    override suspend fun openOf(userId: UserId): Order? =
        database.tx("orders.open-of") {
            OrdersTable
                .selectAll()
                .where { (OrdersTable.userId eq userId.value) and (OrdersTable.state inList OPEN) }
                .map { it.toOrder() }
                .singleOrNull()
        }

    override suspend fun list(
        userId: UserId,
        network: Network,
        limit: Int,
        after: PageCursor?,
    ): List<Order> = database.tx("orders.list") { page(OrdersTable.userId eq userId.value, network, limit, after) }

    override suspend fun ofMerchant(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
        after: PageCursor?,
    ): List<Order> =
        database.tx("orders.of-merchant") { page(OrdersTable.merchantId eq merchantId.value, network, limit, after) }

    private suspend fun page(
        owner: Op<Boolean>,
        network: Network,
        limit: Int,
        after: PageCursor?,
    ): List<Order> =
        OrdersTable
            .selectAll()
            .where {
                val mine = owner and (OrdersTable.network eq network.name)
                if (after == null) mine else mine and after(after, OrdersTable.createdAt, OrdersTable.orderId)
            }.orderBy(OrdersTable.createdAt to SortOrder.DESC, OrdersTable.orderId to SortOrder.DESC)
            .limit(limit)
            .map { it.toOrder() }
            .toList()

    override suspend fun hasAwaitingPayment(merchantId: MerchantId): Boolean =
        database.tx("orders.has-awaiting-payment") {
            OrdersTable
                .select(OrdersTable.orderId)
                .where { (OrdersTable.merchantId eq merchantId.value) and (OrdersTable.state inList AWAITING) }
                .limit(1)
                .firstOrNull() != null
        }

    override suspend fun move(
        orderId: RampOrderId,
        to: OrderState,
        from: Set<OrderState>,
        patch: OrderPatch,
        at: Instant,
    ): Order? =
        database.tx("orders.move") {
            val moved =
                OrdersTable.update({
                    (OrdersTable.orderId eq orderId.value) and (OrdersTable.state inList from.map { it.name })
                }) {
                    it[state] = to.name
                    it[updatedAt] = at
                    patch.providerReference?.let { value -> it[providerReference] = value }
                    patch.checkoutId?.let { value -> it[checkoutId] = value }
                    patch.custodyTxId?.let { value -> it[custodyTxId] = value }
                    patch.txHash?.let { value -> it[txHash] = value }
                    patch.failureReason?.let { value -> it[failureReason] = value }
                }
            if (moved == 0) null else read(orderId)
        }

    override suspend fun markPaid(
        orderId: RampOrderId,
        receipt: String?,
        at: Instant,
    ): PaidOutcome =
        try {
            database.tx("orders.mark-paid") {
                val moved =
                    OrdersTable.update({
                        (OrdersTable.orderId eq orderId.value) and
                            (OrdersTable.state eq OrderState.PAYMENT_REQUESTED.name)
                    }) {
                        it[state] = OrderState.PAID.name
                        it[updatedAt] = at
                        it[mpesaReceipt] = receipt
                    }
                read(orderId)?.takeIf { moved > 0 }?.let { PaidOutcome.Moved(it) } ?: PaidOutcome.AlreadyMoved
            }
        } catch (e: DatabaseException) {
            // orders_receipt_key: this M-Pesa payment already settled another order.
            if (e.isUniqueViolation) PaidOutcome.ReceiptUsed else throw e
        }

    override suspend fun stale(
        network: Network,
        states: Set<OrderState>,
        before: Instant,
        limit: Int,
    ): List<Order> =
        database.tx("orders.stale") {
            OrdersTable
                .selectAll()
                .where {
                    (OrdersTable.state inList states.map { it.name }) and
                        (OrdersTable.updatedAt less before) and
                        (OrdersTable.network eq network.name)
                }.orderBy(OrdersTable.updatedAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toOrder() }
                .toList()
        }

    override suspend fun inReview(
        network: Network?,
        after: RampOrderId?,
        limit: Int,
    ): List<Order> =
        database.tx("orders.in-review") {
            OrdersTable
                .selectAll()
                .where {
                    val review: Op<Boolean> = OrdersTable.state eq OrderState.REVIEW.name
                    val onNetwork = if (network == null) review else review and (OrdersTable.network eq network.name)
                    if (after == null) onNetwork else onNetwork and (OrdersTable.orderId greater after.value)
                }.orderBy(OrdersTable.orderId to SortOrder.ASC)
                .limit(limit)
                .map { it.toOrder() }
                .toList()
        }

    private suspend fun read(orderId: RampOrderId): Order? =
        OrdersTable
            .selectAll()
            .where { OrdersTable.orderId eq orderId.value }
            .map { it.toOrder() }
            .singleOrNull()

    private companion object {
        val OPEN = OrderState.OPEN.map { it.name }
        val AWAITING = OrderState.AWAITING_PAYMENT.map { it.name }
    }
}

internal fun ResultRow.toOrder() =
    Order(
        orderId = RampOrderId(this[OrdersTable.orderId]),
        userId = UserId(this[OrdersTable.userId]),
        merchantId = MerchantId(this[OrdersTable.merchantId]),
        quoteId = QuoteId(this[OrdersTable.quoteId]),
        network = Network.valueOf(this[OrdersTable.network]),
        direction = Direction.valueOf(this[OrdersTable.direction]),
        state = OrderState.valueOf(this[OrdersTable.state]),
        blockchain = this[OrdersTable.blockchain],
        fiat = FiatAmount(this[OrdersTable.fiatAmountMinor], this[OrdersTable.fiatCurrency]),
        assetMinor = this[OrdersTable.assetAmountMinor],
        rate = BigDecimal(this[OrdersTable.rate]),
        feeMinor = this[OrdersTable.feeMinor],
        feeBps = this[OrdersTable.feeBps],
        merchantShareBps = this[OrdersTable.merchantShareBps],
        collectionsProvider = this[OrdersTable.collectionsProvider],
        payerPhone = payer(this[OrdersTable.payerPhone]),
        destinationAddress = this[OrdersTable.destinationAddress],
        providerReference = this[OrdersTable.providerReference],
        checkoutId = this[OrdersTable.checkoutId],
        mpesaReceipt = this[OrdersTable.mpesaReceipt],
        custodyTxId = this[OrdersTable.custodyTxId],
        txHash = this[OrdersTable.txHash],
        failureReason = this[OrdersTable.failureReason],
        paymentDeadline = this[OrdersTable.paymentDeadline],
        createdAt = this[OrdersTable.createdAt],
        updatedAt = this[OrdersTable.updatedAt],
    )

internal fun payer(stored: String): PhoneNumber =
    checkNotNull(PhoneNumber.parse(stored)) {
        "A stored payer number is E.164"
    }
