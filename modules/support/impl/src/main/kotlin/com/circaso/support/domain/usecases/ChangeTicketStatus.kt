package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketNumbers
import com.circaso.support.api.TicketStatus
import com.circaso.support.api.TicketUpdatedFrame
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.TicketThread
import com.circaso.support.domain.ports.FrameSender
import com.circaso.support.domain.repositories.MessageRepository
import com.circaso.support.domain.repositories.TicketRepository
import com.circaso.support.domain.support.thread
import java.time.Clock

/**
 * Staff move a ticket (`SupportDesk.changeStatus`): resolve, close, or open again (a closed ticket too: staff may
 * reopen what a user can't). The user hears `support/ticket.updated` only when the status really changed; setting
 * the status it has answers the ticket as it is. `PENDING_USER` is refused (`support.status_invalid`): only a staff
 * reply sets it, so "waiting on the user" always means there is a reply for them to read.
 */
class ChangeTicketStatus(
    private val tickets: TicketRepository,
    private val messages: MessageRepository,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(ticketId: TicketId, status: TicketStatus): Resource<TicketThread> {
        if (status == TicketStatus.PENDING_USER) return Resource.error(SupportError.StatusInvalid)
        val changed = tickets.changeStatus(ticketId, status, clock.instant())
        if (changed == null) {
            val ticket = tickets.find(ticketId) ?: return Resource.error(SupportError.TicketNotFound)
            return Resource.success(messages.thread(ticket, withNotes = true))
        }
        frames.send(changed.userId, TicketUpdatedFrame(changed.ticketId, TicketNumbers.format(changed.number), status))
        return Resource.success(messages.thread(changed, withNotes = true))
    }
}
