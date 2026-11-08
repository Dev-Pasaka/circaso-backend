package com.circaso.support.domain.support

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.TicketAuthor
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketMessage
import com.circaso.support.api.TicketStatus
import com.circaso.support.domain.errors.SupportError
import com.circaso.support.domain.models.Ticket
import com.circaso.support.domain.models.TicketThread
import com.circaso.support.domain.repositories.MessageRepository
import com.circaso.support.domain.repositories.TicketRepository
import java.util.UUID

/**
 * Why a conditional ticket update wrote nothing: there is no such ticket (or, for a user, it isn't theirs: the two
 * look the same), or it is closed. Read inside the same transaction as the update that failed.
 */
suspend fun TicketRepository.refusal(ticketId: TicketId, userId: UserId? = null): Resource.Error {
    val ticket = find(ticketId)?.takeIf { userId == null || it.userId == userId }
    return if (ticket?.status == TicketStatus.CLOSED) {
        Resource.Error(SupportError.TicketClosed)
    } else {
        Resource.Error(SupportError.TicketNotFound)
    }
}

/** [ticket] with its messages, oldest first; internal notes only for staff ([withNotes]). */
suspend fun MessageRepository.thread(ticket: Ticket, withNotes: Boolean): TicketThread =
    TicketThread(ticket, of(ticket.ticketId, withNotes))

/** A message written now by [author], with an ordered id; [staffId] for staff only. */
fun message(
    ids: OrderedIds,
    author: TicketAuthor,
    body: String,
    staffId: UUID? = null,
    isInternal: Boolean = false,
): TicketMessage {
    val messageId = ids.next()
    return TicketMessage(messageId, author, staffId, body, isInternal, messageId.madeAt())
}
