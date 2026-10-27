package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object AccountsTable : Table("identity.accounts") {
    val userId = javaUUID("user_id")
    override val primaryKey = PrimaryKey(userId)
}

// Named for what it is not: every table object ends in Table.
object Devices : Table("identity.devices") {
    val deviceId = javaUUID("device_id")
    override val primaryKey = PrimaryKey(deviceId)
}
