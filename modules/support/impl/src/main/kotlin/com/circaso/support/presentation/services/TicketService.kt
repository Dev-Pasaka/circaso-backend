package com.circaso.support.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.NewTicket
import com.circaso.support.api.TicketId
import com.circaso.support.domain.models.Ticket
import com.circaso.support.domain.models.TicketThread
import com.circaso.support.domain.models.TicketsPage
import com.circaso.support.domain.usecases.GetMyTicket
import com.circaso.support.domain.usecases.ListMyTickets
import com.circaso.support.domain.usecases.OpenTicket
import com.circaso.support.domain.usecases.ReplyAsUser

/** The user's side of support: SU1 open (also Asha's tool), SU2 list, SU3 read, SU4 reply. */
class TicketService(
    private val openTicket: OpenTicket,
    private val listMyTickets: ListMyTickets,
    private val getMyTicket: GetMyTicket,
    private val replyAsUser: ReplyAsUser,
) {
    suspend fun open(ticket: NewTicket): Resource<Ticket> = openTicket(ticket)

    suspend fun list(
        userId: UserId,
        after: TicketId?,
        limit: Int,
    ): Resource<TicketsPage> = listMyTickets(userId, after, limit)

    suspend fun get(userId: UserId, ticketId: TicketId): Resource<TicketThread> = getMyTicket(userId, ticketId)

    suspend fun reply(
        userId: UserId,
        ticketId: TicketId,
        body: String,
    ): Resource<TicketThread> = replyAsUser(userId, ticketId, body)
}
