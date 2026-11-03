package com.circaso.wallet.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Who a wallet that isn't a person's own belongs to (decision 2026-11-03): one row per group wallet, keyed by the
 * wallet's id, which is also the holder id its other rows are filed under. A personal wallet has no row. The one
 * wallet per group is the partial unique index `wallet_owners_chat_key` in the migration.
 */
object WalletOwnersTable : Table("wallet.wallet_owners") {
    val walletId = javaUUID("wallet_id")
    val ownerType = text("owner_type")
    val chatId = javaUUID("chat_id").nullable()
    val signerId = javaUUID("signer_id")
    val createdBy = javaUUID("created_by")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(walletId)

    init {
        index("wallet_owners_signer_idx", false, signerId)
    }
}
