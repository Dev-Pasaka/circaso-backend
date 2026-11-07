package com.circaso.ops.domain.support

import com.circaso.ops.domain.models.Staff
import com.circaso.ops.domain.repositories.StaffRepository
import com.circaso.shared.StaffRole
import java.time.Instant
import java.util.UUID

/**
 * The staff list as everyone reads it: the `ops.staff` rows plus the bootstrap admins from config. A bootstrap admin's
 * id is derived from their email, so their audit lines keep one actor id across restarts; when the table also has a
 * row for the same email, the config wins (always an active ADMIN).
 */
class StaffRoster(private val staff: StaffRepository, private val settings: OpsSettings) {
    suspend fun byEmail(email: String): Staff? {
        val normalised = normalise(email)
        if (normalised in settings.bootstrapAdmins) return bootstrap(normalised)
        return staff.findByEmail(normalised)
    }

    suspend fun byId(id: UUID): Staff? = bootstrapById(id) ?: staff.find(id)

    suspend fun byIds(ids: Set<UUID>): Map<UUID, Staff> {
        val fromConfig = ids.mapNotNull { bootstrapById(it) }.associateBy { it.id }
        return fromConfig + staff.findMany(ids - fromConfig.keys)
    }

    /** Bootstrap admins first, then the table's rows not shadowed by them. */
    suspend fun all(limit: Int): List<Staff> {
        val fromConfig = settings.bootstrapAdmins.sorted().map(::bootstrap)
        val rows = staff.list(limit).filter { it.email !in settings.bootstrapAdmins }
        return fromConfig + rows
    }

    fun isBootstrap(email: String): Boolean = normalise(email) in settings.bootstrapAdmins

    private fun bootstrapById(id: UUID): Staff? =
        settings.bootstrapAdmins.firstOrNull { idOf(it) == id }?.let(::bootstrap)

    private fun bootstrap(email: String) =
        Staff(
            id = idOf(email),
            email = email,
            role = StaffRole.ADMIN,
            active = true,
            addedBy = CONFIG,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            fromConfig = true,
        )

    companion object {
        const val CONFIG = "config"

        fun normalise(email: String): String = email.trim().lowercase()

        /** A stable, name-based id: the same email always gets the same id. */
        fun idOf(email: String): UUID = UUID.nameUUIDFromBytes("circaso-ops-bootstrap:$email".toByteArray())
    }
}
