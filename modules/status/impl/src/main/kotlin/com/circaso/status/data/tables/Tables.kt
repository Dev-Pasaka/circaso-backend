package com.circaso.status.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * ST3: one moment, ciphertext only, live until `expires_at` (24 hours after `posted_at`). `(poster_id,
 * client_moment_id)` is the claim that makes a retried ST3 return the first answer.
 */
object MomentsTable : Table("status.moments") {
    val momentId = javaUUID("moment_id")
    val posterId = javaUUID("poster_id")
    val clientMomentId = javaUUID("client_moment_id")
    val ciphertext = text("ciphertext")
    val mediaId = javaUUID("media_id").nullable()
    val postedAt = timestamp("posted_at")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(momentId)

    init {
        uniqueIndex("moments_poster_client_key", posterId, clientMomentId)
        index("moments_poster_idx", false, posterId, momentId)
        index("moments_expires_at_idx", false, expiresAt)
    }
}

/** Who a moment was shown to, their key copy, and their view: deleted with the moment. */
object AudienceTable : Table("status.audience") {
    val momentId = javaUUID("moment_id").references(MomentsTable.momentId, onDelete = ReferenceOption.CASCADE)
    val userId = javaUUID("user_id")
    val keyCopy = text("key_copy").nullable()
    val viewedAt = timestamp("viewed_at").nullable()
    val reacted = bool("reacted").default(false)

    /** The one emoji the viewer reacted with, null for none (V2); [reacted] is always written with it. */
    val reaction = text("reaction").nullable()
    override val primaryKey = PrimaryKey(momentId, userId)

    init {
        index("audience_user_idx", false, userId, momentId)
    }
}

/** ST1-ST2, ST8: one encrypted moment file in object storage, deleted by the sweep once `expires_at` passes. */
object MediaTable : Table("status.media") {
    val mediaId = javaUUID("media_id")
    val posterId = javaUUID("poster_id")
    val kind = text("kind")
    val sizeBytes = long("size_bytes")
    val state = text("state")
    val momentId = javaUUID("moment_id").nullable()
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(mediaId)

    init {
        index("media_expires_at_idx", false, expiresAt)
        index("media_moment_idx", false, momentId)
        index("media_poster_idx", false, posterId)
    }
}

/**
 * The frames status sent, kept 7 days for replay. Partitioned by day on `sent_at` (`Migrate` and
 * `status.purge-expired` make the partitions and drop each one 7 days after it ends), so the partitions are not
 * declared here.
 */
object RealtimeTable : Table("status.realtime") {
    val userId = javaUUID("user_id")
    val seq = long("seq")

    /** The envelope as JSON. */
    val frame = text("frame")
    val sentAt = timestamp("sent_at")
    override val primaryKey = PrimaryKey(userId, seq, sentAt)
}
