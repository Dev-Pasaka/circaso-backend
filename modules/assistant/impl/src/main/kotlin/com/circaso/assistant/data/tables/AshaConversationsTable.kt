package com.circaso.assistant.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * The user's conversations with Asha (V2). The list reads newest first by `(last_message_at, conversation_id)` through
 * `asha_conversations_recent_idx`; a conversation's messages are in [AshaMessagesTable] under its id.
 */
object AshaConversationsTable : Table("assistant.asha_conversations") {
    val userId = javaUUID("user_id")
    val conversationId = javaUUID("conversation_id")

    /** Sealed by the module's `TextVault`; null until the conversation is titled. */
    val title = text("title").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val lastMessageAt = timestamp("last_message_at")
    override val primaryKey = PrimaryKey(userId, conversationId)

    init {
        index("asha_conversations_recent_idx", false, userId, lastMessageAt, conversationId)
    }
}
