package com.circaso.chama.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** One goal per group chat; `version` rises on every change so a stale write never wins. */
object GoalsTable : Table("chama.goals") {
    val chatId = javaUUID("chat_id")
    val name = text("name")
    val targetMinor = long("target_minor")
    val asset = text("asset")
    val deadline = timestamp("deadline").nullable()

    /** Only on goals from before group wallets (decision 2026-11-03); null on every target since. */
    val treasurerId = javaUUID("treasurer_id").nullable()
    val createdBy = javaUUID("created_by")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val version = long("version").default(0)
    override val primaryKey = PrimaryKey(chatId)
}

/**
 * A member's confirmed payment into the group's wallet, once per transfer. `legacy` marks those paid to a treasurer
 * before group wallets (decision 2026-11-03): kept in the ledger, never counted towards a target.
 */
object ContributionsTable : Table("chama.contributions") {
    val transferId = javaUUID("transfer_id")
    val chatId = javaUUID("chat_id")
    val userId = javaUUID("user_id")
    val amountMinor = long("amount_minor")
    val asset = text("asset")
    val at = timestamp("at")
    val legacy = bool("legacy").default(false)
    override val primaryKey = PrimaryKey(transferId)
}

/**
 * Money the group wallet's owner sent straight from it (decision 2026-11-06: no request), once per transfer, so what
 * the group paid out and still holds stays true. A payout for a request is counted on its request instead.
 */
object DirectPayoutsTable : Table("chama.direct_payouts") {
    val transferId = javaUUID("transfer_id")
    val chatId = javaUUID("chat_id")
    val amountMinor = long("amount_minor")
    val asset = text("asset")
    val at = timestamp("at")
    override val primaryKey = PrimaryKey(transferId)
}

/** Who approves the group's payouts (decision 2026-11-03); no row means the default, two admins. */
object GroupSettingsTable : Table("chama.group_settings") {
    val chatId = javaUUID("chat_id")
    val approvalPolicy = text("approval_policy")
    val updatedBy = javaUUID("updated_by")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(chatId)
}

/** A request to pay someone from the group; its state moves only by the transition guard. */
object RequestsTable : Table("chama.requests") {
    val requestId = javaUUID("request_id")
    val chatId = javaUUID("chat_id")
    val requestedBy = javaUUID("requested_by")
    val payeeId = javaUUID("payee_id").nullable()
    val payeeAddress = text("payee_address").nullable()
    val payeeName = text("payee_name").nullable()
    val amountMinor = long("amount_minor")
    val asset = text("asset")
    val note = text("note").nullable()
    val state = text("state")
    val approvalsNeeded = integer("approvals_needed")
    val transferId = javaUUID("transfer_id").nullable()
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(requestId)
}

/** One decision per admin per request. */
object ApprovalsTable : Table("chama.approvals") {
    val requestId = javaUUID("request_id")
    val userId = javaUUID("user_id")
    val decision = text("decision")
    val at = timestamp("at")
    override val primaryKey = PrimaryKey(requestId, userId)
}

/** The frames chama sent, kept for replay; daily partitions made and dropped by `chama.purge-expired`. */
object RealtimeTable : Table("chama.realtime") {
    val userId = javaUUID("user_id")
    val seq = long("seq")
    val frame = text("frame")
    val sentAt = timestamp("sent_at")
    override val primaryKey = PrimaryKey(userId, seq, sentAt)
}
