package com.circaso.support.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.TicketCounts
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketQueueFilter
import com.circaso.support.api.TicketStatus
import com.circaso.support.domain.models.Ticket
import com.circaso.support.domain.models.TicketThread
import com.circaso.support.domain.models.TicketsPage
import com.circaso.support.domain.usecases.AddStaffNote
import com.circaso.support.domain.usecases.AssignTicket
import com.circaso.support.domain.usecases.ChangeTicketStatus
import com.circaso.support.domain.usecases.CountTickets
import com.circaso.support.domain.usecases.GetTicket
import com.circaso.support.domain.usecases.ListTicketQueue
import com.circaso.support.domain.usecases.ListUserTickets
import com.circaso.support.domain.usecases.ReplyAsStaff
import java.util.UUID

/** The staff side, behind `SupportDesk` (the ops module's routes on the ops gateway). */
class SupportDeskService(
    private val listTicketQueue: ListTicketQueue,
    private val getTicket: GetTicket,
    private val listUserTickets: ListUserTickets,
    private val replyAsStaff: ReplyAsStaff,
    private val addStaffNote: AddStaffNote,
    private val assignTicket: AssignTicket,
    private val changeTicketStatus: ChangeTicketStatus,
    private val countTickets: CountTickets,
) {
    suspend fun queue(
        filter: TicketQueueFilter,
        after: TicketId?,
        limit: Int,
    ): Resource<TicketsPage> = listTicketQueue(filter, after, limit)

    suspend fun ticket(ticketId: TicketId): Resource<TicketThread> = getTicket(ticketId)

    suspend fun ofUser(userId: UserId, limit: Int): Resource<List<Ticket>> = listUserTickets(userId, limit)

    suspend fun reply(
        ticketId: TicketId,
        staffId: UUID,
        body: String,
    ): Resource<TicketThread> = replyAsStaff(ticketId, staffId, body)

    suspend fun note(
        ticketId: TicketId,
        staffId: UUID,
        body: String,
    ): Resource<TicketThread> = addStaffNote(ticketId, staffId, body)

    suspend fun assign(ticketId: TicketId, assigneeId: UUID?): Resource<TicketThread> =
        assignTicket(ticketId, assigneeId)

    suspend fun changeStatus(ticketId: TicketId, status: TicketStatus): Resource<TicketThread> =
        changeTicketStatus(ticketId, status)

    suspend fun counts(): Resource<TicketCounts> = countTickets()
}
