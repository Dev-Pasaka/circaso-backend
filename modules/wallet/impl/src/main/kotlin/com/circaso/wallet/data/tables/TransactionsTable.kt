package com.circaso.wallet.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * The history: the sender's `OUT` row written with the transfer, the recipient's `IN` row from Circle's inbound
 * notification, unique per Circle transaction and user (a repeated notification inserts nothing) and per transfer.
 * The partial unique index on `transfer_id` and the history indexes are in the migration. An `IN` row names the
 * Circaso transfer that paid it in [paidByTransferId] (V7, 2026-11-05), never in `transfer_id`.
 */
object TransactionsTable : Table("wallet.transactions") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val direction = text("direction")
    val state = text("state")
    val amountMinor = long("amount_minor")
    val asset = text("asset")
    val blockchain = text("blockchain")
    val counterpartyId = javaUUID("counterparty_id").nullable()
    val counterpartyAddress = text("counterparty_address").nullable()
    val transferId = javaUUID("transfer_id").nullable()
    val circleTxId = text("circle_tx_id").nullable()
    val txHash = text("tx_hash").nullable()
    val chatId = javaUUID("chat_id").nullable()
    val origin = text("origin").default("APP")
    val occurredAt = timestamp("occurred_at")
    val paidByTransferId = javaUUID("paid_by_transfer_id").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("transactions_circle_key", circleTxId, userId)
    }
}
