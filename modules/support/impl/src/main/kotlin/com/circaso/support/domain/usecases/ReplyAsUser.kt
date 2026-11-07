package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.TicketAuthor
import com.circaso.support.api.TicketId
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.TicketThread
import com.circaso.support.domain.ports.Transactor
import com.circaso.support.domain.repositories.MessageRepository
import com.circaso.support.domain.repositories.TicketRepository
import com.circaso.support.domain.support.OrderedIds
import com.circaso.support.domain.support.TicketTexts
import com.circaso.support.domain.support.message
import com.circaso.support.domain.support.refusal
import com.circaso.support.domain.support.thread

/**
 * SU4: the user writes again. A resolved ticket, or one waiting on them, opens again and waits on staff (decision
 * 2026-11-08); a closed one refuses (`support.ticket_closed`), and the app offers a new ticket instead. The ticket row
 * is updated first (it is the parent, so it is locked first, on every path), then the message is written, in one
 * transaction. Nothing is sent to the user: it is their own reply.
 */
class ReplyAsUser(
    private val tickets: TicketRepository,
    private val messages: MessageRepository,
    private val transactor: Transactor,
    private val ids: OrderedIds,
) {
    suspend operator fun invoke(
        userId: UserId,
        ticketId: TicketId,
        body: String,
    ): Resource<TicketThread> {
        val text = TicketTexts.body(body) ?: return Resource.error(SupportError.BodyInvalid)
        val reply = message(ids, TicketAuthor.USER, text)
        return transactor.attempt("tickets.user-reply") {
            val ticket =
                tickets.userReplied(ticketId, userId, reply.createdAt)
                    ?: return@attempt tickets.refusal(ticketId, userId)
            messages.insert(ticketId, reply)
            Resource.success(messages.thread(ticket, withNotes = false))
        }
    }
}
