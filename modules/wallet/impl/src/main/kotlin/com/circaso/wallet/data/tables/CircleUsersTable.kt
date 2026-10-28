package com.circaso.wallet.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * One row per user who asked for a wallet: the random id Circle knows them by. The row is the wallet itself, whatever
 * chains it is on; the primary key settles two "Create wallet" taps at once (the second inserts nothing and reads the
 * first's id).
 */
object CircleUsersTable : Table("wallet.circle_users") {
    val userId = javaUUID("user_id")
    val circleUserId = javaUUID("circle_user_id").uniqueIndex("circle_users_circle_user_id_key")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(userId)
}
