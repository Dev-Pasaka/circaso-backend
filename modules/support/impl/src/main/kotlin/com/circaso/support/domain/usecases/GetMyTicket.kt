package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.TicketId
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.TicketThread
import com.circaso.support.domain.repositories.MessageRepository
import com.circaso.support.domain.repositories.TicketRepository
import com.circaso.support.domain.support.thread

/** SU3: one of the caller's tickets with its messages, never an internal note. Someone else's is not found. */
class GetMyTicket(private val tickets: TicketRepository, private val messages: MessageRepository) {
    suspend operator fun invoke(userId: UserId, ticketId: TicketId): Resource<TicketThread> {
        val ticket =
            tickets.find(ticketId)?.takeIf { it.userId == userId }
                ?: return Resource.error(SupportError.TicketNotFound)
        return Resource.success(messages.thread(ticket, withNotes = false))
    }
}
