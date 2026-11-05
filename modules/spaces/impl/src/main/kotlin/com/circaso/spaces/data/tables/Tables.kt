package com.circaso.spaces.data.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * SP1-SP9: one row per space. The live handle is unique (`spaces_handle_key`, partial: a deleted space gives its
 * handle up), and the generated `search` column (a tsvector over name, handle, about and topic) is the migration's
 * alone, read through a raw condition in the repository.
 */
object SpacesTable : Table("spaces.spaces") {
    val spaceId = javaUUID("space_id")
    val handle = text("handle")
    val name = text("name")
    val about = text("about").default("")
    val topic = text("topic")
    val photoMediaId = javaUUID("photo_media_id").nullable()
    val coverMediaId = javaUUID("cover_media_id").nullable()
    val visibility = text("visibility")
    val priceMinor = long("price_minor").nullable()
    val pricePeriod = text("price_period").nullable()
    val postingMode = text("posting_mode")
    val privateMessages = text("private_messages")
    val state = text("state")
    val ownerId = javaUUID("owner_id")
    val country = text("country").nullable()
    val memberCount = integer("member_count").default(0)
    val lastPostAt = timestamp("last_post_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val version = long("version").default(1)
    val createKey = text("create_key").nullable()
    val removedReason = text("removed_reason").nullable()
    override val primaryKey = PrimaryKey(spaceId)

    init {
        index("spaces_owner_idx", false, ownerId, createdAt)
    }
}

object MembersTable : Table("spaces.members") {
    val spaceId = javaUUID("space_id").references(SpacesTable.spaceId, onDelete = ReferenceOption.CASCADE)
    val userId = javaUUID("user_id")
    val role = text("role")
    val joinedAt = timestamp("joined_at")
    val invitedBy = javaUUID("invited_by").nullable()
    val notifications = text("notifications").default("ALL")
    val mutedUntil = timestamp("muted_until").nullable()
    val paidState = text("paid_state").nullable()
    val period = text("period").nullable()
    val paidUntil = timestamp("paid_until").nullable()
    val paidAmountMinor = long("paid_amount_minor").nullable()
    val complimentary = bool("complimentary").default(false)
    val remindedFor = timestamp("reminded_for").nullable()
    val readUpTo = javaUUID("read_up_to").nullable()
    override val primaryKey = PrimaryKey(spaceId, userId)

    init {
        index("members_user_idx", false, userId)
    }
}

object BansTable : Table("spaces.bans") {
    val spaceId = javaUUID("space_id").references(SpacesTable.spaceId, onDelete = ReferenceOption.CASCADE)
    val userId = javaUUID("user_id")
    val bannedBy = javaUUID("banned_by")
    val bannedAt = timestamp("banned_at")
    override val primaryKey = PrimaryKey(spaceId, userId)

    init {
        index("bans_user_idx", false, userId)
    }
}

object RequestsTable : Table("spaces.requests") {
    val spaceId = javaUUID("space_id").references(SpacesTable.spaceId, onDelete = ReferenceOption.CASCADE)
    val userId = javaUUID("user_id")
    val state = text("state")
    val requestedAt = timestamp("requested_at")
    val answeredAt = timestamp("answered_at").nullable()
    override val primaryKey = PrimaryKey(spaceId, userId)
}

object InvitesTable : Table("spaces.invites") {
    val inviteId = javaUUID("invite_id")
    val spaceId = javaUUID("space_id").references(SpacesTable.spaceId, onDelete = ReferenceOption.CASCADE)
    val inviteeId = javaUUID("invitee_id")
    val inviterId = javaUUID("inviter_id")
    val state = text("state")
    val invitedAt = timestamp("invited_at")
    val expiresAt = timestamp("expires_at")
    val answeredAt = timestamp("answered_at").nullable()
    override val primaryKey = PrimaryKey(inviteId)

    init {
        uniqueIndex("invites_space_invitee_key", spaceId, inviteeId)
        index("invites_space_idx", false, spaceId, invitedAt)
    }
}

object PaymentsTable : Table("spaces.payments") {
    val paymentId = javaUUID("payment_id")
    val spaceId = javaUUID("space_id").references(SpacesTable.spaceId, onDelete = ReferenceOption.CASCADE)
    val payerId = javaUUID("payer_id")
    val kind = text("kind")
    val amountMinor = long("amount_minor")
    val period = text("period")
    val previousMinor = long("previous_minor").nullable()
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val confirmedAt = timestamp("confirmed_at").nullable()
    override val primaryKey = PrimaryKey(paymentId)

    init {
        index("payments_payer_idx", false, payerId, createdAt)
    }
}

object MediaTable : Table("spaces.media") {
    val mediaId = javaUUID("media_id")
    val ownerId = javaUUID("owner_id")
    val purpose = text("purpose")
    val kind = text("kind")
    val contentType = text("content_type")
    val sizeBytes = long("size_bytes")
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val durationMs = long("duration_ms").nullable()
    val state = text("state")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(mediaId)
}

object PostsTable : Table("spaces.posts") {
    val postId = javaUUID("post_id")
    val spaceId = javaUUID("space_id").references(SpacesTable.spaceId, onDelete = ReferenceOption.CASCADE)
    val authorId = javaUUID("author_id")
    val clientPostId = javaUUID("client_post_id")
    val text = text("text").nullable()
    val replyTo = javaUUID("reply_to").nullable()

    /** Comma-separated media ids, in order. */
    val mediaIds = text("media_ids").default("")
    val createdAt = timestamp("created_at")
    val removedAt = timestamp("removed_at").nullable()
    val removedBy = text("removed_by").nullable()
    override val primaryKey = PrimaryKey(postId)

    init {
        uniqueIndex("posts_author_client_key", authorId, clientPostId)
        index("posts_space_idx", false, spaceId, postId)
        index("posts_author_idx", false, authorId, createdAt)
    }
}

object ReactionsTable : Table("spaces.reactions") {
    val postId = javaUUID("post_id").references(PostsTable.postId, onDelete = ReferenceOption.CASCADE)
    val userId = javaUUID("user_id")
    val emoji = text("emoji")
    val reactedAt = timestamp("reacted_at")
    override val primaryKey = PrimaryKey(postId, userId)
}

object ReportsTable : Table("spaces.reports") {
    val reportId = javaUUID("report_id")
    val spaceId = javaUUID("space_id").references(SpacesTable.spaceId, onDelete = ReferenceOption.CASCADE)
    val postId = javaUUID("post_id").nullable()
    val reporterId = javaUUID("reporter_id")
    val reason = text("reason")
    val detail = text("detail").nullable()
    val state = text("state")
    val createdAt = timestamp("created_at")
    val resolvedAt = timestamp("resolved_at").nullable()
    val resolvedBy = javaUUID("resolved_by").nullable()
    val action = text("action").nullable()
    override val primaryKey = PrimaryKey(reportId)

    init {
        index("reports_space_idx", false, spaceId, state)
        index("reports_reporter_idx", false, reporterId, createdAt)
        index("reports_created_idx", false, createdAt)
    }
}

/** The frames spaces sent, kept 7 days for replay; partitioned by day on `sent_at` (made by Migrate and the sweep). */
object RealtimeTable : Table("spaces.realtime") {
    val userId = javaUUID("user_id")
    val seq = long("seq")
    val frame = text("frame")
    val sentAt = timestamp("sent_at")
    override val primaryKey = PrimaryKey(userId, seq, sentAt)
}
