package com.circaso.assistant.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * The Asha chat: every message of a user, each in one of their conversations ([AshaConversationsTable], V2). Ids are
 * UUIDv7 made on the server, so the keys also order the messages: the whole history (A1) reads by keyset on the
 * primary key, one conversation (its page, Asha's context) on `asha_messages_conversation_idx`, both `message_id DESC`.
 * The text is sealed (AES-GCM) before it is stored.
 */
object AshaMessagesTable : Table("assistant.asha_messages") {
    val userId = javaUUID("user_id")
    val messageId = javaUUID("message_id")
    val conversationId = javaUUID("conversation_id")

    /** `USER` or `ASHA`. */
    val role = text("role")

    /** Sealed by the module's `TextVault`; never plain text. */
    val body = text("body")
    val replyTo = javaUUID("reply_to").nullable()
    val answeredBy = javaUUID("answered_by").nullable()
    val askedFrom = text("asked_from").nullable()

    /** JSON: the chat ids and the number of messages Asha read, never the messages. */
    val readNote = text("read_note").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(userId, messageId)

    init {
        index("asha_messages_conversation_idx", false, userId, conversationId, messageId)
    }
}
