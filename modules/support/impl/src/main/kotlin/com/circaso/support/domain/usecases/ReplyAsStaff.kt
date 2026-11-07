package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.support.api.TicketAuthor
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketNumbers
import com.circaso.support.api.TicketRepliedFrame
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.TicketThread
import com.circaso.support.domain.ports.FrameSender
import com.circaso.support.domain.ports.Transactor
import com.circaso.support.domain.repositories.MessageRepository
import com.circaso.support.domain.repositories.TicketRepository
import com.circaso.support.domain.support.OrderedIds
import com.circaso.support.domain.support.TicketTexts
import com.circaso.support.domain.support.message
import com.circaso.support.domain.support.refusal
import com.circaso.support.domain.support.thread
import java.util.UUID

/**
 * A staff reply (`SupportDesk.reply`): the ticket turns `PENDING_USER`, waiting on the user, and after the commit the
 * user hears `support/ticket.replied` (socket, or a push when offline, with no text from the ticket). A closed ticket
 * refuses, so nobody is told about a reply to something they can no longer answer; staff reopen it first.
 */
class ReplyAsStaff(
    private val tickets: TicketRepository,
    private val messages: MessageRepository,
    private val transactor: Transactor,
    private val frames: FrameSender,
    private val ids: OrderedIds,
) {
    suspend operator fun invoke(
        ticketId: TicketId,
        staffId: UUID,
        body: String,
    ): Resource<TicketThread> {
        val text = TicketTexts.body(body) ?: return Resource.error(SupportError.BodyInvalid)
        val reply = message(ids, TicketAuthor.STAFF, text, staffId = staffId)
        val result =
            transactor.attempt("tickets.staff-reply") {
                val ticket =
                    tickets.staffReplied(ticketId, reply.createdAt) ?: return@attempt tickets.refusal(ticketId)
                messages.insert(ticketId, reply)
                Resource.success(messages.thread(ticket, withNotes = true))
            }
        if (result is Resource.Success) {
            val ticket = result.data.ticket
            val frame = TicketRepliedFrame(ticket.ticketId, TicketNumbers.format(ticket.number), ticket.status)
            frames.send(ticket.userId, frame)
        }
        return result
    }
}
