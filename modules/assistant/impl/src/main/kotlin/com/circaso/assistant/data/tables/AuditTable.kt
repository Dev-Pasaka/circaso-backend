package com.circaso.assistant.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/** Append-only: what happened (the turn, its tools, its size), never what was said. Ids are UUIDv7 made in the app. */
object AuditTable : Table("assistant.audit") {
    val userId = javaUUID("user_id")
    val eventId = javaUUID("event_id")
    val kind = text("kind")
    val detail = text("detail")
    val requestId = text("request_id").nullable()
    override val primaryKey = PrimaryKey(userId, eventId)
}
