package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Each chat's newest `MESSAGE`, which M6's chat list and the chat views read by key. The `message_records_chat_head`
 * trigger (V5) writes it in the send's own transaction, a monotonic upsert; the code never does. The purge forgets a
 * head once its record's partition is dropped, and deleting a chat deletes its head.
 */
object ChatHeadsTable : Table("messaging.chat_heads") {
    val chatId = javaUUID("chat_id")
    val lastMessageId = javaUUID("last_message_id")
    val lastMessageAt = timestamp("last_message_at")
    override val primaryKey = PrimaryKey(chatId)

    init {
        index("chat_heads_last_message_idx", false, lastMessageId)
    }
}
