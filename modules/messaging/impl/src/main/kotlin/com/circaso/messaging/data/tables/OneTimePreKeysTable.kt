package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * One-time prekeys waiting to be handed out. M3 takes the lowest `key_id` with `DELETE … RETURNING` over a
 * `FOR UPDATE SKIP LOCKED` pick, so two fetches never get the same key; `uploaded_at` lets a replaced phone's keys go
 * by the event's time without touching what the new phone uploaded since.
 */
object OneTimePreKeysTable : Table("messaging.one_time_prekeys") {
    val userId = javaUUID("user_id")
    val keyId = integer("key_id")
    val deviceId = javaUUID("device_id")
    val publicKey = text("public_key")
    val uploadedAt = timestamp("uploaded_at")
    override val primaryKey = PrimaryKey(userId, keyId)
}
