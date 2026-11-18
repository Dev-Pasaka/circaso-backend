package com.circaso.wallet.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `wallet.payment_holds` (V11): outgoing payments are paused for the person until [expiresAt]. */
object PaymentHoldsTable : Table("wallet.payment_holds") {
    val userId = javaUUID("user_id")

    /** V12 (2026-11-18): the network payments are paused on. */
    val network = text("network")
    val reason = text("reason")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(userId, network)

    init {
        index("payment_holds_expires_idx", false, expiresAt)
    }
}
