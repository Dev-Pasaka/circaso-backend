package com.circaso.share.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * SH1-SH4 and the public page: one share link. `snapshot` is the typed JSON of what the page shows (data/mappers),
 * cleared when the link is revoked. `expires_at` is when the row itself goes, 30 days after the link ends or is
 * revoked; a link with no end keeps it null until then.
 */
object LinksTable : Table("share.links") {
    val code = text("code")
    val ownerId = javaUUID("owner_id")
    val kind = text("kind")
    val snapshot = text("snapshot").nullable()
    val createdAt = timestamp("created_at")
    val endsAt = timestamp("ends_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    override val primaryKey = PrimaryKey(code)

    init {
        index("links_owner_idx", false, ownerId, createdAt, code)
        index("links_expires_at_idx", false, expiresAt)
    }
}
