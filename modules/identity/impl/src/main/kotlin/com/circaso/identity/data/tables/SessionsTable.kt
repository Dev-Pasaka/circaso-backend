package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `identity.sessions`: one row per app session, gone 30 days after its last rotation (`expires_at`). */
object SessionsTable : Table("identity.sessions") {
    val sessionId = javaUUID("session_id")
    val userId = javaUUID("user_id")
    val deviceId = javaUUID("device_id")
    val currentTokenHash = text("current_token_hash")
    val scope = text("scope")
    val status = text("status")
    val createdAt = timestamp("created_at")
    val rotatedAt = timestamp("rotated_at")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(sessionId)

    init {
        index("sessions_expires_at_idx", false, expiresAt)
        // V3: staff's list of an account's sessions; the SQL orders created_at DESC.
        index("sessions_user_idx", false, userId, createdAt)
    }
}
