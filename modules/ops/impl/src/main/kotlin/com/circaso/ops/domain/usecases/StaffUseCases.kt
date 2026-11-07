package com.circaso.ops.domain.usecases

import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.models.Staff
import com.circaso.ops.domain.repositories.StaffRepository
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.StaffRoster
import com.circaso.shared.Resource
import com.circaso.shared.StaffRole
import com.circaso.shared.Uuid7
import java.time.Clock

// The staff list (ADMIN) and who a signed-in email is (the ops gateway, through StaffDirectory).

/** The active staff member for a verified email, or null: not on the list, or switched off. */
class SignInStaff(private val roster: StaffRoster) {
    suspend operator fun invoke(email: String): Resource<Staff?> =
        Resource.success(roster.byEmail(email)?.takeIf { it.active })
}

/** A dev login is a sign-in no route of this module sees, so it is written here, as its own audit line. */
class RecordDevLogin(private val trail: AuditTrail) {
    suspend operator fun invoke(staff: Staff, requestId: String?): Resource<Unit> {
        val actor = Actor(staff.id, staff.email, staff.role, requestId)
        return trail.record(actor, AuditAction.STAFF_DEV_LOGIN, staff.id.toString()) { Resource.success(Unit) }
    }
}

/** What the caller's role may do, for `/v1/ops/me`: the portal builds its menu from it. */
class ListPermissions {
    suspend operator fun invoke(role: StaffRole): Resource<List<Permission>> = Resource.success(Permission.of(role))
}

class ListStaff(private val roster: StaffRoster) {
    suspend operator fun invoke(): Resource<List<Staff>> = Resource.success(roster.all(MAX_STAFF))

    private companion object {
        const val MAX_STAFF = 500
    }
}

/** ADMIN adds someone by the email they sign in to Cloudflare Access with. */
class AddStaff(
    private val staff: StaffRepository,
    private val roster: StaffRoster,
    private val trail: AuditTrail,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        actor: Actor,
        email: String,
        role: StaffRole,
    ): Resource<Staff> {
        val normalised = StaffRoster.normalise(email)
        if (!EMAIL.matches(normalised)) return Resource.error(OpsError.EmailInvalid)
        if (roster.isBootstrap(normalised)) return Resource.error(OpsError.StaffExists)
        val now = clock.instant()
        val row = Staff(Uuid7.generate(now), normalised, role, true, actor.email, now, now)
        return trail.record(actor, AuditAction.STAFF_ADD, row.id.toString(), detail = "role ${role.name}") {
            if (staff.insert(row)) Resource.success(row) else Resource.error(OpsError.StaffExists)
        }
    }

    private companion object {
        /** Deliberately loose (one @, a dot after it, no spaces): Access is what proves the address is real. */
        val EMAIL = Regex("[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}")
    }
}

/** ADMIN changes a role or switches someone off (or back on). A switched-off member's very next call is refused. */
class UpdateStaff(
    private val staff: StaffRepository,
    private val roster: StaffRoster,
    private val trail: AuditTrail,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        actor: Actor,
        staffId: java.util.UUID,
        role: StaffRole?,
        active: Boolean?,
    ): Resource<Staff> {
        val current = roster.byId(staffId) ?: return Resource.error(OpsError.StaffNotFound)
        if (current.fromConfig) return Resource.error(OpsError.StaffFromConfig)
        if (current.id == actor.staffId) return Resource.error(OpsError.CannotChangeSelf)
        val next =
            current.copy(
                role = role ?: current.role,
                active = active ?: current.active,
                updatedAt = clock.instant(),
            )
        val detail = describe(current, next)
        return trail.record(actor, AuditAction.STAFF_UPDATE, staffId.toString(), detail = detail) {
            if (staff.update(next)) {
                Resource.success(next.copy(version = current.version + 1))
            } else {
                Resource.error(OpsError.StaffChanged)
            }
        }
    }

    private fun describe(before: Staff, after: Staff): String =
        listOfNotNull(
            "role ${before.role.name}→${after.role.name}".takeIf { before.role != after.role },
            "active ${before.active}→${after.active}".takeIf { before.active != after.active },
        ).joinToString(", ").ifEmpty { "no change" }
}
