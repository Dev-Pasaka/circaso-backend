package com.circaso.support.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.support.api.AssigneeFilter
import com.circaso.support.api.TicketCategory
import com.circaso.support.api.TicketContext
import com.circaso.support.api.TicketCounts
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketQueueFilter
import com.circaso.support.api.TicketSource
import com.circaso.support.api.TicketStatus
import com.circaso.support.api.WaitingOn
import com.circaso.support.data.tables.TicketsTable
import com.circaso.support.domain.models.Ticket
import com.circaso.support.domain.repositories.TicketRepository
import com.circaso.support.domain.repositories.isOldestFirst
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertReturning
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.time.Instant
import java.util.UUID

/** SU1-SU4, the staff desk and account removal over `support.tickets`. */
class TicketRepositoryImpl(private val database: Database) : TicketRepository {
    override suspend fun insert(ticket: Ticket): Ticket =
        database.tx("tickets.insert") {
            val number =
                TicketsTable
                    .insertReturning(listOf(TicketsTable.number)) {
                        it[ticketId] = ticket.ticketId.value
                        it[userId] = ticket.userId.value
                        it[subject] = ticket.subject
                        it[description] = ticket.description
                        it[category] = ticket.category.name
                        it[status] = ticket.status.name
                        it[origin] = ticket.source.name
                        it[contextTransferId] = ticket.context.transferId
                        it[contextChatId] = ticket.context.chatId
                        it[assigneeId] = ticket.assigneeId
                        it[waitingOn] = ticket.waitingOn?.name
                        it[createdAt] = ticket.createdAt
                        it[updatedAt] = ticket.updatedAt
                        it[lastMessageAt] = ticket.lastMessageAt
                        it[version] = ticket.version
                    }.map { it[TicketsTable.number] }
                    .single()
            ticket.copy(number = number)
        }

    override suspend fun find(ticketId: TicketId): Ticket? =
        database.tx("tickets.find") {
            TicketsTable
                .selectAll()
                .where { TicketsTable.ticketId eq ticketId.value }
                .map { it.toTicket() }
                .singleOrNull()
        }

    override suspend fun listOfUser(
        userId: UserId,
        after: TicketId?,
        limit: Int,
    ): List<Ticket> =
        database.tx("tickets.list-of-user") {
            TicketsTable
                .selectAll()
                .where { (TicketsTable.userId eq userId.value) and older(after) }
                .orderBy(TicketsTable.ticketId to SortOrder.DESC)
                .limit(limit)
                .map { it.toTicket() }
                .toList()
        }

    override suspend fun queue(
        filter: TicketQueueFilter,
        after: TicketId?,
        limit: Int,
    ): List<Ticket> {
        val isOldestFirst = filter.isOldestFirst()
        return database.tx("tickets.queue") {
            TicketsTable
                .selectAll()
                .where { matching(filter) and if (isOldestFirst) newer(after) else older(after) }
                .orderBy(TicketsTable.ticketId to if (isOldestFirst) SortOrder.ASC else SortOrder.DESC)
                .limit(limit)
                .map { it.toTicket() }
                .toList()
        }
    }

    override suspend fun userReplied(
        ticketId: TicketId,
        userId: UserId,
        at: Instant,
    ): Ticket? =
        database.tx("tickets.user-replied") {
            change({ (TicketsTable.userId eq userId.value) and notClosed() }, ticketId) {
                it[status] = TicketStatus.OPEN.name
                it[waitingOn] = WaitingOn.STAFF.name
                it[updatedAt] = at
                it[lastMessageAt] = at
            }
        }

    override suspend fun staffReplied(ticketId: TicketId, at: Instant): Ticket? =
        database.tx("tickets.staff-replied") {
            change({ notClosed() }, ticketId) {
                it[status] = TicketStatus.PENDING_USER.name
                it[waitingOn] = WaitingOn.USER.name
                it[updatedAt] = at
                it[lastMessageAt] = at
            }
        }

    override suspend fun noted(ticketId: TicketId, at: Instant): Ticket? =
        database.tx("tickets.noted") {
            change({ Op.TRUE }, ticketId) { it[updatedAt] = at }
        }

    override suspend fun assign(
        ticketId: TicketId,
        assigneeId: UUID?,
        at: Instant,
    ): Ticket? =
        database.tx("tickets.assign") {
            change({ Op.TRUE }, ticketId) {
                it[TicketsTable.assigneeId] = assigneeId
                it[updatedAt] = at
            }
        }

    override suspend fun changeStatus(
        ticketId: TicketId,
        status: TicketStatus,
        at: Instant,
    ): Ticket? =
        database.tx("tickets.change-status") {
            change({ TicketsTable.status neq status.name }, ticketId) {
                it[TicketsTable.status] = status.name
                it[waitingOn] = waitingOnFor(status)?.name
                it[updatedAt] = at
            }
        }

    override suspend fun counts(): TicketCounts =
        database.tx("tickets.counts") {
            TicketCounts(
                open = count { TicketsTable.status eq TicketStatus.OPEN.name },
                pendingUser = count { TicketsTable.status eq TicketStatus.PENDING_USER.name },
                unassigned =
                    count {
                        (TicketsTable.status eq TicketStatus.OPEN.name) and
                            TicketsTable.assigneeId.isNull()
                    },
            )
        }

    override suspend fun deleteOfUser(userId: UserId): Int =
        database.tx("tickets.delete-of-user") {
            TicketsTable.deleteWhere { TicketsTable.userId eq userId.value }
        }

    /**
     * The one shape of every change: `UPDATE … SET <set>, version = version + 1 WHERE ticket_id = ? AND <condition>
     * RETURNING *`, the row as it now is, or null when the condition failed.
     */
    private suspend fun change(
        condition: () -> Op<Boolean>,
        ticketId: TicketId,
        set: TicketsTable.(UpdateStatement) -> Unit,
    ): Ticket? =
        TicketsTable
            .updateReturning(where = { (TicketsTable.ticketId eq ticketId.value) and condition() }) {
                set(it)
                it[version] = version + 1L
            }.map { it.toTicket() }
            .singleOrNull()

    private suspend fun count(where: () -> Op<Boolean>): Long =
        TicketsTable
            .selectAll()
            .where { where() }
            .count()

    private fun notClosed(): Op<Boolean> = TicketsTable.status neq TicketStatus.CLOSED.name

    private fun matching(filter: TicketQueueFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.status?.let { op = op and (TicketsTable.status eq it.name) }
        filter.category?.let { op = op and (TicketsTable.category eq it.name) }
        when (val assignee = filter.assignee) {
            AssigneeFilter.Anyone -> Unit
            AssigneeFilter.Unassigned -> op = op and TicketsTable.assigneeId.isNull()
            is AssigneeFilter.Staff -> op = op and (TicketsTable.assigneeId eq assignee.staffId)
        }
        // A ticket's created_at is the instant in its id, so "created before" is a bound on the id: every index ends
        // in ticket_id and serves it.
        filter.createdBefore?.let { op = op and (TicketsTable.ticketId less firstIdAt(it)) }
        return op
    }

    private fun older(after: TicketId?): Op<Boolean> =
        if (after == null) Op.TRUE else TicketsTable.ticketId less after.value

    private fun newer(after: TicketId?): Op<Boolean> =
        if (after == null) Op.TRUE else TicketsTable.ticketId greater after.value

    /** The smallest UUIDv7 of [instant]'s millisecond: every id made before it sorts below. */
    private fun firstIdAt(instant: Instant): UUID =
        UUID((instant.toEpochMilli() shl MILLIS_SHIFT) or VERSION_7, VARIANT_ONLY)

    private fun ResultRow.toTicket() =
        Ticket(
            ticketId = TicketId(this[TicketsTable.ticketId]),
            number = this[TicketsTable.number],
            userId = UserId(this[TicketsTable.userId]),
            subject = this[TicketsTable.subject],
            description = this[TicketsTable.description],
            category = TicketCategory.valueOf(this[TicketsTable.category]),
            status = TicketStatus.valueOf(this[TicketsTable.status]),
            source = TicketSource.valueOf(this[TicketsTable.origin]),
            context = TicketContext(this[TicketsTable.contextTransferId], this[TicketsTable.contextChatId]),
            assigneeId = this[TicketsTable.assigneeId],
            waitingOn = this[TicketsTable.waitingOn]?.let(WaitingOn::valueOf),
            createdAt = this[TicketsTable.createdAt],
            updatedAt = this[TicketsTable.updatedAt],
            lastMessageAt = this[TicketsTable.lastMessageAt],
            version = this[TicketsTable.version],
        )

    private companion object {
        const val MILLIS_SHIFT = 16
        const val VERSION_7 = 0x7000L
        const val VARIANT_ONLY = Long.MIN_VALUE // 0x8000…: the RFC 9562 variant bits, the rest zero
    }
}

/** Whose turn it is after staff set [status]: open waits on staff, resolved and closed on nobody. */
internal fun waitingOnFor(status: TicketStatus): WaitingOn? =
    when (status) {
        TicketStatus.OPEN -> WaitingOn.STAFF
        TicketStatus.PENDING_USER -> WaitingOn.USER
        TicketStatus.RESOLVED, TicketStatus.CLOSED -> null
    }
