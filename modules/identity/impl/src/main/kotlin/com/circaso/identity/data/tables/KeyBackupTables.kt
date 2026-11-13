package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `identity.key_backup_roots` (V6, 2026-11-12): one sealed group key backup root per account, stored exactly as the
 * phone sent it. The server never holds the key, so these columns are opaque to it.
 */
object KeyBackupRootsTable : Table("identity.key_backup_roots") {
    val userId = javaUUID("user_id")
    val credentialId = text("credential_id")
    val salt = text("salt")
    val sealedRoot = text("sealed_root")
    val algorithm = text("algorithm")
    val version = integer("version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

/** `identity.group_key_backups` (V6): each group meta key, encrypted under the backup root key, one per metaVersion. */
object GroupKeyBackupsTable : Table("identity.group_key_backups") {
    val userId = javaUUID("user_id")
    val chatId = javaUUID("chat_id")
    val metaVersion = integer("meta_version")
    val ciphertext = text("ciphertext")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId, chatId, metaVersion)
}

/** `identity.saved_names` (V8, 2026-11-12): the person's names for their contacts, one sealed blob per account. */
object SavedNamesTable : Table("identity.saved_names") {
    val userId = javaUUID("user_id")
    val ciphertext = text("ciphertext")
    val version = long("version")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId)
}
