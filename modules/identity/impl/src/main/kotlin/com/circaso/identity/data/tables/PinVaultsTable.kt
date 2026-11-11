package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `identity.pin_vaults` (V5, 2026-11-11): one encrypted PIN backup per account, stored exactly as the phone sent it.
 * The server never holds the key, so these columns are opaque to it.
 */
object PinVaultsTable : Table("identity.pin_vaults") {
    val userId = javaUUID("user_id")
    val credentialId = text("credential_id")
    val salt = text("salt")
    val ciphertext = text("ciphertext")
    val algorithm = text("algorithm")
    val version = integer("version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}
