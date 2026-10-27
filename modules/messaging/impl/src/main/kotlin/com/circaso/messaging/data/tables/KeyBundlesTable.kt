package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** One published key set per account: one phone per account (decision 2026-10-25). Keys are base64 text. */
object KeyBundlesTable : Table("messaging.key_bundles") {
    val userId = javaUUID("user_id")
    val deviceId = javaUUID("device_id")
    val identityKey = text("identity_key")
    val signedPreKeyId = integer("signed_pre_key_id")
    val signedPreKey = text("signed_pre_key")
    val signedPreKeySignature = text("signed_pre_key_signature")
    val signedPreKeyAt = timestamp("signed_pre_key_at")
    val uploadedAt = timestamp("uploaded_at")
    override val primaryKey = PrimaryKey(userId)
}
