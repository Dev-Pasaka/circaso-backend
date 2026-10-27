package com.circaso.identity.data.repositories

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

// A table belongs in data.tables, not beside the repository that uses it.
object SessionsTable : Table("identity.sessions") {
    val sessionId = javaUUID("session_id")
    override val primaryKey = PrimaryKey(sessionId)
}
