package com.circaso.ramp.api

import com.circaso.shared.DomainEvent
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

// Published on `events:ramp`. No event carries a phone number, an M-Pesa receipt or a credential.

/** The user's shillings were verified and the USDC reached their wallet on chain. Once per order. */
@Serializable
data class OnRampCompleted(
    val orderId: RampOrderId,
    val userId: UserId,
    val merchantId: MerchantId,
    /** `MAINNET` or `TESTNET` (decision 2026-11-18: everything about money carries its network). */
    val network: String,
    val blockchain: String,
    val asset: String,
    val assetAmountMinor: String,
    val fiatCurrency: String,
    val fiatAmountMinor: String,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "ramp.OnRampCompleted"
    }
}

/** An order ended without a trade (`FAILED`, `EXPIRED` or `CANCELLED`). Once per order. */
@Serializable
data class OrderFailed(
    val orderId: RampOrderId,
    val userId: UserId,
    /** `ON_RAMP` or `OFF_RAMP`. */
    val direction: String,
    val reason: String,
    /** `MAINNET` or `TESTNET`. */
    val network: String = "TESTNET",
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "ramp.OrderFailed"
    }
}
