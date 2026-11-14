package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Group join links (V9, decision 2026-11-14). The partial unique index keeps one live link per group; a reset or
 * turned-off link keeps its row, emptied of the published preview, until `expires_at`, then `messaging.purge-expired`
 * deletes it.
 */
object GroupLinksTable : Table("messaging.group_links") {
    /** 22 base62 characters; whoever has it can see the preview and join. */
    val token = text("token")
    val chatId = javaUUID("chat_id").references(ChatsTable.chatId, onDelete = ReferenceOption.CASCADE)
    val createdBy = javaUUID("created_by")

    /** The preview an admin's phone published: null once the link is reset or turned off. */
    val name = text("name").nullable()
    val description = text("description").nullable()

    /** Base64 of a small JPEG or PNG. */
    val photo = text("photo").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val revokedAt = timestamp("revoked_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    override val primaryKey = PrimaryKey(token)

    init {
        uniqueIndex("group_links_live_key", chatId, filterCondition = { revokedAt.isNull() })
        index("group_links_expires_at_idx", false, expiresAt)
    }
}

/** Who an admin removed from a group (V9), so its join link can't bring them back. */
object GroupRemovalsTable : Table("messaging.group_removals") {
    val chatId = javaUUID("chat_id").references(ChatsTable.chatId, onDelete = ReferenceOption.CASCADE)
    val userId = javaUUID("user_id")
    val removedBy = javaUUID("removed_by")
    val removedAt = timestamp("removed_at")
    override val primaryKey = PrimaryKey(chatId, userId)
}
