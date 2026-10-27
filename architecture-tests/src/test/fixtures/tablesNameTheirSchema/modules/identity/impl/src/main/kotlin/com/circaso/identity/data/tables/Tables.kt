package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

// No schema: it would land in whatever the role's search_path says.
object AccountsTable : Table("accounts") {
    val userId = javaUUID("user_id")
    override val primaryKey = PrimaryKey(userId)
}

// Another module's schema, which the identity role cannot reach.
object ProfilesTable : Table("users.profiles") {
    val userId = javaUUID("user_id")
    override val primaryKey = PrimaryKey(userId)
}
