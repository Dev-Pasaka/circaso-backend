package com.circaso.ramp.api

import com.circaso.shared.DomainEvent
import com.circaso.shared.InstantSerializer
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable
import java.time.Instant

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

/**
 * A sale completed (decision 2026-11-23): the provider itself said the user's shillings were paid, and the USDC the
 * user sent became the merchant's. Once per order.
 */
@Serializable
data class OffRampCompleted(
    val orderId: RampOrderId,
    val userId: UserId,
    val merchantId: MerchantId,
    /** `MAINNET` or `TESTNET`. */
    val network: String,
    val blockchain: String,
    val asset: String,
    val assetAmountMinor: String,
    val fiatCurrency: String,
    /** What the user was paid. */
    val fiatAmountMinor: String,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "ramp.OffRampCompleted"
    }
}

/**
 * An order ended without a trade (`FAILED`, `EXPIRED` or `CANCELLED`; a sale also `REFUNDED`, its USDC back with the
 * user). Once per order.
 */
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

/**
 * A user's identity verification in Didit's **live** environment moved (2026-11-24, the verified badge): approved,
 * taken back on review, expired, declined, or reset by staff. Sandbox verifications never publish it: they are
 * simulated. It carries the state, not the step: [verified] is whether the live row is `VERIFIED` now, [verifiedAt]
 * when it was approved (null when not verified), and [changedAt] when the row last moved, which orders the events
 * (a consumer ignores one no newer than what it holds). Never a document, a name or the identity key.
 *
 * Published when a live user row moves (with the move where the move's own transaction allows it, otherwise right
 * after it), when staff reset one, and again by `ramp.announce-verifications` for every live user row that moved in
 * the last 14 days, which is also the backfill of the approvals made before this event existed.
 */
@Serializable
data class UserVerificationChanged(
    val userId: UserId,
    val verified: Boolean,
    @Serializable(with = InstantSerializer::class) val verifiedAt: Instant? = null,
    @Serializable(with = InstantSerializer::class) val changedAt: Instant,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "ramp.UserVerificationChanged"
    }
}
