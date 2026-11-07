package com.circaso.wallet.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * One row per transfer W4 prepared, unique per sender and `Idempotency-Key` for good. Its state moves only by the
 * transition guard (`UPDATE … WHERE state IN (<allowed predecessors>)`), so a late or repeated webhook changes
 * nothing.
 * The open-transfer index for the jobs (WS4) and the payout lookup (`transfers_payout_idx`) are
 * partial indexes in the migrations.
 */
object TransfersTable : Table("wallet.transfers") {
    val transferId = javaUUID("transfer_id")
    val senderId = javaUUID("sender_id")
    val senderWalletId = text("sender_wallet_id")
    val blockchain = text("blockchain")
    val idempotencyKey = javaUUID("idempotency_key")
    val recipientId = javaUUID("recipient_id").nullable()
    val destinationAddress = text("destination_address")

    /** In the coin's minor units: numeric(78,0), since an 18-decimal coin passes a bigint at about 9 coins (V8). */
    val amountMinor = decimal("amount_minor", 78, 0)
    val asset = text("asset")
    val assetDecimals = short("asset_decimals").default(6)
    val state = text("state")
    val circleTxId = text("circle_tx_id").nullable()
    val txHash = text("tx_hash").nullable()
    val chatId = javaUUID("chat_id").nullable()
    val origin = text("origin")
    val proposalId = javaUUID("proposal_id").nullable()
    val failureReason = text("failure_reason").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val version = long("version").default(0)
    val signerId = javaUUID("signer_id").nullable()
    val payoutRequestId = javaUUID("payout_request_id").nullable()
    val fromGroupId = javaUUID("from_group_id").nullable()
    val toGroupId = javaUUID("to_group_id").nullable()
    val feeMinor = decimal("fee_minor", 78, 0).default(java.math.BigDecimal.ZERO)
    val feeAddress = text("fee_address").nullable()

    /** V8 (2026-11-07, any held coin): the coin's contract (empty: the chain's own), its price, its USD weight. */
    val tokenAddress = text("token_address").nullable()
    val usdPrice = decimal("usd_price", 38, 18).nullable()
    val usdTotalMinor = long("usd_total_minor")

    /** Spaces (V5): the space wallet on either side, and the membership payment (SP13) it pays. */
    val fromSpaceId = javaUUID("from_space_id").nullable()
    val toSpaceId = javaUUID("to_space_id").nullable()
    val spacePaymentId = javaUUID("space_payment_id").nullable()
    override val primaryKey = PrimaryKey(transferId)

    init {
        uniqueIndex("transfers_idempotency_key", senderId, idempotencyKey)
    }
}
