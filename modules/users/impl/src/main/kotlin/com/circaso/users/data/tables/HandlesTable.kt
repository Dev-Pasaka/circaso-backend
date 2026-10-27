package com.circaso.users.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * One owner per handle, live or on hold: the primary key is the claim. A released handle keeps its row for 30 days
 * (`expires_at`), so only its old owner can take it back; `users.purge-expired` deletes it after that.
 */
object HandlesTable : Table("users.handles") {
    val handle = text("handle")
    val userId = javaUUID("user_id")
    val claimedAt = timestamp("claimed_at")
    val releasedAt = timestamp("released_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    override val primaryKey = PrimaryKey(handle)

    init {
        index("handles_user_idx", false, userId)
        index("handles_expires_at_idx", false, expiresAt, filterCondition = { expiresAt.isNotNull() })
    }
}
