package com.circaso.identity.data.repositories

import com.circaso.identity.data.tables.SecurityLogTable
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.repositories.SecurityLogRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.time.Clock
import java.util.UUID

class SecurityLogRepositoryImpl(private val database: Database, private val clock: Clock) : SecurityLogRepository {
    /** Keyset paging on the primary key: `WHERE user_id = ? AND event_id < ? ORDER BY event_id DESC LIMIT ?`. */
    override suspend fun list(
        userId: UserId,
        before: UUID?,
        limit: Int,
    ): List<SecurityEvent> =
        database.tx("security-log.list") {
            SecurityLogTable
                .selectAll()
                .where {
                    val own = SecurityLogTable.userId eq userId.value
                    if (before == null) own else own and (SecurityLogTable.eventId less before)
                }.orderBy(SecurityLogTable.eventId to SortOrder.DESC)
                .limit(limit)
                .map { it.toSecurityEvent() }
                .toList()
        }

    /** For the store: adds an entry inside the open transaction, under a new time-ordered id. */
    internal suspend fun add(event: SecurityEvent) {
        database.tx("security-log.add") {
            SecurityLogTable.insert {
                it[userId] = event.userId.value
                it[eventId] = event.eventId ?: Uuid7.generate(clock.instant())
                it[type] = event.type.name
                it[deviceModel] = event.deviceModel
                it[detail] = event.detail
            }
        }
    }
}

private fun ResultRow.toSecurityEvent() =
    SecurityEvent(
        userId = UserId(this[SecurityLogTable.userId]),
        eventId = this[SecurityLogTable.eventId],
        type = SecurityEventType.valueOf(this[SecurityLogTable.type]),
        deviceModel = this[SecurityLogTable.deviceModel],
        detail = this[SecurityLogTable.detail],
    )
