package com.circaso.ops.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Who may sign in to the admin, with which role. One row per (lowercased) email. */
object StaffTable : Table("ops.staff") {
    val staffId = javaUUID("staff_id")
    val email = text("email")
    val role = text("role")
    val active = bool("active").default(true)
    val addedBy = text("added_by").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val version = long("version").default(0)
    override val primaryKey = PrimaryKey(staffId)

    init {
        uniqueIndex("staff_email_key", email)
    }
}

/** One line per staff action; see V1 for each column's meaning. */
object AuditLogTable : Table("ops.audit_log") {
    val entryId = javaUUID("entry_id")
    val actorId = javaUUID("actor_id")
    val actorEmail = text("actor_email")
    val actorRole = text("actor_role")
    val action = text("action")
    val targetType = text("target_type")
    val targetId = text("target_id").nullable()
    val reason = text("reason").nullable()
    val detail = text("detail").nullable()
    val outcome = text("outcome")
    val errorCode = text("error_code").nullable()
    val requestId = text("request_id").nullable()
    val at = timestamp("at")
    val finishedAt = timestamp("finished_at").nullable()
    override val primaryKey = PrimaryKey(entryId)

    init {
        index("audit_log_actor_idx", false, actorId, entryId)
        index("audit_log_action_idx", false, action, entryId)
        index("audit_log_target_idx", false, targetType, targetId, entryId)
    }
}
