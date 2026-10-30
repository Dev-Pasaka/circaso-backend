package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `identity.accounts`: one row per person. `phone` is unique, so setting it is the claim on the number. */
object AccountsTable : Table("identity.accounts") {
    /** The constraint whose violation means another account holds the number. */
    const val PHONE_KEY = "accounts_phone_key"

    val userId = javaUUID("user_id")
    val phone = text("phone").nullable()
    val status = text("status")
    val activeDeviceId = javaUUID("active_device_id").nullable()
    val verification = text("verification").default("NONE")
    val sessionVersion = integer("session_version").default(1)
    val deleteAfter = timestamp("delete_after").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val version = long("version").default(0)

    /** `PERSON` or `SUPPORT` (decision 2026-10-31), set when the row is created and never updated. */
    val kind = text("kind").default("PERSON")
    override val primaryKey = PrimaryKey(userId)

    init {
        uniqueIndex(PHONE_KEY, phone)
        index("accounts_pending_idx", false, createdAt, filterCondition = { status eq "PENDING_PHONE" })
        index("accounts_deleted_idx", false, deleteAfter, filterCondition = { status eq "DELETED" })
        index("accounts_support_idx", false, userId, filterCondition = { kind eq "SUPPORT" })
    }
}
