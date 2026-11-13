package com.circaso.calls.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** One call; at most one per chat that hasn't ended (`calls_live_chat_key`, a partial unique index). */
object CallsTable : Table("calls.calls") {
    val callId = javaUUID("call_id")
    val chatId = javaUUID("chat_id")
    val kind = text("kind")
    val state = text("state")
    val startedBy = javaUUID("started_by")
    val isGroup = bool("is_group")
    val createdAt = timestamp("created_at")
    val startedAt = timestamp("started_at").nullable()
    val endedAt = timestamp("ended_at").nullable()
    val endReason = text("end_reason").nullable()
    val activeAt = timestamp("active_at")
    override val primaryKey = PrimaryKey(callId)
}

/** Everyone a call rang or who joined it, with their SFU session while joined. */
object ParticipantsTable : Table("calls.participants") {
    val callId = javaUUID("call_id")
    val userId = javaUUID("user_id")
    val state = text("state")
    val sessionId = text("session_id").nullable()
    val audio = bool("audio")
    val video = bool("video")
    val joinedAt = timestamp("joined_at").nullable()
    val updatedAt = timestamp("updated_at")
    val connectedAt = timestamp("connected_at").nullable()
    val screen = bool("screen").default(false)
    val leftCause = text("left_cause").nullable()
    override val primaryKey = PrimaryKey(callId, userId)
}

/** The tracks a joined participant publishes. */
object TracksTable : Table("calls.tracks") {
    val callId = javaUUID("call_id")
    val userId = javaUUID("user_id")
    val trackName = text("track_name")
    val kind = text("kind")
    val mid = text("mid")
    override val primaryKey = PrimaryKey(callId, userId, trackName)
}

/** The frames calls sent, kept for replay; daily partitions made and dropped by `calls.purge-expired`. */
object RealtimeTable : Table("calls.realtime") {
    val userId = javaUUID("user_id")
    val seq = long("seq")
    val frame = text("frame")
    val sentAt = timestamp("sent_at")
    override val primaryKey = PrimaryKey(userId, seq, sentAt)
}

/** What each phone recorded about a call (CL10), one row per person per call; deleted 30 days after it came. */
object ReportsTable : Table("calls.reports") {
    val callId = javaUUID("call_id")
    val userId = javaUUID("user_id")
    val platform = text("platform")
    val appVersion = text("app_version")
    val osVersion = text("os_version")
    val deviceModel = text("device_model")
    val video = bool("video")
    val outgoing = bool("outgoing")
    val endCause = text("end_cause")
    val startedAt = timestamp("started_at")
    val endedAt = timestamp("ended_at")
    val receivedAt = timestamp("received_at")
    val expiresAt = timestamp("expires_at")
    val summary = text("summary")
    val events = text("events")
    val samples = text("samples")
    override val primaryKey = PrimaryKey(callId, userId)
}
