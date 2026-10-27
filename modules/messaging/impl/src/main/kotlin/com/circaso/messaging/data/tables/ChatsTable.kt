package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * One row per chat. A direct chat carries its pair as `direct_low` < `direct_high`, and the partial unique index on
 * them is the claim that makes two people opening their chat at once get the same one. Every membership change locks
 * this row first (`FOR UPDATE` or the conditional `member_count` update) and moves `version` on.
 */
object ChatsTable : Table("messaging.chats") {
    val chatId = javaUUID("chat_id")
    val kind = text("kind")
    val directLow = javaUUID("direct_low").nullable()
    val directHigh = javaUUID("direct_high").nullable()
    val sealedMeta = text("sealed_meta").nullable()
    val metaVersion = integer("meta_version").default(0)
    val avatarMediaId = javaUUID("avatar_media_id").nullable()

    /** JSON: membersCanAdd. */
    val settings = text("settings")
    val memberCount = integer("member_count")
    val ownerId = javaUUID("owner_id").nullable()
    val createdBy = javaUUID("created_by")
    val createdAt = timestamp("created_at")
    val version = long("version").default(0)
    override val primaryKey = PrimaryKey(chatId)

    init {
        uniqueIndex("chats_direct_pair_key", directLow, directHigh, filterCondition = { kind eq "DIRECT" })
    }
}
