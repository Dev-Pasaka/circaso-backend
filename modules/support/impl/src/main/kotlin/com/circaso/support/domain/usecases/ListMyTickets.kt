package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.api.TicketId
import com.circaso.support.domain.models.TicketsPage
import com.circaso.support.domain.repositories.TicketRepository

/** SU2: the caller's tickets, newest first, a page at a time (one more is read to know whether there is a next). */
class ListMyTickets(private val tickets: TicketRepository) {
    suspend operator fun invoke(
        userId: UserId,
        after: TicketId?,
        limit: Int,
    ): Resource<TicketsPage> {
        val page = tickets.listOfUser(userId, after, limit + 1)
        val shown = page.take(limit)
        return Resource.success(TicketsPage(shown, if (page.size > limit) shown.last().ticketId else null))
    }
}
