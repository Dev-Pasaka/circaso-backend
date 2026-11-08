package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.domain.models.Ticket
import com.circaso.support.domain.repositories.TicketRepository

/** A user's tickets for staff (`SupportDesk.ofUser`, the user page of the dashboard): newest first, at most [limit]. */
class ListUserTickets(private val tickets: TicketRepository) {
    suspend operator fun invoke(userId: UserId, limit: Int): Resource<List<Ticket>> =
        Resource.success(tickets.listOfUser(userId, null, limit))
}
