package com.circaso.ramp.api

import kotlinx.serialization.Serializable

/**
 * The `data` of each realtime frame `ramp` sends (decision 2026-10-27: one class per type, in the contract so the
 * gateway reads them typed when it words a push). A push never shows an amount on the lock screen.
 */
object RampFrames {
    const val MODULE = "ramp"
    const val ORDER_UPDATED = "order.updated"
    const val KYC_UPDATED = "kyc.updated"
}

/** An amount as the ramp hands it out: [unit] is `USDC` or `KES`, [amountMinor] exact, [amount] for display. */
@Serializable
data class RampAmount(val unit: String, val amountMinor: String, val amount: String) {
    override fun toString(): String = "RampAmount($unit, ***)"
}

/**
 * `ramp/order.updated`, to the user, each time their order moves. Pushed when it ends: `COMPLETED` ("Your USDC has
 * arrived"), `FAILED` or `EXPIRED` ("Your top-up didn't go through").
 */
@Serializable
data class OrderUpdatedFrame(
    val orderId: RampOrderId,
    /** `ON_RAMP` or `OFF_RAMP`. */
    val direction: String,
    val state: String,
    val blockchain: String,
    val pay: RampAmount,
    val receive: RampAmount,
    val failureReason: String? = null,
)

/**
 * `ramp/kyc.updated`, to the user, each time their identity verification on [network] moves. The same fields as R1's
 * `verification`, so the app replaces what it shows without another read.
 *
 * [status]: `NONE` | `PENDING` | `IN_REVIEW` | `VERIFIED` | `DECLINED` | `EXPIRED`. [reason] is a code the app words
 * (`document`, `document_expired`, `document_unreadable`, `liveness`, `face_match`, `not_approved`, `declined`,
 * `identity_in_use`, `blocked`, `session_expired`, `resubmission_requested`), never the provider's text. Pushed when
 * it is `VERIFIED` ("You're verified. You can add money now."), `DECLINED`, asked to be redone, or expired with the
 * document.
 */
@Serializable
data class KycUpdatedFrame(
    /** `MAINNET` or `TESTNET`. */
    val network: String,
    val status: String,
    /** Whether this network asks for verification before trading. */
    val required: Boolean,
    /** Whether a check may be started or continued now (R0). */
    val canStart: Boolean,
    val reason: String? = null,
    /** When the daily cap on new checks lifts, ISO-8601 UTC; null when it isn't what stands in the way. */
    val retryAt: String? = null,
)
