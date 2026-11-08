package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.support.api.TicketCounts
import com.circaso.support.domain.repositories.TicketRepository

/** The dashboard's tile: open, waiting on the user, and open with nobody on it. */
class CountTickets(private val tickets: TicketRepository) {
    suspend operator fun invoke(): Resource<TicketCounts> = Resource.success(tickets.counts())
}
