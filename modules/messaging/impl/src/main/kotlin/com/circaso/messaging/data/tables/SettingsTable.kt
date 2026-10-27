package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/** M22: a row only once the user changes something; no row means both on. */
object SettingsTable : Table("messaging.settings") {
    val userId = javaUUID("user_id")
    val readReceipts = bool("read_receipts").default(true)
    val typing = bool("typing").default(true)
    override val primaryKey = PrimaryKey(userId)
}
