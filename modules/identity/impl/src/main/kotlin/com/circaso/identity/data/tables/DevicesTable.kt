package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `identity.devices`: every phone an account registered, with its hardware-backed public key. */
object DevicesTable : Table("identity.devices") {
    val userId = javaUUID("user_id")
    val deviceId = javaUUID("device_id")
    val platform = text("platform")
    val model = text("model")
    val appVersion = text("app_version")
    val publicKey = text("public_key")
    val keyAlgorithm = text("key_algorithm")
    val pushToken = text("push_token").nullable()
    val status = text("status")
    val registeredAt = timestamp("registered_at")
    val lastSeenAt = timestamp("last_seen_at")
    override val primaryKey = PrimaryKey(userId, deviceId)
}
