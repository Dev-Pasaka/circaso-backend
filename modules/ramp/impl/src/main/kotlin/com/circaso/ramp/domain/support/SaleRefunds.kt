package com.circaso.ramp.domain.support

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.api.TreasuryTransaction
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.integrations.api.TreasuryTransfer
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.shared.Resource
import java.math.BigInteger
import java.time.Clock
import java.util.UUID

/**
 * A sale's refund (decision 2026-11-23), in the **ops process only**, which alone can sign for the merchants' wallets:
 * the USDC the custodian recorded arriving for the sale ([Order.fundedMinor]) goes back from the merchant's float to
 * the user's own wallet it came from ([Order.refundAddress]), with an idempotency key derived from the order and
 * `refund`, so however often it is asked for, it is sent once. A sale is `REFUNDING` only once nothing can have been
 * paid out ([SaleFlow.refund]).
 *
 * `REFUNDING` (sent: the custodian's transaction kept on the order) → `REFUNDED` when the chain confirms it. When the
 * custodian can't be reached the sale stays as it is and the next run asks again with the same key; when it refuses,
 * or the chain fails the transaction, a person looks, with the USDC still held.
 */
class SaleRefunds(
    private val orders: OrderRepository,
    private val wallets: MerchantWalletRepository,
    private val releases: Releases,
    private val flow: SaleFlow,
    private val clock: Clock,
) {
    /** Sends the refund of a `REFUNDING` sale that hasn't been sent, or follows the one that has. */
    suspend fun advance(treasury: TreasuryCustody, order: Order): Order {
        if (order.state != OrderState.REFUNDING) return order
        return if (order.custodyTxId == null) send(treasury, order) else follow(treasury, order)
    }

    private suspend fun send(treasury: TreasuryCustody, order: Order): Order {
        val amount = order.fundedMinor ?: return flow.review(order, REFUND_UNKNOWN)
        val destination = order.refundAddress ?: return flow.review(order, REFUND_UNKNOWN)
        val wallet =
            wallets.find(order.merchantId, order.network) ?: return flow.review(order, "merchant_wallet_missing")
        val token =
            when (val found = releases.usdcIn(treasury, order.network, wallet.custodyWalletId, wallet.blockchain)) {
                is Releases.Token.Found -> found.balance
                Releases.Token.Unknown -> return order
                Releases.Token.Absent -> return flow.review(order, REFUND_SHORT)
            }
        val transfer =
            TreasuryTransfer(
                walletId = wallet.custodyWalletId,
                blockchain = order.blockchain,
                tokenId = token.tokenId,
                decimals = token.decimals,
                amountMinor = BigInteger.valueOf(amount),
                destinationAddress = destination,
                refId = order.orderId.toString(),
                idempotencyKey = refundKey(order.orderId),
            )
        return when (val sent = treasury.send(order.network, transfer)) {
            is Resource.Error -> {
                // Unavailable: the outcome is unknown, so the same key is asked again on the next run.
                if (sent.error is IntegrationError.Unavailable) order else flow.review(order, REFUND_REFUSED)
            }

            is Resource.Success -> {
                val patch = OrderPatch(custodyTxId = sent.data.transactionId)
                val noted = orders.annotate(order.orderId, OrderState.REFUNDING, patch, clock.instant())
                apply(noted ?: flow.current(order), sent.data)
            }
        }
    }

    private suspend fun follow(treasury: TreasuryCustody, order: Order): Order {
        val transactionId = order.custodyTxId ?: return order
        return when (val read = treasury.transaction(order.network, transactionId)) {
            is Resource.Error -> order
            is Resource.Success -> apply(order, read.data)
        }
    }

    private suspend fun apply(order: Order, transaction: TreasuryTransaction): Order {
        if (order.state != OrderState.REFUNDING) return order
        return when (transaction.state) {
            TreasuryTransactionState.CONFIRMED -> flow.refunded(order, transaction.txHash)
            TreasuryTransactionState.FAILED -> flow.review(order, REFUND_FAILED)
            TreasuryTransactionState.IN_FLIGHT -> order
        }
    }

    companion object {
        /** The custodian wants a UUID of its own kind: a name-based one from the order, as the release's. */
        fun refundKey(orderId: RampOrderId): UUID = UUID.nameUUIDFromBytes("ramp:refund:$orderId".toByteArray())

        const val REFUND_UNKNOWN = "refund_unknown"
        const val REFUND_SHORT = "refund_float_short"
        const val REFUND_REFUSED = "refund_refused"
        const val REFUND_FAILED = "refund_failed"
    }
}
