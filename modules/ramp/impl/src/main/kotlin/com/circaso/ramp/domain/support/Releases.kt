package com.circaso.ramp.domain.support

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.TreasuryBalance
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.api.TreasuryTransaction
import com.circaso.integrations.api.TreasuryTransactionState
import com.circaso.integrations.api.TreasuryTransfer
import com.circaso.ramp.api.OnRampCompleted
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.ports.FrameSender
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.RampOutbox
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.ports.UsdcContracts
import com.circaso.ramp.domain.repositories.CommissionRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.shared.EventBus
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.envelope
import java.math.BigInteger
import java.time.Clock
import java.util.UUID

/**
 * The release of a paid order's USDC, in the **ops process only** (decision 2026-11-22): the custodian signs for the
 * merchant's developer-controlled wallet with the entity secret, which the app's processes never hold. The app moved
 * the order to `PAID` after verifying the payment itself; nothing here looks at a payment again.
 *
 * `PAID → RELEASING → COMPLETED`. The idempotency key is derived from the order, so however often a send is asked for
 * the custodian sends once. When the custodian can't be reached the order stays where it is and the next run asks
 * again; when it refuses, or the chain fails the transaction, a person looks (`REVIEW`), with the float still reserved.
 */
class Releases(
    private val orders: OrderRepository,
    private val wallets: MerchantWalletRepository,
    private val reservations: ReservationRepository,
    private val commissions: CommissionRepository,
    private val transactor: Transactor,
    private val outbox: RampOutbox,
    private val bus: EventBus,
    private val frames: FrameSender,
    private val alerts: RampAlerts,
    private val usdc: UsdcContracts,
    private val clock: Clock,
) {
    /** Sends the USDC for a `PAID` order from its merchant's wallet to the user's. */
    suspend fun release(treasury: TreasuryCustody, order: Order): Order {
        if (order.state != OrderState.PAID) return order
        val wallet = wallets.find(order.merchantId, order.network) ?: return review(order, "merchant_wallet_missing")
        val token =
            when (val found = usdcIn(treasury, order.network, wallet.custodyWalletId, wallet.blockchain)) {
                is Token.Found -> found.balance
                Token.Unknown -> return order
                Token.Absent -> return review(order, "float_short")
            }
        val transfer =
            TreasuryTransfer(
                walletId = wallet.custodyWalletId,
                blockchain = order.blockchain,
                tokenId = token.tokenId,
                decimals = token.decimals,
                amountMinor = BigInteger.valueOf(order.assetMinor),
                destinationAddress = order.destinationAddress,
                refId = order.orderId.toString(),
                idempotencyKey = releaseKey(order.orderId),
            )
        return when (val sent = treasury.send(order.network, transfer)) {
            // Unavailable: the outcome is unknown, so the same key is asked again on the next run.
            is Resource.Error -> {
                if (sent.error is IntegrationError.Unavailable) {
                    order
                } else {
                    review(
                        order,
                        "release_refused",
                    )
                }
            }

            is Resource.Success -> {
                apply(releasing(order, sent.data.transactionId), sent.data)
            }
        }
    }

    /** Reads the release of a `RELEASING` order from the custodian and applies what the chain did with it. */
    suspend fun follow(treasury: TreasuryCustody, order: Order): Order {
        if (order.state != OrderState.RELEASING) return order
        val transactionId = order.custodyTxId ?: return review(order, "release_untracked")
        return when (val read = treasury.transaction(order.network, transactionId)) {
            is Resource.Error -> order
            is Resource.Success -> apply(order, read.data)
        }
    }

    /**
     * The token the ramp may send from [walletId]: the one under the USDC contract the network's settings name for
     * the chain, never one that only calls itself USDC.
     */
    suspend fun usdcIn(
        treasury: TreasuryCustody,
        network: Network,
        walletId: String,
        blockchain: String,
    ): Token {
        val contract = usdc.contractOn(network, blockchain)
        if (contract == null) {
            alerts.stuck("release on $blockchain", "no USDC contract in the network's settings")
            return Token.Unknown
        }
        val held =
            when (val read = treasury.balances(network, walletId)) {
                is Resource.Error -> return Token.Unknown
                is Resource.Success -> read.data
            }
        return held.firstOrNull { !it.native && sameContract(contract, it.tokenAddress) }?.let { Token.Found(it) }
            ?: Token.Absent
    }

    /** What a wallet's balances say about the USDC the ramp may send. */
    sealed interface Token {
        data class Found(val balance: TreasuryBalance) : Token

        /** The wallet holds none of it. */
        data object Absent : Token

        /** It can't be said now (the custodian is down, or the chain has no USDC contract set): try again later. */
        data object Unknown : Token
    }

    private suspend fun releasing(order: Order, transactionId: String): Order {
        val patch = OrderPatch(custodyTxId = transactionId)
        val moved = orders.move(order.orderId, OrderState.RELEASING, setOf(OrderState.PAID), patch, clock.instant())
        if (moved != null) frames.orderUpdated(moved)
        return moved ?: current(order)
    }

    private suspend fun apply(order: Order, transaction: TreasuryTransaction): Order {
        if (order.state != OrderState.RELEASING) return order
        return when (transaction.state) {
            TreasuryTransactionState.CONFIRMED -> complete(order, transaction.txHash)
            TreasuryTransactionState.FAILED -> review(order, "release_failed")
            TreasuryTransactionState.IN_FLIGHT -> order
        }
    }

    /**
     * The chain confirmed the release: the order is completed, its reservation consumed, its commission recorded (the
     * merchant's share, and Circaso's, which the float now holds until the daily sweep) and `ramp.OnRampCompleted`
     * written, all in one transaction. Then the event is published and the user is told.
     */
    private suspend fun complete(order: Order, txHash: String): Order {
        val now = clock.instant()
        val event =
            bus.envelope(
                OnRampCompleted(
                    orderId = order.orderId,
                    userId = order.userId,
                    merchantId = order.merchantId,
                    network = order.network.name,
                    blockchain = order.blockchain,
                    asset = RampSettings.ASSET,
                    assetAmountMinor = order.assetMinor.toString(),
                    fiatCurrency = order.fiat.currency,
                    fiatAmountMinor = order.fiat.amountMinor.toString(),
                ),
            )
        val completed =
            transactor.run("orders.complete") {
                val patch = OrderPatch(txHash = txHash.ifEmpty { null })
                val moved = orders.move(order.orderId, OrderState.COMPLETED, setOf(OrderState.RELEASING), patch, now)
                if (moved != null) {
                    reservations.consume(order.orderId, now)
                    commissions.insert(moved, Fees.split(moved), now)
                    outbox.write(event)
                }
                moved
            } ?: return current(order)
        bus.publish(event)
        frames.orderUpdated(completed)
        return completed
    }

    private suspend fun review(order: Order, reason: String): Order {
        val patch = OrderPatch(failureReason = reason)
        val moved =
            orders.move(order.orderId, OrderState.REVIEW, OrderFlow.REVIEWABLE, patch, clock.instant())
                ?: return current(order)
        alerts.needsReview(moved, reason)
        frames.orderUpdated(moved)
        return moved
    }

    private suspend fun current(order: Order): Order = orders.find(order.orderId) ?: order

    companion object {
        /** The custodian wants a UUID of its own kind, and our ids are UUIDv7: a name-based one, as W4 derives its key. */
        fun releaseKey(orderId: RampOrderId): UUID = UUID.nameUUIDFromBytes("ramp:release:$orderId".toByteArray())

        /** EVM addresses compare without case; anything else (a Solana mint) exactly. */
        fun sameContract(a: String, b: String): Boolean =
            if (a.startsWith("0x")) a.equals(b.trim(), ignoreCase = true) else a == b.trim()
    }
}
