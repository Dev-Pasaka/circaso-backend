package com.circaso.ops.api

import com.circaso.shared.StaffRole
import java.util.UUID

/**
 * A Circaso staff member as the ops gateway needs one: who, with which role. [fromConfig] marks a bootstrap admin
 * (`OPS_BOOTSTRAP_ADMINS`), always an active ADMIN whatever the staff table says, so the platform can never be locked
 * out of its own admin.
 */
data class StaffMember(
    val id: UUID,
    val email: String,
    val role: StaffRole,
    val active: Boolean,
    val fromConfig: Boolean,
) {
    // The email is personal data: never printed whole by accident.
    override fun toString(): String = "StaffMember($id, $role, active=$active)"
}

/**
 * The ops gateway asks this who a verified email is (decision 2026-11-08). Implemented by the ops module, wired in
 * `app`, so the gateway never reads the staff table.
 */
interface StaffDirectory {
    /** The active staff member for [email] (compared lowercased), or null: not staff, or deactivated. */
    suspend fun signIn(email: String): StaffMember?

    /** Writes the audit line for a sandbox dev login, which no route of the ops module sees. */
    suspend fun recordDevLogin(staff: StaffMember, requestId: String?)
}
