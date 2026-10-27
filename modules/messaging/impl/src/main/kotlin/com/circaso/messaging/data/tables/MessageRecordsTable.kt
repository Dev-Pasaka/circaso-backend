package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Who sent what, written with every send and kept 30 days whatever has been delivered: M14's sender check, read
 * receipts after delivery, and M6's newest message per chat (`message_records_chat_idx`).
 */
object MessageRecordsTable : Table("messaging.message_records") {
    val messageId = javaUUID("message_id")
    val chatId = javaUUID("chat_id")
    val senderId = javaUUID("sender_id")
    val kind = text("kind")
    val sentAt = timestamp("sent_at")
    override val primaryKey = PrimaryKey(messageId)

    init {
        index("message_records_chat_idx", false, chatId, messageId)
    }
}
