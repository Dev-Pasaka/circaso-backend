package com.circaso.support.presentation.events

import com.circaso.identity.api.AccountDeleted
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.decode
import com.circaso.support.presentation.services.IdentityEventsService

/**
 * Support's consumer group on `events:identity`. Only `AccountDeleted` (30 days after the user asked) takes their
 * tickets, not the earlier `AccountStatusChanged` to `DELETED`: while the account can still be restored, support may be
 * the very thing helping restore it (decision 2026-11-08). A handler that fails throws, so the event is retried and
 * finally dead-lettered; deleting twice is harmless.
 */
class IdentityEventHandler(private val events: IdentityEventsService) {
    fun subscription() =
        EventSubscription(
            group = "support",
            stream = EventEnvelope.streamOf("identity"),
            handlers =
                mapOf(
                    AccountDeleted.TYPE to
                        EventHandler { orThrow(events.accountDeleted(it.decode<AccountDeleted>().userId)) },
                ),
        )

    private fun orThrow(result: Resource<Unit>) {
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
