package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Who is in each chat. It is also the chat list (M6), read through `members_user_idx`: the row goes with the
 * membership, so there is no second copy to keep in step.
 */
object MembersTable : Table("messaging.members") {
    val chatId = javaUUID("chat_id").references(ChatsTable.chatId, onDelete = ReferenceOption.CASCADE)
    val userId = javaUUID("user_id")
    val role = text("role")

    /** This member's read marker (`messaging.read`); it only moves forward. */
    val readUpTo = javaUUID("read_up_to").nullable()
    val joinedAt = timestamp("joined_at")
    override val primaryKey = PrimaryKey(chatId, userId)

    init {
        index("members_user_idx", false, userId, chatId)
    }
}
