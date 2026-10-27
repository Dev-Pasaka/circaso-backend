package com.circaso.users.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Who blocked whom; "who blocked me" reads through the index on `blocked_id`. */
object BlocksTable : Table("users.blocks") {
    val userId = javaUUID("user_id")
    val blockedId = javaUUID("blocked_id")
    val blockedAt = timestamp("blocked_at")
    override val primaryKey = PrimaryKey(userId, blockedId)

    init {
        index("blocks_blocked_idx", false, blockedId)
    }
}
