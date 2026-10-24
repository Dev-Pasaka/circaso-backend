package com.circaso.users.presentation.events

import com.circaso.identity.api.AccountActivated
import com.circaso.identity.api.AccountDeleted
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.identity.api.PhoneChanged
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.decode
import com.circaso.users.presentation.services.IdentityEventsService

/**
 * Users' consumer group on `events:identity`. A handler that fails throws, so the event is retried and finally
 * dead-lettered; every one is safe to run twice.
 */
class IdentityEventHandler(private val events: IdentityEventsService) {
    fun subscription() =
        EventSubscription(
            group = "users",
            stream = EventEnvelope.streamOf("identity"),
            handlers =
                mapOf(
                    AccountActivated.TYPE to EventHandler { orThrow(events.accountActivated(it.decode())) },
                    PhoneChanged.TYPE to EventHandler { orThrow(events.phoneChanged(it.decode())) },
                    AccountStatusChanged.TYPE to EventHandler { orThrow(events.statusChanged(it.decode())) },
                    AccountDeleted.TYPE to EventHandler { orThrow(events.accountDeleted(it.decode())) },
                ),
        )

    private fun orThrow(result: Resource<Unit>) {
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
