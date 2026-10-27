package com.circaso.users.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** One row per user; `handle` is a copy of the owner's live row in [HandlesTable], unique among profiles. */
object ProfilesTable : Table("users.profiles") {
    val userId = javaUUID("user_id")
    val displayName = text("display_name")
    val about = text("about").nullable()
    val handle = text("handle").nullable()
    val handleChangedAt = timestamp("handle_changed_at").nullable()
    val avatarKey = text("avatar_key").nullable()

    /** JSON: avatar, about, handleLookup. */
    val privacy = text("privacy")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    /** U2's optimistic lock: every update is `WHERE version = ?` and adds one. */
    val version = long("version").default(0)
    override val primaryKey = PrimaryKey(userId)

    init {
        uniqueIndex("profiles_handle_key", handle)
    }
}
