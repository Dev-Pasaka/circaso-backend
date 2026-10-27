package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.util.UUID

/**
 * What waits for each recipient until their phone acks it. A direct or `SENDER_KEY` row holds that recipient's own
 * ciphertext; a group `MESSAGE` row is a pointer (`ciphertext` NULL) to its one body in [GroupMessagesTable].
 * Partitioned by day on `message_id` (UUIDv7), so the partitions are not declared here.
 */
object InboxTable : Table("messaging.inbox") {
    val userId = javaUUID("user_id")
    val messageId = javaUUID("message_id")
    val chatId = javaUUID("chat_id")
    val senderId = javaUUID("sender_id")
    val kind = text("kind")
    val ciphertext = text("ciphertext").nullable()
    val mediaIds = array<UUID>("media_ids", UUIDColumnType())
    val sentAt = timestamp("sent_at")
    override val primaryKey = PrimaryKey(userId, messageId)
}
