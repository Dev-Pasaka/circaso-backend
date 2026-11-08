package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.support.api.TicketId
import com.circaso.support.api.TicketQueueFilter
import com.circaso.support.domain.models.TicketsPage
import com.circaso.support.domain.repositories.TicketRepository

/**
 * The staff queue (`SupportDesk.queue`): tickets matching [filter], an open status oldest first (the longest wait on
 * top), any other newest first, a page at a time by keyset on the id.
 */
class ListTicketQueue(private val tickets: TicketRepository) {
    suspend operator fun invoke(
        filter: TicketQueueFilter,
        after: TicketId?,
        limit: Int,
    ): Resource<TicketsPage> {
        val page = tickets.queue(filter, after, limit + 1)
        val shown = page.take(limit)
        return Resource.success(TicketsPage(shown, if (page.size > limit) shown.last().ticketId else null))
    }
}
