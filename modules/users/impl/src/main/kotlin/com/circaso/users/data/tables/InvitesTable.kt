package com.circaso.users.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Invite links and QR codes, good for 7 days unless revoked. */
object InvitesTable : Table("users.invites") {
    val token = text("token")
    val ownerId = javaUUID("owner_id")
    val kind = text("kind")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(token)

    init {
        index("invites_expires_at_idx", false, expiresAt)
    }
}
