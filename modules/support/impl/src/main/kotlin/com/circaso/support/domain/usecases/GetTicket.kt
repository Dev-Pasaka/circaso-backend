package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.support.api.TicketId
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.TicketThread
import com.circaso.support.domain.repositories.MessageRepository
import com.circaso.support.domain.repositories.TicketRepository
import com.circaso.support.domain.support.thread

/** A whole ticket for staff (`SupportDesk.ticket`): every message, internal notes included, oldest first. */
class GetTicket(private val tickets: TicketRepository, private val messages: MessageRepository) {
    suspend operator fun invoke(ticketId: TicketId): Resource<TicketThread> {
        val ticket = tickets.find(ticketId) ?: return Resource.error(SupportError.TicketNotFound)
        return Resource.success(messages.thread(ticket, withNotes = true))
    }
}
