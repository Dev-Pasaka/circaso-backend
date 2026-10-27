package com.circaso.messaging.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * The frames messaging sent, kept for replay. Partitioned by day on `sent_at` (`Migrate` and
 * `messaging.purge-expired` make the partitions, the job drops each one 7 days after it ends), so the partitions are
 * not declared here.
 */
object RealtimeTable : Table("messaging.realtime") {
    val userId = javaUUID("user_id")
    val seq = long("seq")

    /** The envelope as JSON. */
    val frame = text("frame")
    val sentAt = timestamp("sent_at")
    override val primaryKey = PrimaryKey(userId, seq, sentAt)
}
