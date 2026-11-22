package com.circaso.ramp.api

import kotlinx.serialization.Serializable

/**
 * The `data` of each realtime frame `ramp` sends (decision 2026-10-27: one class per type, in the contract so the
 * gateway reads them typed when it words a push). A push never shows an amount on the lock screen.
 */
object RampFrames {
    const val MODULE = "ramp"
    const val ORDER_UPDATED = "order.updated"
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
