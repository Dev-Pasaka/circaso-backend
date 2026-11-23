package com.circaso.ramp.domain.support

import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.integrations.api.DepositQuery
import com.circaso.integrations.api.MerchantFloat
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.UsdcContracts
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.shared.Resource
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.Wallets
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.time.Clock
import java.util.UUID

/**
 * Whether a sale's USDC reached its merchant (decision 2026-11-23). The ramp confirms it **itself, from the
 * custodian's own record of the receiving wallet**, never from the app and never from the wallet's word alone:
 *
 * 1. The wallet's `wallet.TransferConfirmed` names the order (W4 `rampOrderId`): the transfer is recorded on the sale
 *    once (`orders_wallet_transfer_key`; the wallet already lets one transfer fund an order).
 * 2. The wallet's own record of the transfer ([Wallets.transfer]) must be the user's, confirmed, to this merchant's
 *    float address on the sale's chain, with a chain transaction hash.
 * 3. The custodian is asked what that merchant wallet received in that very chain transaction
 *    ([MerchantFloat.deposits]): an incoming, confirmed transfer **from the user's own wallet** of **the USDC contract
 *    the network's settings name**. Its amount, not the app's or the wallet's, is what the sale is funded with.
 *
 * Anything that can't be read yet (the wallet's record, the custodian, a deposit still on its way) leaves the sale as
 * it is: the funding sweep asks again. Anything that doesn't fit (another sender, another address, another coin) goes
 * to a person, with nothing sent or refunded on a guess. The wallet's own handling of Circle's webhook is untouched:
 * it still ignores every update of a developer-controlled wallet.
 */
class SaleFunding(
    private val orders: OrderRepository,
    private val wallets: Wallets,
    private val merchantWallets: MerchantWalletRepository,
    private val float: MerchantFloat,
    private val usdc: UsdcContracts,
    private val flow: SaleFlow,
    private val alerts: RampAlerts,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(SaleFunding::class.java)

    /** The wallet confirmed [transferId] funding [order]: record it, then check it with the custodian. */
    suspend fun transferConfirmed(order: Order, transferId: UUID): Order {
        if (order.direction != Direction.OFF_RAMP) return order
        val attached = orders.attachTransfer(order.orderId, transferId, clock.instant()) ?: return order
        if (attached.walletTransferId != transferId) {
            // The wallet funds an order with one transfer at a time, so this is a second transfer for a sale that
            // already has one (or a transfer that funds another sale): nothing here can refund it on its own.
            alerts.stuck("order ${order.orderId} transfer $transferId", "second_transfer")
            return attached
        }
        return confirm(attached)
    }

    /** Checks a sale's recorded transfer with the custodian and applies what it shows; nothing when it can't say yet. */
    suspend fun confirm(order: Order): Order {
        if (order.fundedMinor != null) return order
        val transferId = order.walletTransferId ?: return order
        val transfer = wallets.transfer(TransferId(transferId)) ?: return order
        if (transfer.state != CONFIRMED) return order
        val wallet = merchantWallets.find(order.merchantId, order.network) ?: return flow.review(order, MISMATCH)
        val fits =
            transfer.senderId == order.userId &&
                transfer.fromGroupId == null &&
                transfer.blockchain == order.blockchain &&
                sameAddress(transfer.destinationAddress, wallet.address)
        if (!fits) return flow.review(order, MISMATCH)
        val txHash = transfer.txHash?.takeIf { it.isNotBlank() } ?: return order
        val from = order.refundAddress ?: return flow.review(order, MISMATCH)
        val contract = usdc.contractOn(order.network, order.blockchain)
        if (contract == null) {
            alerts.stuck("order ${order.orderId} funding", "no USDC contract in the network's settings")
            return order
        }
        val query =
            DepositQuery(
                walletId = wallet.custodyWalletId,
                blockchain = order.blockchain,
                txHash = txHash,
                expectedFrom = from,
                expectedTokenAddress = contract,
                expectedAmountMinor = BigInteger.valueOf(order.assetMinor),
            )
        val deposits =
            when (val read = float.deposits(order.network, query)) {
                is Resource.Error -> return order
                is Resource.Success -> read.data.filter { it.state == CustodyTransactionState.CONFIRMED }
            }
        val fromUser = deposits.filter { sameAddress(it.sourceAddress, from) }
        if (fromUser.isEmpty()) return if (deposits.isEmpty()) order else flow.review(order, MISMATCH)
        val usdcIn = fromUser.filter { deposit -> deposit.tokenAddress?.let { sameAddress(it, contract) } == true }
        if (usdcIn.isEmpty()) return flow.review(order, WRONG_TOKEN)
        val received =
            usdcIn
                .sumOf { it.amount.amountMinor }
                .takeIf { it.bitLength() < Long.SIZE_BITS }
                ?.toLong()
                ?: return flow.review(order, MISMATCH)
        log.info(
            "ramp sale funded: order={} state={} exact={}",
            order.orderId,
            order.state,
            received == order.assetMinor,
        )
        return flow.funded(order, received, txHash)
    }

    /**
     * A sale whose transfer has waited for the custodian's record longer than [RampSettings.REVIEW_AFTER]·3: a
     * person looks. It keeps being checked: a funding that turns up later is still applied (and held for them).
     */
    suspend fun confirmOrReview(order: Order): Order {
        val confirmed = confirm(order)
        if (confirmed.fundedMinor != null || confirmed.state == OrderState.REVIEW) return confirmed
        val waited = confirmed.updatedAt.plus(UNCONFIRMED_AFTER).isBefore(clock.instant())
        val stuck = confirmed.walletTransferId != null && waited && confirmed.state != OrderState.REVIEW
        return if (stuck) flow.review(confirmed, FUNDING_UNCONFIRMED) else confirmed
    }

    companion object {
        /** The transfer isn't the user's, to this merchant, on this chain, or the record shows another sender. */
        const val MISMATCH = "transfer_mismatch"

        /** The user's transfer reached the merchant in another coin than the network's USDC. */
        const val WRONG_TOKEN = "wrong_token"

        /** The wallet confirmed the transfer, and the custodian still shows no deposit after a long wait. */
        const val FUNDING_UNCONFIRMED = "funding_unconfirmed"

        private const val CONFIRMED = "CONFIRMED"
        private val UNCONFIRMED_AFTER = RampSettings.REVIEW_AFTER.multipliedBy(3)

        /** EVM addresses compare without case; anything else (a Solana address) exactly. */
        fun sameAddress(a: String, b: String): Boolean =
            if (a.trim().startsWith("0x")) a.trim().equals(b.trim(), ignoreCase = true) else a.trim() == b.trim()
    }
}
