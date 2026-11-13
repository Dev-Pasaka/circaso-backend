package com.circaso.support.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.support.data.tables.DiagnosticLogsTable
import com.circaso.support.domain.models.DiagnosticLog
import com.circaso.support.domain.repositories.DiagnosticLogRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.time.Instant
import java.util.UUID

/** SU5 and the staff desk over `support.diagnostic_logs`; a row past its `expires_at` reads as gone. */
class DiagnosticLogRepositoryImpl(private val database: Database) : DiagnosticLogRepository {
    override suspend fun insert(log: DiagnosticLog): Boolean =
        database.tx("diagnostic-logs.insert") {
            DiagnosticLogsTable
                .insertIgnore {
                    it[logId] = log.logId
                    it[code] = log.code
                    it[userId] = log.userId.value
                    it[platform] = log.platform
                    it[appVersion] = log.appVersion
                    it[osVersion] = log.osVersion
                    it[deviceModel] = log.deviceModel
                    it[sizeBytes] = log.sizeBytes
                    it[this.log] = log.log
                    it[createdAt] = log.createdAt
                    it[expiresAt] = log.expiresAt
                }.insertedCount > 0
        }

    override suspend fun byCode(code: String): DiagnosticLog? =
        database.tx("diagnostic-logs.by-code") {
            DiagnosticLogsTable
                .select(LINE)
                .where { (DiagnosticLogsTable.code eq code) and live() }
                .map { it.toLog(withText = false) }
                .singleOrNull()
        }

    override suspend fun ofUser(userId: UserId, limit: Int): List<DiagnosticLog> =
        database.tx("diagnostic-logs.of-user") {
            DiagnosticLogsTable
                .select(LINE)
                .where { (DiagnosticLogsTable.userId eq userId.value) and live() }
                .orderBy(DiagnosticLogsTable.logId to SortOrder.DESC)
                .limit(limit)
                .map { it.toLog(withText = false) }
                .toList()
        }

    override suspend fun find(logId: UUID): DiagnosticLog? =
        database.tx("diagnostic-logs.find") {
            DiagnosticLogsTable
                .selectAll()
                .where { (DiagnosticLogsTable.logId eq logId) and live() }
                .map { it.toLog(withText = true) }
                .singleOrNull()
        }

    override suspend fun deleteOfUser(userId: UserId): Int =
        database.tx("diagnostic-logs.delete-of-user") {
            DiagnosticLogsTable.deleteWhere { DiagnosticLogsTable.userId eq userId.value }
        }

    private fun live() = DiagnosticLogsTable.expiresAt greater Instant.now()

    private fun ResultRow.toLog(withText: Boolean) =
        DiagnosticLog(
            logId = this[DiagnosticLogsTable.logId],
            code = this[DiagnosticLogsTable.code],
            userId = UserId(this[DiagnosticLogsTable.userId]),
            platform = this[DiagnosticLogsTable.platform],
            appVersion = this[DiagnosticLogsTable.appVersion],
            osVersion = this[DiagnosticLogsTable.osVersion],
            deviceModel = this[DiagnosticLogsTable.deviceModel],
            sizeBytes = this[DiagnosticLogsTable.sizeBytes],
            log = if (withText) this[DiagnosticLogsTable.log] else "",
            createdAt = this[DiagnosticLogsTable.createdAt],
            expiresAt = this[DiagnosticLogsTable.expiresAt],
        )

    private companion object {
        /** Every column but the text: lists never load a 2 MiB log. */
        val LINE: List<Column<*>> = DiagnosticLogsTable.columns.filter { it != DiagnosticLogsTable.log }
    }
}
