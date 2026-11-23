package com.circaso.ramp.domain.usecases

import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.Resolution
import com.circaso.ramp.domain.models.ResolveCommand
import com.circaso.ramp.domain.models.Resolved
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.support.OrderFlow
import com.circaso.ramp.domain.support.SaleFlow
import com.circaso.ramp.domain.support.SaleFunding
import com.circaso.ramp.domain.support.SalePayouts
import com.circaso.shared.Resource
import java.time.Clock

/**
 * RO4: staff resolve an order in `REVIEW` (decision 2026-11-23, both directions), on the ops gateway, an ADMIN with a
 * reason, audited by the ops module. Three outcomes:
 *
 * - **Mark paid** (with the M-Pesa receipt staff saw). A sale: completed as a payout the provider confirmed would be
 *   (the user's USDC becomes the merchant's, the commission is recorded). A purchase: `PAID`, and the ops process
 *   releases the USDC; refused once a release was sent.
 * - **Refund.** A sale: its USDC goes back to the user from the merchant's float (`REFUNDING`; the ops process sends
 *   it). Refused when the USDC never arrived, when a refund was already sent, and when the provider last said the
 *   payout was paid. **Staff are the ones who decide a payout didn't go out** (the merchant's M-Pesa statement, the
 *   provider's dashboard): that is what `REVIEW` is for. A purchase: closed unreleased (`FAILED`), its float freed;
 *   the shillings are returned by hand, outside the ramp.
 * - **Re-check.** The provider is asked again by the app's process, which alone holds the merchant's keys, on its next
 *   sweep (seconds): a payout or payment the provider confirms moves the order on; anything else leaves it with staff.
 *   The answer is the order as it stands now, with `providerSaid` `queued`.
 */
class ResolveOrder(
    private val orders: OrderRepository,
    private val merchants: MerchantRepository,
    private val flow: OrderFlow,
    private val sales: SaleFlow,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: ResolveCommand): Resource<Resolved> {
        val order = orders.find(command.orderId) ?: return Resource.error(RampError.OrderNotFound)
        if (order.state != OrderState.REVIEW) return invalid("it isn't waiting for a person")
        return when (command.resolution) {
            Resolution.RECHECK -> recheck(order)
            Resolution.MARK_PAID -> markPaid(order, command.receipt?.trim()?.takeIf { it.isNotEmpty() })
            Resolution.REFUND -> refund(order)
        }
    }

    private suspend fun recheck(order: Order): Resource<Resolved> {
        if (!orders.requestRecheck(order.orderId, clock.instant())) return invalid("it isn't waiting for a person")
        return resolved(order, QUEUED)
    }

    private suspend fun markPaid(order: Order, receipt: String?): Resource<Resolved> {
        if (receipt == null) return Resource.error(RampError.ReceiptRequired)
        if (orders.receiptTaken(receipt, order.orderId)) return invalid("that receipt already settled another order")
        return when (order.direction) {
            Direction.OFF_RAMP -> {
                if (order.fundedMinor == null) return invalid("the user's USDC never arrived")
                if (order.custodyTxId != null) return invalid("its USDC was already sent back")
                val completed = sales.complete(order, receipt, setOf(OrderState.REVIEW))
                if (completed.state != OrderState.COMPLETED) return invalid("it moved on meanwhile")
                resolved(completed, null)
            }

            Direction.ON_RAMP -> {
                if (order.custodyTxId != null) return invalid("its USDC was already sent: follow the release")
                val paid = flow.paidFromReview(order, receipt) ?: return invalid("it moved on meanwhile")
                resolved(paid, null)
            }
        }
    }

    private suspend fun refund(order: Order): Resource<Resolved> =
        when (order.direction) {
            Direction.OFF_RAMP -> {
                refundSale(order)
            }

            Direction.ON_RAMP -> {
                if (order.custodyTxId != null) {
                    invalid("its USDC was already sent")
                } else {
                    flow.closeFromReview(order, RESOLVED_REFUND)?.let { resolved(it, null) }
                        ?: invalid("it moved on meanwhile")
                }
            }
        }

    private suspend fun refundSale(order: Order): Resource<Resolved> {
        if (order.fundedMinor == null) return invalid("the user's USDC never arrived: there is nothing to send back")
        if (order.custodyTxId != null) return invalid("a refund was already sent: it failed on chain, send it by hand")
        if (order.payoutOutcome == SaleFlow.PAID) return Resource.error(RampError.PayoutMayBePaid)
        val refunding = sales.refund(order, RESOLVED_REFUND, setOf(OrderState.REVIEW)) ?: return invalid("it moved on")
        return resolved(refunding, null)
    }

    private fun <T> invalid(problem: String): Resource<T> = Resource.error(RampError.ResolutionInvalid(problem))

    private suspend fun resolved(order: Order, said: String?): Resource<Resolved> {
        val name = merchants.find(order.merchantId)?.displayName.orEmpty()
        return Resource.success(Resolved(OrderView(order, name), said))
    }

    companion object {
        const val RESOLVED_REFUND = "resolved_refund"
        const val QUEUED = "queued"
    }
}

/**
 * The app's half of staff's re-check (RO4): every order staff asked about is checked with its provider, here where the
 * merchant's keys are, and the request is done whatever the answer. A sale's payout is looked up (a confirmed payout
 * completes it) or, while its funding is still unconfirmed, its transfer is checked; a purchase's payment is asked
 * about ([OrderFlow.recheck]).
 */
class RunRechecks(
    private val orders: OrderRepository,
    private val flow: OrderFlow,
    private val payouts: SalePayouts,
    private val funding: SaleFunding,
) {
    suspend operator fun invoke(): Int {
        val asked = orders.rechecks(LIMIT)
        asked.forEach { order ->
            when {
                order.direction == Direction.ON_RAMP -> flow.recheck(order)
                order.payoutReference != null -> payouts.lookUp(order)
                order.walletTransferId != null && order.fundedMinor == null -> funding.confirm(order)
            }
            orders.clearRecheck(order.orderId)
        }
        return asked.size
    }

    private companion object {
        const val LIMIT = 50
    }
}
