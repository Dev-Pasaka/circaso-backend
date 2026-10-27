package com.circaso.assistant.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * The Asha chat, one continuous thread per user. Ids are UUIDv7 made on the server, so the primary key also orders the
 * thread: pages are read by keyset on `message_id DESC`. The text is sealed (AES-GCM) before it is stored.
 */
object AshaMessagesTable : Table("assistant.asha_messages") {
    val userId = javaUUID("user_id")
    val messageId = javaUUID("message_id")

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
}
