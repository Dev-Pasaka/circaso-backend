package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.support.api.TicketId
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.TicketThread
import com.circaso.support.domain.repositories.MessageRepository
import com.circaso.support.domain.repositories.TicketRepository
import com.circaso.support.domain.support.thread
import java.time.Clock
import java.util.UUID

/**
 * Assigns a ticket (`SupportDesk.assign`), or with null unassigns it. [assigneeId] is a staff id from the ops module,
 * taken as it is: who may be assigned is the ops module's rule. The user hears nothing.
 */
class AssignTicket(
    private val tickets: TicketRepository,
    private val messages: MessageRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(ticketId: TicketId, assigneeId: UUID?): Resource<TicketThread> {
        val ticket =
            tickets.assign(ticketId, assigneeId, clock.instant()) ?: return Resource.error(SupportError.TicketNotFound)
        return Resource.success(messages.thread(ticket, withNotes = true))
    }
}
