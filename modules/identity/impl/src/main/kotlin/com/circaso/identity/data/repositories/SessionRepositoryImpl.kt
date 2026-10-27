package com.circaso.identity.data.repositories

import com.circaso.identity.api.SessionId
import com.circaso.identity.data.tables.SessionsTable
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.DeviceId
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Clock
import java.time.Duration

/**
 * Sessions expire 30 days after their last rotation (`expires_at`): every read hides an expired row at once, and
 * `identity.purge-expired` deletes it later.
 */
class SessionRepositoryImpl(private val database: Database, private val clock: Clock) : SessionRepository {
    override suspend fun find(sessionId: SessionId): Session? =
        database.tx("sessions.find") {
            SessionsTable
                .selectAll()
                .where {
                    (SessionsTable.sessionId eq sessionId.value) and
                        (SessionsTable.expiresAt greater clock.instant())
                }.map { it.toSession() }
                .singleOrNull()
        }

    override suspend fun revoke(sessionId: SessionId): Session? =
        database.tx("sessions.revoke") {
            SessionsTable
                .updateReturning(where = {
                    (SessionsTable.sessionId eq sessionId.value) and
                        (SessionsTable.status eq SessionStatus.ACTIVE.name) and
                        (SessionsTable.expiresAt greater clock.instant())
                }) {
                    it[status] = SessionStatus.REVOKED.name
                }.map { it.toSession() }
                .singleOrNull()
        }

    /** For the store: writes the session as it is now, inside the open transaction; its 30 days start again. */
    internal suspend fun save(session: Session) {
        database.tx("sessions.save") {
            SessionsTable.upsert(SessionsTable.sessionId) {
                it[sessionId] = session.sessionId.value
                it[userId] = session.userId.value
                it[deviceId] = session.deviceId.value
                it[currentTokenHash] = session.currentTokenHash
                it[scope] = session.scope.wire
                it[status] = session.status.name
                it[createdAt] = session.createdAt
                it[rotatedAt] = session.rotatedAt
                it[expiresAt] = session.rotatedAt.plus(LIFETIME)
            }
        }
    }

    private companion object {
        /** Unused for this long after its last rotation, a session is gone. */
        val LIFETIME: Duration = Duration.ofDays(30)
    }
}

private fun ResultRow.toSession() =
    Session(
        sessionId = SessionId(this[SessionsTable.sessionId]),
        userId = UserId(this[SessionsTable.userId]),
        deviceId = DeviceId(this[SessionsTable.deviceId]),
        currentTokenHash = this[SessionsTable.currentTokenHash],
        scope = Scope.fromWire(this[SessionsTable.scope]) ?: Scope.APP,
        status = SessionStatus.valueOf(this[SessionsTable.status]),
        createdAt = this[SessionsTable.createdAt],
        rotatedAt = this[SessionsTable.rotatedAt],
    )
