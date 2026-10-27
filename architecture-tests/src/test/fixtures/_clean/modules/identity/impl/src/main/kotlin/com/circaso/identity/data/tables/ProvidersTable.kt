package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object ProvidersTable : Table("identity.provider_links") {
    val userId = javaUUID("user_id")
    val provider = text("provider")
    val subject = text("subject")
    override val primaryKey = PrimaryKey(userId, provider)
}
