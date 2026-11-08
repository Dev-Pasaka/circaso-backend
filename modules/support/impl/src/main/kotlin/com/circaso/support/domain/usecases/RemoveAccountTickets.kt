package com.circaso.support.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.support.domain.repositories.TicketRepository

/**
 * A deleted account's tickets go with it, messages and notes by the foreign key (identity's `AccountDeleted`, decision
 * 2026-11-08); the ops audit log keeps what staff did. Safe to run twice.
 */
class RemoveAccountTickets(private val tickets: TicketRepository) {
    suspend operator fun invoke(userId: UserId): Resource<Unit> {
        tickets.deleteOfUser(userId)
        return Resource.success(Unit)
    }
}
