package com.circaso.ops.domain.repositories

import com.circaso.ops.domain.models.AuditEntry
import com.circaso.ops.domain.models.AuditFilter
import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ops.domain.models.Staff
import java.time.Instant
import java.util.UUID

/** `ops.staff`: who may sign in to the admin, with which role. Emails are stored lowercased, one row per email. */
interface StaffRepository {
    suspend fun findByEmail(email: String): Staff?

    suspend fun find(id: UUID): Staff?

    suspend fun findMany(ids: Set<UUID>): Map<UUID, Staff>

    /** Every row, oldest first, at most [limit]: a staff list is a few dozen people. */
    suspend fun list(limit: Int): List<Staff>

    /** True when written; false when the email is already on the list (its unique index settles a race). */
    suspend fun insert(staff: Staff): Boolean

    /** Writes [staff] if the row is still at [Staff.version]; false when someone changed it first. */
    suspend fun update(staff: Staff): Boolean
}

/**
 * `ops.audit_log`: one line per staff action, newest first by id (UUIDv7, so ids sort by time). A line is never
 * edited except to [finish] it, and never deleted by the app.
 */
interface AuditRepository {
    suspend fun begin(entry: AuditEntry)

    suspend fun finish(
        id: UUID,
        outcome: AuditOutcome,
        errorCode: String?,
        finishedAt: Instant,
    )

    /** Newest first; [before] is the last id of the previous page. */
    suspend fun list(
        filter: AuditFilter,
        before: UUID?,
        limit: Int,
    ): List<AuditEntry>

    /** The newest succeeded line of [action] (its wire name) on [targetId], or null. */
    suspend fun latestSucceeded(action: String, targetId: String): AuditEntry?
}
