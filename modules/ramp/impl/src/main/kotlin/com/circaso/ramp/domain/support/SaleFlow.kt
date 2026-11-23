package com.circaso.ramp.domain.support

import com.circaso.ramp.api.OffRampCompleted
import com.circaso.ramp.api.OrderFailed
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.SaleStates
import com.circaso.ramp.domain.ports.FrameSender
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.RampCounters
import com.circaso.ramp.domain.ports.RampOutbox
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.PayoutHoldRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.shared.EventBus
import com.circaso.shared.envelope
import java.time.Clock

/**
 * The moves a sale makes (decision 2026-11-23), shared by every way in: R7 and R6, the wallet's event, the payout and
 * funding jobs, the provider's callback, staff, and the ops process's refunds. Each is a transition guard
 * ([SaleStates]) with what it holds or frees written in the same transaction, so a step happens once however often,
 * and from wherever, it is asked for.
 *
 * What a sale holds, and when it lets go:
 *
 * - **The merchant's shillings** (`payout_holds`: the payout and its fee) from the order until it completes (consumed)
 *   or ends without a payout (released), so two sales never count on the same shillings at the provider.
 * - **The user's USDC** (a `HOLD` reservation) from the moment the custodian records it in the merchant's float until
 *   the user is paid (consumed: the USDC is now the merchant's) or refunded (released, when the refund is confirmed on
 *   chain). While it is held it can't be sold to a buyer or swept as fees.
 */
class SaleFlow(
    private val orders: OrderRepository,
    private val reservations: ReservationRepository,
    private val payoutHolds: PayoutHoldRepository,
    private val commissions: CommissionRepository,
    private val transactor: Transactor,
    private val outbox: RampOutbox,
    private val bus: EventBus,
    private val frames: FrameSender,
    private val counters: RampCounters,
    private val alerts: RampAlerts,
    private val clock: Clock,
) {
    /**
     * Ends a sale the user never funded ([to]: `EXPIRED` by the deadline, `CANCELLED` by the user): the merchant's
     * shillings are freed and the day given back. Null when the sale is past waiting for its transfer. A transfer
     * that lands afterwards is refunded ([funded]).
     */
    suspend fun close(
        order: Order,
        to: OrderState,
        reason: String,
    ): Order? {
        val now = clock.instant()
        val event =
            bus.envelope(
                OrderFailed(order.orderId, order.userId, order.direction.name, reason, order.network.name),
            )
        val closed =
            transactor.run("sales.close") {
                val moved =
                    orders.move(
                        order.orderId,
                        to,
                        SaleStates.TO_CLOSED,
                        OrderPatch(failureReason = reason),
                        now,
                    )
                if (moved != null) {
                    payoutHolds.release(order.orderId, now)
                    outbox.write(event)
                }
                moved
            } ?: return null
        bus.publish(event)
        counters.giveBackDailySale(order.userId, order.network, Periods.day(order.createdAt), order.fiat.amountMinor)
        frames.orderUpdated(closed)
        return closed
    }

    /**
     * The custodian recorded [receivedMinor] USDC from the user in the merchant's float for this sale. The USDC is held
     * at once, whatever happens next:
     *
     * - the sale is waiting and the amount is exactly the order's: `FUNDED`;
     * - the sale is waiting and the amount is anything else: refunded, all of it (`amount_mismatch`);
     * - the sale already expired or was cancelled: refunded (`late_transfer`);
     * - the sale is with staff: the funding is written down and held for them to decide.
     */
    suspend fun funded(
        order: Order,
        receivedMinor: Long,
        txHash: String,
    ): Order {
        val now = clock.instant()
        val patch = OrderPatch(fundedMinor = receivedMinor, fundingTxHash = txHash, fundedAt = now)
        return when (order.state) {
            OrderState.AWAITING_TRANSFER -> {
                if (receivedMinor == order.assetMinor) {
                    fund(order, patch, receivedMinor)
                } else {
                    refund(order, AMOUNT_MISMATCH, setOf(OrderState.AWAITING_TRANSFER), patch, receivedMinor)
                        ?: current(order)
                }
            }

            OrderState.EXPIRED, OrderState.CANCELLED -> {
                refund(order, LATE_TRANSFER, setOf(order.state), patch, receivedMinor) ?: current(order)
            }

            OrderState.REVIEW -> {
                transactor.run("sales.funded-in-review") {
                    orders.annotate(order.orderId, OrderState.REVIEW, patch, now)?.also {
                        reservations.hold(it, receivedMinor, now)
                    }
                } ?: current(order)
            }

            else -> {
                order
            }
        }
    }

    private suspend fun fund(
        order: Order,
        patch: OrderPatch,
        receivedMinor: Long,
    ): Order {
        val now = clock.instant()
        val funded =
            transactor.run("sales.funded") {
                orders.move(order.orderId, OrderState.FUNDED, setOf(OrderState.AWAITING_TRANSFER), patch, now)?.also {
                    reservations.hold(it, receivedMinor, now)
                }
            } ?: return current(order)
        frames.orderUpdated(funded)
        return funded
    }

    /**
     * Decides that the user gets their USDC back: `REFUNDING`, for the ops process to send. Only from where no payout
     * can be out ([from] is narrowed to [SaleStates.TO_REFUNDING]; a `PAYING_OUT` sale gets here only after the
     * provider itself said its payout failed). The merchant's shillings are freed; [holdMinor] is held when the
     * funding arrives with the decision. Null when the sale wasn't in [from].
     */
    suspend fun refund(
        order: Order,
        reason: String,
        from: Set<OrderState>,
        patch: OrderPatch = OrderPatch(),
        holdMinor: Long? = null,
    ): Order? {
        val now = clock.instant()
        val allowed = from intersect SaleStates.TO_REFUNDING
        val refunding =
            transactor.run("sales.refund") {
                val moved =
                    orders.move(
                        order.orderId,
                        OrderState.REFUNDING,
                        allowed,
                        patch.copy(failureReason = reason),
                        now,
                    )
                if (moved != null) {
                    payoutHolds.release(order.orderId, now)
                    holdMinor?.let { reservations.hold(moved, it, now) }
                }
                moved
            } ?: return null
        // The day was given back when a never-funded sale closed; any other sale gives it back now.
        if (order.state !in CLOSED) {
            counters.giveBackDailySale(
                order.userId,
                order.network,
                Periods.day(order.createdAt),
                order.fiat.amountMinor,
            )
        }
        frames.orderUpdated(refunding)
        return refunding
    }

    /** Stops the sale for a person. Whatever it holds stays held: nothing more moves by itself. */
    suspend fun review(order: Order, reason: String): Order {
        val patch = OrderPatch(failureReason = reason)
        val moved =
            orders.move(order.orderId, OrderState.REVIEW, SaleStates.TO_REVIEW, patch, clock.instant())
                ?: return current(order)
        alerts.needsReview(moved, reason)
        frames.orderUpdated(moved)
        return moved
    }

    /**
     * The provider itself said the payout was paid ([from] `PAYING_OUT`), or staff saw its receipt (`REVIEW`): the sale
     * is completed, the user's USDC becomes the merchant's (its hold consumed), the shillings hold is consumed, the
     * commission recorded (Circaso's share stays in the float until the daily sweep, as for a purchase) and
     * `ramp.OffRampCompleted` written, in one transaction. A receipt that already completed another order sends the
     * sale to a person instead.
     */
    suspend fun complete(
        order: Order,
        receipt: String?,
        from: Set<OrderState>,
    ): Order {
        if (receipt != null && orders.receiptTaken(receipt, order.orderId)) return review(order, RECEIPT_REUSED)
        val now = clock.instant()
        val event =
            bus.envelope(
                OffRampCompleted(
                    orderId = order.orderId,
                    userId = order.userId,
                    merchantId = order.merchantId,
                    network = order.network.name,
                    blockchain = order.blockchain,
                    asset = RampSettings.ASSET,
                    assetAmountMinor = (order.fundedMinor ?: order.assetMinor).toString(),
                    fiatCurrency = order.fiat.currency,
                    fiatAmountMinor = order.fiat.amountMinor.toString(),
                ),
            )
        val patch = OrderPatch(mpesaReceipt = receipt, payoutOutcome = PAID)
        val allowed = from intersect SaleStates.TO_COMPLETED
        val completed =
            transactor.run("sales.complete") {
                val moved = orders.move(order.orderId, OrderState.COMPLETED, allowed, patch, now)
                if (moved != null) {
                    reservations.consume(order.orderId, now)
                    payoutHolds.consume(order.orderId, now)
                    commissions.insert(moved, Fees.split(moved), now)
                    outbox.write(event)
                }
                moved
            } ?: return current(order)
        bus.publish(event)
        frames.orderUpdated(completed)
        return completed
    }

    /**
     * The ops process: the refund reached the user's wallet on chain. `REFUNDED`, the USDC hold released (it left the
     * float), `ramp.OrderFailed` with the reason the sale was refunded for. The reason is written on the step too, so
     * the merchant's statistics see why it ended.
     */
    suspend fun refunded(order: Order, txHash: String): Order {
        val now = clock.instant()
        val reason = order.failureReason ?: REFUNDED
        val event =
            bus.envelope(
                OrderFailed(order.orderId, order.userId, order.direction.name, reason, order.network.name),
            )
        val patch = OrderPatch(txHash = txHash.ifEmpty { null }, failureReason = reason)
        val done =
            transactor.run("sales.refunded") {
                val moved = orders.move(order.orderId, OrderState.REFUNDED, SaleStates.TO_REFUNDED, patch, now)
                if (moved != null) {
                    reservations.release(order.orderId, now)
                    payoutHolds.release(order.orderId, now)
                    outbox.write(event)
                }
                moved
            } ?: return current(order)
        bus.publish(event)
        frames.orderUpdated(done)
        return done
    }

    suspend fun current(order: Order): Order = orders.find(order.orderId) ?: order

    companion object {
        /** The user's USDC arrived, but not the amount the sale was for: all of it goes back. */
        const val AMOUNT_MISMATCH = "amount_mismatch"

        /** The user's USDC arrived after the sale had expired or been cancelled: it goes back. */
        const val LATE_TRANSFER = "late_transfer"

        /** The user never sent the USDC by the deadline. Not the merchant's doing: never counted. */
        const val NEVER_FUNDED = "never_funded"

        /** The merchant's payout failed for good, or couldn't be sent within the payout window: it counts. */
        const val PAYOUT_FAILED = "payout_failed"

        /** The merchant was offline (no keys) for the whole payout window: it counts, as a missed payout. */
        const val PAYOUT_MISSED = "payout_missed"

        /** The provider was out of reach for the whole payout window: not the merchant's doing. */
        const val PAYOUT_UNAVAILABLE = "payout_unavailable"

        /** The provider refused the payout for the request itself (the amount, the provider): not counted. */
        const val PAYOUT_REJECTED = "payout_rejected"

        const val RECEIPT_REUSED = "receipt_reused"
        const val PAID = "PAID"
        const val REFUNDED = "refunded"

        private val CLOSED = setOf(OrderState.EXPIRED, OrderState.CANCELLED)
    }
}
