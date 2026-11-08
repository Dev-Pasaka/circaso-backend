package com.circaso.share.presentation.events

import com.circaso.identity.api.AccountDeleted
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.share.presentation.services.IdentityEventsService
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.decode

/**
 * Share's consumer group on `events:identity`. A deleted account's links stop opening at once: at
 * `AccountStatusChanged` to `DELETED` every link it made is deleted, snapshot and row, and again at `AccountDeleted`
 * 30 days later in case one was made in between. A handler that fails throws, so the event is retried and finally
 * dead-lettered; both are safe to run twice.
 */
class IdentityEventHandler(private val events: IdentityEventsService) {
    fun subscription() =
        EventSubscription(
            group = "share",
            stream = EventEnvelope.streamOf("identity"),
            handlers =
                mapOf(
                    AccountStatusChanged.TYPE to
                        EventHandler {
                            val event = it.decode<AccountStatusChanged>()
                            if (event.status == AccountStatus.DELETED) orThrow(events.accountDeleted(event.userId))
                        },
                    AccountDeleted.TYPE to
                        EventHandler { orThrow(events.accountDeleted(it.decode<AccountDeleted>().userId)) },
                ),
        )

    private fun orThrow(result: Resource<Unit>) {
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
