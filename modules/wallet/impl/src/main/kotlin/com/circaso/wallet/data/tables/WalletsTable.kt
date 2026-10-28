package com.circaso.wallet.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * The user's wallet on one chain, as Circle reported it: one row per (user, chain). Rows are inserted with
 * `ON CONFLICT DO NOTHING`, so the request that inserts a chain's row is the one that announces it, however many see
 * Circle's wallet at once.
 */
object WalletsTable : Table("wallet.wallets") {
    val circleWalletId = text("circle_wallet_id")
    val userId = javaUUID("user_id")
    val blockchain = text("blockchain")
    val accountType = text("account_type")
    val address = text("address")
    val state = text("state")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(circleWalletId)

    init {
        uniqueIndex("wallets_user_chain_key", userId, blockchain)
        uniqueIndex("wallets_address_key", address, blockchain)
    }
}
