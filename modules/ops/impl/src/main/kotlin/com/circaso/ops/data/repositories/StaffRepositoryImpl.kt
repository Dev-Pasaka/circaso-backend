package com.circaso.ops.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.ops.data.tables.StaffTable
import com.circaso.ops.domain.models.Staff
import com.circaso.ops.domain.repositories.StaffRepository
import com.circaso.shared.StaffRole
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID

class StaffRepositoryImpl(private val database: Database) : StaffRepository {
    override suspend fun findByEmail(email: String): Staff? =
        database.tx("staff.find-by-email") {
            StaffTable
                .selectAll()
                .where { StaffTable.email eq email }
                .map { it.toStaff() }
                .singleOrNull()
        }

    override suspend fun find(id: UUID): Staff? =
        database.tx("staff.find") {
            StaffTable
                .selectAll()
                .where { StaffTable.staffId eq id }
                .map { it.toStaff() }
                .singleOrNull()
        }

    override suspend fun findMany(ids: Set<UUID>): Map<UUID, Staff> {
        if (ids.isEmpty()) return emptyMap()
        return database.tx("staff.find-many") {
            StaffTable
                .selectAll()
                .where { StaffTable.staffId inList ids }
                .map { it.toStaff() }
                .toList()
                .associateBy { it.id }
        }
    }

    override suspend fun list(limit: Int): List<Staff> =
        database.tx("staff.list") {
            StaffTable
                .selectAll()
                .orderBy(StaffTable.staffId to SortOrder.ASC)
                .limit(limit)
                .map { it.toStaff() }
                .toList()
        }

    override suspend fun insert(staff: Staff): Boolean =
        database.tx("staff.insert") {
            StaffTable
                .insertIgnore {
                    it[staffId] = staff.id
                    it[email] = staff.email
                    it[role] = staff.role.name
                    it[active] = staff.active
                    it[addedBy] = staff.addedBy
                    it[createdAt] = staff.createdAt
                    it[updatedAt] = staff.updatedAt
                }.insertedCount > 0
        }

    override suspend fun update(staff: Staff): Boolean =
        database.tx("staff.update") {
            StaffTable.update({ (StaffTable.staffId eq staff.id) and (StaffTable.version eq staff.version) }) {
                it[role] = staff.role.name
                it[active] = staff.active
                it[updatedAt] = staff.updatedAt
                it[version] = staff.version + 1
            } > 0
        }

    private fun ResultRow.toStaff() =
        Staff(
            id = this[StaffTable.staffId],
            email = this[StaffTable.email],
            role = StaffRole.valueOf(this[StaffTable.role]),
            active = this[StaffTable.active],
            addedBy = this[StaffTable.addedBy],
            createdAt = this[StaffTable.createdAt],
            updatedAt = this[StaffTable.updatedAt],
            version = this[StaffTable.version],
        )
}
