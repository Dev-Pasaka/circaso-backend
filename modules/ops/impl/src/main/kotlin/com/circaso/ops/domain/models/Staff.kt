package com.circaso.ops.domain.models

import com.circaso.shared.StaffRole
import java.time.Instant
import java.util.UUID

/**
 * A row of the staff list. [addedBy] is the email of the admin who added them (or `config` for a bootstrap admin).
 * [fromConfig]: a bootstrap admin from `OPS_BOOTSTRAP_ADMINS`, always an active ADMIN and never edited in the portal.
 */
data class Staff(
    val id: UUID,
    val email: String,
    val role: StaffRole,
    val active: Boolean,
    val addedBy: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val fromConfig: Boolean = false,
    val version: Long = 0,
) {
    override fun toString(): String = "Staff($id, $role, active=$active, fromConfig=$fromConfig)"
}

/**
 * Who is acting, as every staff use case receives it: from the ops gateway's verified principal, plus the request id
 * the audit line keeps so an entry can be matched to its log lines.
 */
data class Actor(
    val staffId: UUID,
    val email: String,
    val role: StaffRole,
    val requestId: String?,
) {
    override fun toString(): String = "Actor($staffId, $role)"
}
