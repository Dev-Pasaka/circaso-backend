package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
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
import com.circaso.support.domain.support.thread
import java.util.UUID

/**
 * An internal note (`SupportDesk.note`): staff only. It moves neither the status nor `last_message_at`, and sends
 * nothing, so the user can't tell one was written. Allowed on a closed ticket: the history is staff's to annotate.
 */
class AddStaffNote(
    private val tickets: TicketRepository,
    private val messages: MessageRepository,
    private val transactor: Transactor,
    private val ids: OrderedIds,
) {
    suspend operator fun invoke(
        ticketId: TicketId,
        staffId: UUID,
        body: String,
    ): Resource<TicketThread> {
        val text = TicketTexts.body(body) ?: return Resource.error(SupportError.BodyInvalid)
        val note = message(ids, TicketAuthor.STAFF, text, staffId = staffId, isInternal = true)
        return transactor.attempt("tickets.staff-note") {
            val ticket =
                tickets.noted(ticketId, note.createdAt) ?: return@attempt Resource.error(SupportError.TicketNotFound)
            messages.insert(ticketId, note)
            Resource.success(messages.thread(ticket, withNotes = true))
        }
    }
}
