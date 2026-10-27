package com.circaso.assistant.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Whether a user has Asha Plus. `plan_ends_at` is when a paid plan ends (null for the free plan). It is not an
 * `expires_at`: the row is never purged, and past that time the plan simply reads as the free one.
 */
object EntitlementsTable : Table("assistant.entitlements") {
    val userId = javaUUID("user_id")
    val plan = text("plan")
    val planEndsAt = timestamp("plan_ends_at").nullable()
    override val primaryKey = PrimaryKey(userId)
}
