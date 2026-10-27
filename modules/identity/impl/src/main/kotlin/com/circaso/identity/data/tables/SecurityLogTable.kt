package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/** `identity.security_log`: every sign-in, device change, verification and recovery, newest first by UUIDv7 id. */
object SecurityLogTable : Table("identity.security_log") {
    val userId = javaUUID("user_id")
    val eventId = javaUUID("event_id")
    val type = text("type")
    val deviceModel = text("device_model").nullable()
    val detail = text("detail").nullable()
    override val primaryKey = PrimaryKey(userId, eventId)
}
