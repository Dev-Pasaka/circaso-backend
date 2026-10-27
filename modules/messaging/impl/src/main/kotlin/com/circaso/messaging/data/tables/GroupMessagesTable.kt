package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * A group message's one ciphertext, shared by every member's pointer and deleted with the last member's ack
 * (`msg:pending:{messageId}` counts down); the day's partition goes after 30 days whatever Redis lost.
 */
object GroupMessagesTable : Table("messaging.group_messages") {
    val chatId = javaUUID("chat_id")
    val messageId = javaUUID("message_id")
    val senderId = javaUUID("sender_id")
    val ciphertext = text("ciphertext")
    val sentAt = timestamp("sent_at")
    override val primaryKey = PrimaryKey(chatId, messageId)
}
