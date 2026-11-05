package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * AT1-AT3: one encrypted file in object storage, `PENDING` until AT2 checks it, deleted after `expires_at`. A null
 * `expires_at` (V6) is a group's current photo, kept until M8 replaces it or the group is deleted.
 */
object AttachmentsTable : Table("messaging.attachments") {
    val attachmentId = javaUUID("attachment_id")
    val chatId = javaUUID("chat_id")
    val uploaderId = javaUUID("uploader_id")
    val kind = text("kind")
    val sizeBytes = long("size_bytes")
    val state = text("state")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at").nullable()
    override val primaryKey = PrimaryKey(attachmentId)

    init {
        index("attachments_expires_at_idx", false, expiresAt)
        index("attachments_pending_created_at_idx", false, createdAt, filterCondition = { state eq "PENDING" })
    }
}
