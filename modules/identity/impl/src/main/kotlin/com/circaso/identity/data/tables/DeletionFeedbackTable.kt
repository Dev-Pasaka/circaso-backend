package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `identity.deletion_feedback` (V7, 2026-11-12): why people asked for their account to go, from the website's deletion
 * page (I17). Deliberately not linked to the account, so it outlives the deletion and points at no one.
 */
object DeletionFeedbackTable : Table("identity.deletion_feedback") {
    val feedbackId = javaUUID("feedback_id")
    val reason = text("reason")
    val details = text("details").nullable()
    val origin = text("source")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(feedbackId)
}
