package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Group invites (V8, decision 2026-11-09): someone added to a group is invited, and joins only when they accept. The
 * partial unique index keeps one open invite per person per group; decided rows stay until `expires_at`, so a retried
 * accept or decline finds its answer, and then `messaging.purge-expired` deletes them.
 */
object InvitesTable : Table("messaging.invites") {
    val inviteId = javaUUID("invite_id")
    val chatId = javaUUID("chat_id").references(ChatsTable.chatId, onDelete = ReferenceOption.CASCADE)
    val userId = javaUUID("user_id")
    val invitedBy = javaUUID("invited_by")

    /** `PENDING`, `ACCEPTED`, `DECLINED` or `CANCELLED`. */
    val state = text("state")

    /** The group's metadata key sealed 1:1 by the inviter's phone, so the invite can show the group's name. */
    val sealedKey = text("sealed_key").nullable()
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val decidedAt = timestamp("decided_at").nullable()
    override val primaryKey = PrimaryKey(inviteId)

    init {
        uniqueIndex("invites_pending_key", chatId, userId, filterCondition = { state eq "PENDING" })
        index("invites_user_idx", false, userId, createdAt, filterCondition = { state eq "PENDING" })
        index("invites_expires_at_idx", false, expiresAt)
    }
}
