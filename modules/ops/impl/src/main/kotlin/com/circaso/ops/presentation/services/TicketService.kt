package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.TicketLinePage
import com.circaso.ops.domain.models.TicketView
import com.circaso.ops.domain.usecases.AssignTicket
import com.circaso.ops.domain.usecases.ChangeTicketStatus
import com.circaso.ops.domain.usecases.GetTicket
import com.circaso.ops.domain.usecases.ListTickets
import com.circaso.ops.domain.usecases.NoteOnTicket
import com.circaso.ops.domain.usecases.ReplyToTicket
import com.circaso.shared.Resource
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketQueueFilter
import com.circaso.support.api.TicketStatus
import java.util.UUID

class TicketService(
    private val listTickets: ListTickets,
    private val getTicket: GetTicket,
    private val replyToTicket: ReplyToTicket,
    private val noteOnTicket: NoteOnTicket,
    private val assignTicket: AssignTicket,
    private val changeTicketStatus: ChangeTicketStatus,
) {
    suspend fun list(
        filter: TicketQueueFilter,
        cursor: String?,
        limit: Int,
    ): Resource<TicketLinePage> = listTickets(filter, cursor, limit)

    suspend fun get(ticketId: TicketId): Resource<TicketView> = getTicket(ticketId)

    suspend fun reply(
        actor: Actor,
        ticketId: TicketId,
        body: String,
    ): Resource<TicketView> = replyToTicket(actor, ticketId, body)

    suspend fun note(
        actor: Actor,
        ticketId: TicketId,
        body: String,
    ): Resource<TicketView> = noteOnTicket(actor, ticketId, body)

    suspend fun assign(
        actor: Actor,
        ticketId: TicketId,
        assigneeId: UUID?,
    ): Resource<TicketView> = assignTicket(actor, ticketId, assigneeId)

    suspend fun status(
        actor: Actor,
        ticketId: TicketId,
        status: TicketStatus,
    ): Resource<TicketView> = changeTicketStatus(actor, ticketId, status)
}
