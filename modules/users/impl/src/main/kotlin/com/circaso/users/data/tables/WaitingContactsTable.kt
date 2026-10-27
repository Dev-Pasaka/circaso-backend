package com.circaso.users.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Phone-book numbers not on Circaso yet, kept only as keyed hashes for 180 days. */
object WaitingContactsTable : Table("users.waiting_contacts") {
    val phoneHmac = text("phone_hmac")
    val ownerId = javaUUID("owner_id")
    val addedAt = timestamp("added_at")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(phoneHmac, ownerId)

    init {
        index("waiting_contacts_expires_at_idx", false, expiresAt)
    }
}
