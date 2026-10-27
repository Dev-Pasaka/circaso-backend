package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** The rows go in one transaction; the event is published after the commit, outside it. */
class LinkProvider(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val events: EventBus,
) {
    suspend operator fun invoke(userId: UserId, provider: String, subject: String): Resource<Unit> {
        val linked =
            transactor.run {
                accounts.find(userId) ?: return@run false
                providers.link(userId, provider, subject)
                true
            }
        if (linked) events.publish(events.envelope(ProviderLinked(userId), ProviderLinked.serializer()))
        return Resource.success(Unit)
    }
}
