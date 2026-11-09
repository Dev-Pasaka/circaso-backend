package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.tables.InvitesTable
import com.circaso.messaging.domain.models.Invite
import com.circaso.messaging.domain.models.InviteState
import com.circaso.messaging.domain.repositories.InviteRepository
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.time.Instant
import java.util.UUID

/** Group invites over `invites`: by id, by person through `invites_user_idx`, by group through the pending key. */
class InviteRepositoryImpl(private val database: Database) : InviteRepository {
    override suspend fun invite(inviteId: UUID): Invite? =
        database.tx("invites.find") {
            InvitesTable
                .selectAll()
                .where { InvitesTable.inviteId eq inviteId }
                .map { it.toInvite() }
                .singleOrNull()
        }

    override suspend fun lockInvite(inviteId: UUID): Invite? =
        database.tx("invites.lock") {
            InvitesTable
                .selectAll()
                .where { InvitesTable.inviteId eq inviteId }
                .forUpdate(ForUpdateOption.ForUpdate)
                .map { it.toInvite() }
                .singleOrNull()
        }

    override suspend fun openFor(
        chatId: ChatId,
        userIds: Collection<UserId>,
        now: Instant,
    ): List<Invite> {
        if (userIds.isEmpty()) return emptyList()
        return database.tx("invites.open-for") {
            InvitesTable
                .selectAll()
                .where {
                    (InvitesTable.chatId eq chatId.value) and
                        (InvitesTable.userId inList userIds.map { it.value }) and
                        (InvitesTable.state eq InviteState.PENDING.name) and
                        (InvitesTable.expiresAt greater now)
                }.map { it.toInvite() }
                .toList()
        }
    }

    override suspend fun insert(invites: List<Invite>, now: Instant) {
        if (invites.isEmpty()) return
        database.tx("invites.insert") {
            for ((chatId, people) in invites.groupBy { it.chatId }) {
                // An invite that ran out while pending still holds `invites_pending_key` until the purge: close it.
                InvitesTable
                    .updateReturning(listOf(InvitesTable.inviteId), where = {
                        (InvitesTable.chatId eq chatId.value) and
                            (InvitesTable.userId inList people.map { it.userId.value }) and
                            (InvitesTable.state eq InviteState.PENDING.name) and
                            (InvitesTable.expiresAt lessEq now)
                    }) {
                        it[state] = InviteState.CANCELLED.name
                        it[decidedAt] = now
                    }.toList()
            }
            InvitesTable.batchInsert(invites, useMultiRowValues = true, shouldReturnGeneratedValues = false) {
                this[InvitesTable.inviteId] = it.inviteId
                this[InvitesTable.chatId] = it.chatId.value
                this[InvitesTable.userId] = it.userId.value
                this[InvitesTable.invitedBy] = it.invitedBy.value
                this[InvitesTable.state] = it.state.name
                this[InvitesTable.sealedKey] = it.sealedKey
                this[InvitesTable.createdAt] = it.createdAt
                this[InvitesTable.expiresAt] = it.expiresAt
            }
        }
    }

    override suspend fun decide(
        inviteId: UUID,
        state: InviteState,
        at: Instant,
    ): Boolean =
        database.tx("invites.decide") {
            InvitesTable
                .updateReturning(listOf(InvitesTable.inviteId), where = {
                    (InvitesTable.inviteId eq inviteId) and (InvitesTable.state eq InviteState.PENDING.name)
                }) {
                    it[InvitesTable.state] = state.name
                    it[decidedAt] = at
                }.toList()
                .isNotEmpty()
        }

    override suspend fun openOf(userId: UserId, now: Instant): List<Invite> =
        database.tx("invites.open-of") {
            InvitesTable
                .selectAll()
                .where {
                    (InvitesTable.userId eq userId.value) and
                        (InvitesTable.state eq InviteState.PENDING.name) and
                        (InvitesTable.expiresAt greater now)
                }.orderBy(InvitesTable.createdAt to SortOrder.DESC)
                .map { it.toInvite() }
                .toList()
        }

    override suspend fun openIn(chatId: ChatId, now: Instant): List<Invite> =
        database.tx("invites.open-in") {
            InvitesTable
                .selectAll()
                .where {
                    (InvitesTable.chatId eq chatId.value) and
                        (InvitesTable.state eq InviteState.PENDING.name) and
                        (InvitesTable.expiresAt greater now)
                }.orderBy(InvitesTable.createdAt to SortOrder.ASC)
                .map { it.toInvite() }
                .toList()
        }
}

private fun ResultRow.toInvite() =
    Invite(
        inviteId = this[InvitesTable.inviteId],
        chatId = ChatId(this[InvitesTable.chatId]),
        userId = UserId(this[InvitesTable.userId]),
        invitedBy = UserId(this[InvitesTable.invitedBy]),
        state = InviteState.valueOf(this[InvitesTable.state]),
        sealedKey = this[InvitesTable.sealedKey],
        createdAt = this[InvitesTable.createdAt],
        expiresAt = this[InvitesTable.expiresAt],
    )
