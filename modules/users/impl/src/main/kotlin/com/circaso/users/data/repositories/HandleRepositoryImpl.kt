package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.users.data.tables.HandlesTable
import com.circaso.users.domain.models.HandleClaim
import com.circaso.users.domain.repositories.HandleRepository
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** How long a released handle stays held for its old owner (Users → U5–U6). */
internal val HANDLE_HOLD: Duration = Duration.ofDays(30)

/**
 * The handle claim (users.md → Data → handles): the primary key decides who wins. A conflict is taken over only when
 * the row is the caller's own hold or an expired one, and a claim that loses to the purge between the two statements
 * gets one more try.
 */
class HandleRepositoryImpl(private val database: Database, private val clock: Clock) : HandleRepository {
    override suspend fun find(handle: String): HandleClaim? =
        database.tx("handles.find") {
            HandlesTable
                .selectAll()
                .where {
                    (HandlesTable.handle eq handle) and
                        (HandlesTable.expiresAt.isNull() or (HandlesTable.expiresAt greater clock.instant()))
                }.singleOrNull()
                ?.toClaim()
        }

    override suspend fun claim(
        handle: String,
        userId: UserId,
        at: Instant,
    ): Boolean =
        database.tx("handles.claim") {
            repeat(ATTEMPTS) {
                if (insert(handle, userId, at) || takeOver(handle, userId, at)) return@tx true
            }
            false
        }

    private suspend fun insert(
        handle: String,
        userId: UserId,
        at: Instant,
    ): Boolean {
        val statement =
            HandlesTable.insertIgnore {
                it[this.handle] = handle
                it[this.userId] = userId.value
                it[claimedAt] = at
            }
        return statement.insertedCount == 1
    }

    /** Takes the row over if it is [userId]'s own hold or a hold that has run out; nothing else moves. */
    private suspend fun takeOver(
        handle: String,
        userId: UserId,
        at: Instant,
    ): Boolean =
        HandlesTable
            .updateReturning(
                returning = listOf(HandlesTable.handle),
                where = {
                    (HandlesTable.handle eq handle) and
                        ((HandlesTable.userId eq userId.value) or (HandlesTable.expiresAt lessEq at))
                },
            ) {
                it[this.userId] = userId.value
                it[claimedAt] = at
                it[releasedAt] = null
                it[expiresAt] = null
            }.toList()
            .isNotEmpty()

    private companion object {
        const val ATTEMPTS = 2
    }
}

private fun ResultRow.toClaim() =
    HandleClaim(
        handle = this[HandlesTable.handle],
        userId = UserId(this[HandlesTable.userId]),
        claimedAt = this[HandlesTable.claimedAt],
        releasedAt = this[HandlesTable.releasedAt],
    )
