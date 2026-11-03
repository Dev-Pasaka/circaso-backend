package com.circaso.status.presentation.events

import com.circaso.identity.api.AccountDeleted
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.decode
import com.circaso.status.presentation.services.IdentityEventsService

/**
 * Status' consumer group on `events:identity`. Deleting an account removes its moments at once (Moments API →
 * Retention): at `AccountStatusChanged` to `DELETED`, the moment deletion is requested, and again at `AccountDeleted`
 * 30 days later, in case anything was left. A handler that fails throws, so the event is retried and finally
 * dead-lettered; both are safe to run twice.
 */
class IdentityEventHandler(private val events: IdentityEventsService) {
    fun subscription() =
        EventSubscription(
            group = "status",
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
