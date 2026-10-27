package com.circaso.users.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Who each user can reach; read from the other side ("who has me") through the partial index on `contact_id`. */
object ContactsTable : Table("users.contacts") {
    val ownerId = javaUUID("owner_id")
    val contactId = javaUUID("contact_id")

    /** `PHONEBOOK`, `QR`, `INVITE` or `HANDLE`; not called `source`, which would hide `ColumnSet.source`. */
    val contactSource = text("source")

    /** `ACTIVE` or `REMOVED`: a removed row stays, so a sync can't add the person back. */
    val status = text("status").default("ACTIVE")
    val removedAt = timestamp("removed_at").nullable()
    val addedAt = timestamp("added_at")
    override val primaryKey = PrimaryKey(ownerId, contactId)

    init {
        index("contacts_target_idx", false, contactId, filterCondition = { status eq "ACTIVE" })
    }
}
