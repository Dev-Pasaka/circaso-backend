package com.circaso.spaces.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.data.tables.BansTable
import com.circaso.spaces.data.tables.InvitesTable
import com.circaso.spaces.data.tables.MembersTable
import com.circaso.spaces.data.tables.PaymentsTable
import com.circaso.spaces.data.tables.RequestsTable
import com.circaso.spaces.domain.models.Ban
import com.circaso.spaces.domain.models.Invite
import com.circaso.spaces.domain.models.InviteState
import com.circaso.spaces.domain.models.JoinAsk
import com.circaso.spaces.domain.models.Membership
import com.circaso.spaces.domain.models.MembershipPayment
import com.circaso.spaces.domain.models.NotificationLevel
import com.circaso.spaces.domain.models.PaidState
import com.circaso.spaces.domain.models.PaymentKind
import com.circaso.spaces.domain.models.Period
import com.circaso.spaces.domain.models.RequestState
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.repositories.BanRepository
import com.circaso.spaces.domain.repositories.InviteRepository
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.PaymentRepository
import com.circaso.spaces.domain.repositories.RequestRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Instant
import java.util.UUID

/** `spaces.members`: one row per person and space; an ended paid membership keeps its row (Rejoin). */
class MemberRepositoryImpl(private val database: Database) : MemberRepository {
    override suspend fun find(spaceId: SpaceId, userId: UserId): Membership? =
        database.tx("members.find") {
            MembersTable
                .selectAll()
                .where { key(spaceId, userId) }
                .map { it.toMembership() }
                .singleOrNull()
        }

    override suspend fun insert(membership: Membership): Boolean =
        database.tx("members.insert") {
            MembersTable.insertIgnore { write(it, membership) }.insertedCount > 0
        }

    override suspend fun save(membership: Membership) {
        database.tx("members.save") {
            MembersTable.upsert(MembersTable.spaceId, MembersTable.userId) { write(it, membership) }
        }
    }

    override suspend fun delete(spaceId: SpaceId, userId: UserId): Boolean =
        database.tx("members.delete") { MembersTable.deleteWhere { key(spaceId, userId) } > 0 }

    override suspend fun ofUser(userId: UserId): List<Membership> =
        database.tx("members.of-user") {
            MembersTable
                .selectAll()
                .where { MembersTable.userId eq userId.value }
                .map { it.toMembership() }
                .toList()
        }

    override suspend fun team(spaceId: SpaceId): List<Membership> =
        database.tx("members.team") {
            MembersTable
                .selectAll()
                .where {
                    (MembersTable.spaceId eq spaceId.value) and (MembersTable.role neq Role.MEMBER.name) and active()
                }.map { it.toMembership() }
                .toList()
        }

    override suspend fun active(spaceId: SpaceId): List<Membership> =
        database.tx("members.active") {
            MembersTable
                .selectAll()
                .where { (MembersTable.spaceId eq spaceId.value) and active() }
                .map { it.toMembership() }
                .toList()
        }

    override suspend fun among(spaceId: SpaceId, userIds: Collection<UserId>): List<Membership> {
        if (userIds.isEmpty()) return emptyList()
        return database.tx("members.among") {
            MembersTable
                .selectAll()
                .where {
                    (MembersTable.spaceId eq spaceId.value) and
                        (MembersTable.userId inList userIds.map { it.value }.distinct()) and active()
                }.map { it.toMembership() }
                .toList()
        }
    }

    override suspend fun spacesOf(userIds: Collection<UserId>): Map<SpaceId, List<UserId>> {
        if (userIds.isEmpty()) return emptyMap()
        return database.tx("members.spaces-of") {
            MembersTable
                .select(MembersTable.spaceId, MembersTable.userId)
                .where { (MembersTable.userId inList userIds.map { it.value }.distinct()) and active() }
                .map { SpaceId(it[MembersTable.spaceId]) to UserId(it[MembersTable.userId]) }
                .toList()
                .groupBy({ it.first }, { it.second })
        }
    }

    override suspend fun page(
        spaceId: SpaceId,
        mutedAfter: Instant?,
        offset: Int,
        limit: Int,
    ): List<Membership> =
        database.tx("members.page") {
            MembersTable
                .selectAll()
                .where {
                    var condition: Op<Boolean> = (MembersTable.spaceId eq spaceId.value) and active()
                    if (mutedAfter != null) condition = condition and (MembersTable.mutedUntil greater mutedAfter)
                    condition
                }.orderBy(MembersTable.joinedAt to SortOrder.DESC, MembersTable.userId to SortOrder.ASC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toMembership() }
                .toList()
        }

    override suspend fun paidDue(
        graceStartedBefore: Instant,
        now: Instant,
        limit: Int,
    ): List<Membership> =
        database.tx("members.paid-due") {
            MembersTable
                .selectAll()
                .where {
                    ((MembersTable.paidState eq PaidState.ACTIVE.name) and (MembersTable.paidUntil lessEq now)) or
                        (
                            (MembersTable.paidState eq PaidState.GRACE.name) and
                                (MembersTable.paidUntil lessEq graceStartedBefore)
                        )
                }.limit(limit)
                .map { it.toMembership() }
                .toList()
        }

    override suspend fun reminderDue(
        monthlyBefore: Instant,
        yearlyBefore: Instant,
        limit: Int,
    ): List<Membership> =
        database.tx("members.reminder-due") {
            MembersTable
                .selectAll()
                .where {
                    val monthly =
                        (MembersTable.period eq Period.MONTHLY.name) and (MembersTable.paidUntil lessEq monthlyBefore)
                    val yearly =
                        (MembersTable.period eq Period.YEARLY.name) and (MembersTable.paidUntil lessEq yearlyBefore)
                    val unreminded =
                        MembersTable.remindedFor.isNull() or (MembersTable.remindedFor neq MembersTable.paidUntil)
                    (MembersTable.paidState eq PaidState.ACTIVE.name) and MembersTable.paidUntil.isNotNull() and
                        (monthly or yearly) and unreminded
                }.limit(limit)
                .map { it.toMembership() }
                .toList()
        }

    override suspend fun markRead(
        spaceId: SpaceId,
        userId: UserId,
        postId: UUID,
    ) {
        database.tx("members.mark-read") {
            MembersTable.update(where = {
                key(spaceId, userId) and (MembersTable.readUpTo.isNull() or (MembersTable.readUpTo less postId))
            }) { it[readUpTo] = postId }
        }
    }

    override suspend fun deleteAllOf(userId: UserId) {
        database.tx("members.delete-all-of") {
            MembersTable.deleteWhere { MembersTable.userId eq userId.value }
            RequestsTable.deleteWhere { RequestsTable.userId eq userId.value }
            InvitesTable.deleteWhere { InvitesTable.inviteeId eq userId.value }
        }
    }

    override suspend fun clearPaid(spaceId: SpaceId) {
        database.tx("members.clear-paid") {
            MembersTable.deleteWhere {
                (MembersTable.spaceId eq spaceId.value) and
                    (MembersTable.paidState eq PaidState.ENDED.name)
            }
            MembersTable.update(where = { MembersTable.spaceId eq spaceId.value }) {
                it[paidState] = null
                it[period] = null
                it[paidUntil] = null
                it[complimentary] = false
                it[remindedFor] = null
            }
        }
    }

    override suspend fun makeComplimentary(spaceId: SpaceId) {
        database.tx("members.make-complimentary") {
            MembersTable.update(
                where = { (MembersTable.spaceId eq spaceId.value) and MembersTable.paidState.isNull() },
            ) {
                it[paidState] = PaidState.ACTIVE.name
                it[period] = Period.ONE_TIME.name
                it[paidUntil] = null
                it[complimentary] = true
            }
        }
    }

    private fun key(spaceId: SpaceId, userId: UserId): Op<Boolean> =
        (MembersTable.spaceId eq spaceId.value) and (MembersTable.userId eq userId.value)

    private fun active(): Op<Boolean> =
        MembersTable.paidState.isNull() or (MembersTable.paidState neq PaidState.ENDED.name)
}

private fun MembersTable.write(row: UpdateBuilder<*>, m: Membership) {
    row[spaceId] = m.spaceId.value
    row[userId] = m.userId.value
    row[role] = m.role.name
    row[joinedAt] = m.joinedAt
    row[invitedBy] = m.invitedBy?.value
    row[notifications] = m.notifications.name
    row[mutedUntil] = m.mutedUntil
    row[paidState] = m.paidState?.name
    row[period] = m.period?.name
    row[paidUntil] = m.paidUntil
    row[paidAmountMinor] = m.paidAmountMinor
    row[complimentary] = m.complimentary
    row[remindedFor] = m.remindedFor
    row[readUpTo] = m.readUpTo
}

private fun ResultRow.toMembership() =
    Membership(
        spaceId = SpaceId(this[MembersTable.spaceId]),
        userId = UserId(this[MembersTable.userId]),
        role = Role.valueOf(this[MembersTable.role]),
        joinedAt = this[MembersTable.joinedAt],
        invitedBy = this[MembersTable.invitedBy]?.let(::UserId),
        notifications = NotificationLevel.valueOf(this[MembersTable.notifications]),
        mutedUntil = this[MembersTable.mutedUntil],
        paidState = this[MembersTable.paidState]?.let(PaidState::valueOf),
        period = this[MembersTable.period]?.let(Period::valueOf),
        paidUntil = this[MembersTable.paidUntil],
        paidAmountMinor = this[MembersTable.paidAmountMinor],
        complimentary = this[MembersTable.complimentary],
        remindedFor = this[MembersTable.remindedFor],
        readUpTo = this[MembersTable.readUpTo],
    )

class BanRepositoryImpl(private val database: Database) : BanRepository {
    override suspend fun find(spaceId: SpaceId, userId: UserId): Ban? =
        database.tx("bans.find") {
            BansTable
                .selectAll()
                .where { (BansTable.spaceId eq spaceId.value) and (BansTable.userId eq userId.value) }
                .map { it.toBan() }
                .singleOrNull()
        }

    override suspend fun insert(ban: Ban): Boolean =
        database.tx("bans.insert") {
            BansTable
                .insertIgnore {
                    it[spaceId] = ban.spaceId.value
                    it[userId] = ban.userId.value
                    it[bannedBy] = ban.bannedBy.value
                    it[bannedAt] = ban.bannedAt
                }.insertedCount > 0
        }

    override suspend fun delete(spaceId: SpaceId, userId: UserId): Boolean =
        database.tx("bans.delete") {
            BansTable.deleteWhere { (BansTable.spaceId eq spaceId.value) and (BansTable.userId eq userId.value) } > 0
        }

    override suspend fun ofSpace(spaceId: SpaceId): List<Ban> =
        database.tx("bans.of-space") {
            BansTable
                .selectAll()
                .where { BansTable.spaceId eq spaceId.value }
                .map { it.toBan() }
                .toList()
        }

    override suspend fun spacesBanning(userId: UserId): Set<SpaceId> =
        database.tx("bans.spaces-banning") {
            BansTable
                .select(BansTable.spaceId)
                .where { BansTable.userId eq userId.value }
                .map { SpaceId(it[BansTable.spaceId]) }
                .toList()
                .toSet()
        }

    private fun ResultRow.toBan() =
        Ban(
            SpaceId(this[BansTable.spaceId]),
            UserId(this[BansTable.userId]),
            UserId(this[BansTable.bannedBy]),
            this[BansTable.bannedAt],
        )
}

class RequestRepositoryImpl(private val database: Database) : RequestRepository {
    override suspend fun find(spaceId: SpaceId, userId: UserId): JoinAsk? =
        database.tx("requests.find") {
            RequestsTable
                .selectAll()
                .where { (RequestsTable.spaceId eq spaceId.value) and (RequestsTable.userId eq userId.value) }
                .map { it.toRequest() }
                .singleOrNull()
        }

    override suspend fun save(request: JoinAsk) {
        database.tx("requests.save") {
            RequestsTable.upsert(RequestsTable.spaceId, RequestsTable.userId) {
                it[spaceId] = request.spaceId.value
                it[userId] = request.userId.value
                it[state] = request.state.name
                it[requestedAt] = request.requestedAt
                it[answeredAt] = request.answeredAt
            }
        }
    }

    override suspend fun pending(spaceId: SpaceId): List<JoinAsk> =
        database.tx("requests.pending") {
            RequestsTable
                .selectAll()
                .where {
                    (RequestsTable.spaceId eq spaceId.value) and (RequestsTable.state eq RequestState.PENDING.name)
                }.map { it.toRequest() }
                .toList()
        }

    private fun ResultRow.toRequest() =
        JoinAsk(
            SpaceId(this[RequestsTable.spaceId]),
            UserId(this[RequestsTable.userId]),
            RequestState.valueOf(this[RequestsTable.state]),
            this[RequestsTable.requestedAt],
            this[RequestsTable.answeredAt],
        )
}

class InviteRepositoryImpl(private val database: Database) : InviteRepository {
    override suspend fun find(inviteId: UUID): Invite? =
        database.tx("invites.find") {
            InvitesTable
                .selectAll()
                .where { InvitesTable.inviteId eq inviteId }
                .map { it.toInvite() }
                .singleOrNull()
        }

    override suspend fun of(spaceId: SpaceId, inviteeId: UserId): Invite? =
        database.tx("invites.of") {
            InvitesTable
                .selectAll()
                .where { (InvitesTable.spaceId eq spaceId.value) and (InvitesTable.inviteeId eq inviteeId.value) }
                .map { it.toInvite() }
                .singleOrNull()
        }

    override suspend fun save(invite: Invite) {
        database.tx("invites.save") {
            InvitesTable.upsert(InvitesTable.spaceId, InvitesTable.inviteeId) {
                it[inviteId] = invite.inviteId
                it[spaceId] = invite.spaceId.value
                it[inviteeId] = invite.inviteeId.value
                it[inviterId] = invite.inviterId.value
                it[state] = invite.state.name
                it[invitedAt] = invite.invitedAt
                it[expiresAt] = invite.expiresAt
                it[answeredAt] = invite.answeredAt
            }
        }
    }

    override suspend fun liveFor(inviteeId: UserId, now: Instant): List<Invite> =
        database.tx("invites.live-for") {
            InvitesTable
                .selectAll()
                .where {
                    (InvitesTable.inviteeId eq inviteeId.value) and (InvitesTable.state eq InviteState.INVITED.name) and
                        (InvitesTable.expiresAt greater now)
                }.map { it.toInvite() }
                .toList()
        }

    override suspend fun ofSpace(spaceId: SpaceId, since: Instant): List<Invite> =
        database.tx("invites.of-space") {
            InvitesTable
                .selectAll()
                .where { (InvitesTable.spaceId eq spaceId.value) and (InvitesTable.invitedAt greaterEq since) }
                .orderBy(InvitesTable.invitedAt to SortOrder.DESC)
                .map { it.toInvite() }
                .toList()
        }

    private fun ResultRow.toInvite() =
        Invite(
            inviteId = this[InvitesTable.inviteId],
            spaceId = SpaceId(this[InvitesTable.spaceId]),
            inviteeId = UserId(this[InvitesTable.inviteeId]),
            inviterId = UserId(this[InvitesTable.inviterId]),
            state = InviteState.valueOf(this[InvitesTable.state]),
            invitedAt = this[InvitesTable.invitedAt],
            expiresAt = this[InvitesTable.expiresAt],
            answeredAt = this[InvitesTable.answeredAt],
        )
}

class PaymentRepositoryImpl(private val database: Database) : PaymentRepository {
    override suspend fun insert(payment: MembershipPayment) {
        database.tx("payments.insert") {
            PaymentsTable.insert {
                it[paymentId] = payment.paymentId
                it[spaceId] = payment.spaceId.value
                it[payerId] = payment.payerId.value
                it[kind] = payment.kind.name
                it[amountMinor] = payment.amountMinor
                it[period] = payment.period.name
                it[previousMinor] = payment.previousAmountMinor
                it[createdAt] = payment.createdAt
                it[expiresAt] = payment.expiresAt
                it[confirmedAt] = payment.confirmedAt
            }
        }
    }

    override suspend fun find(paymentId: UUID): MembershipPayment? =
        database.tx("payments.find") {
            PaymentsTable
                .selectAll()
                .where { PaymentsTable.paymentId eq paymentId }
                .map {
                    MembershipPayment(
                        paymentId = it[PaymentsTable.paymentId],
                        spaceId = SpaceId(it[PaymentsTable.spaceId]),
                        payerId = UserId(it[PaymentsTable.payerId]),
                        kind = PaymentKind.valueOf(it[PaymentsTable.kind]),
                        amountMinor = it[PaymentsTable.amountMinor],
                        period = Period.valueOf(it[PaymentsTable.period]),
                        previousAmountMinor = it[PaymentsTable.previousMinor],
                        createdAt = it[PaymentsTable.createdAt],
                        expiresAt = it[PaymentsTable.expiresAt],
                        confirmedAt = it[PaymentsTable.confirmedAt],
                    )
                }.singleOrNull()
        }

    override suspend fun confirm(paymentId: UUID, at: Instant): Boolean =
        database.tx("payments.confirm") {
            PaymentsTable.update(where = {
                (PaymentsTable.paymentId eq paymentId) and PaymentsTable.confirmedAt.isNull()
            }) { it[confirmedAt] = at } > 0
        }
}
