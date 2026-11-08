package com.circaso.ops.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.ops.data.tables.AuditLogTable
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.AuditEntry
import com.circaso.ops.domain.models.AuditFilter
import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ops.domain.repositories.AuditRepository
import com.circaso.shared.StaffRole
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant
import java.util.UUID

class AuditRepositoryImpl(private val database: Database) : AuditRepository {
    override suspend fun begin(entry: AuditEntry) {
        database.tx("audit.begin") {
            AuditLogTable.insert {
                it[entryId] = entry.id
                it[actorId] = entry.actorId
                it[actorEmail] = entry.actorEmail
                it[actorRole] = entry.actorRole.name
                it[action] = entry.action
                it[targetType] = entry.targetType
                it[targetId] = entry.targetId
                it[reason] = entry.reason
                it[detail] = entry.detail
                it[outcome] = entry.outcome.name
                it[errorCode] = entry.errorCode
                it[requestId] = entry.requestId
                it[at] = entry.at
            }
        }
    }

    override suspend fun finish(
        id: UUID,
        outcome: AuditOutcome,
        errorCode: String?,
        finishedAt: Instant,
    ) {
        database.tx("audit.finish") {
            AuditLogTable.update({ AuditLogTable.entryId eq id }) {
                it[AuditLogTable.outcome] = outcome.name
                it[AuditLogTable.errorCode] = errorCode
                it[AuditLogTable.finishedAt] = finishedAt
            }
        }
    }

    override suspend fun list(
        filter: AuditFilter,
        before: UUID?,
        limit: Int,
    ): List<AuditEntry> =
        database.tx("audit.list") {
            AuditLogTable
                .selectAll()
                .where { matches(filter) and older(before) }
                .orderBy(AuditLogTable.entryId to SortOrder.DESC)
                .limit(limit)
                .map { it.toEntry() }
                .toList()
        }

    override suspend fun latestSucceeded(action: String, targetId: String): AuditEntry? =
        database.tx("audit.latest") {
            val target = AuditAction.parse(action)?.target?.wire ?: return@tx null
            AuditLogTable
                .selectAll()
                .where {
                    (AuditLogTable.targetType eq target) and (AuditLogTable.targetId eq targetId) and
                        (AuditLogTable.action eq action) and (AuditLogTable.outcome eq AuditOutcome.SUCCEEDED.name)
                }.orderBy(AuditLogTable.entryId to SortOrder.DESC)
                .limit(1)
                .map { it.toEntry() }
                .singleOrNull()
        }

    private fun matches(filter: AuditFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.actorId?.let { op = op and (AuditLogTable.actorId eq it) }
        filter.action?.let { op = op and (AuditLogTable.action eq it.wire) }
        filter.target?.let { op = op and (AuditLogTable.targetType eq it.wire) }
        filter.targetId?.let { op = op and (AuditLogTable.targetId eq it) }
        return op
    }

    private fun older(before: UUID?): Op<Boolean> = before?.let { AuditLogTable.entryId less it } ?: Op.TRUE

    private fun ResultRow.toEntry() =
        AuditEntry(
            id = this[AuditLogTable.entryId],
            actorId = this[AuditLogTable.actorId],
            actorEmail = this[AuditLogTable.actorEmail],
            actorRole = StaffRole.valueOf(this[AuditLogTable.actorRole]),
            action = this[AuditLogTable.action],
            targetType = this[AuditLogTable.targetType],
            targetId = this[AuditLogTable.targetId],
            reason = this[AuditLogTable.reason],
            detail = this[AuditLogTable.detail],
            outcome = AuditOutcome.valueOf(this[AuditLogTable.outcome]),
            errorCode = this[AuditLogTable.errorCode],
            requestId = this[AuditLogTable.requestId],
            at = this[AuditLogTable.at],
        )
}
