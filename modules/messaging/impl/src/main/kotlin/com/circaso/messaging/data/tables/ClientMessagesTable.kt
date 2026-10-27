package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * M12's idempotency: the message id a phone's `clientMessageId` was given, for a day (`expires_at`). The send claims
 * it in its own transaction, so a retry finds the first send's id or nothing at all.
 */
object ClientMessagesTable : Table("messaging.client_messages") {
    val senderId = javaUUID("sender_id")
    val clientMessageId = javaUUID("client_message_id")
    val messageId = javaUUID("message_id")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(senderId, clientMessageId)

    init {
        index("client_messages_expires_at_idx", false, expiresAt)
    }
}
