package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId

// Building the envelope inside is fine (it is pure, and the outbox row needs it); publishing waits for the commit.
class ConfirmPhone(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val events: EventBus,
) {
    suspend operator fun invoke(userId: UserId): Resource<Unit> {
        val envelope =
            transactor.attempt("phone.confirm") {
                accounts.find(userId) ?: return@attempt Resource.error(PhoneNotFound)
                Resource.success(events.envelope(PhoneVerified(userId), PhoneVerified.serializer()))
            }
        if (envelope is Resource.Success) events.publish(envelope.data)
        return Resource.success(Unit)
    }
}
