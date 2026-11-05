package com.circaso.spaces.presentation.events

import com.circaso.identity.api.AccountDeleted
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.decode
import com.circaso.spaces.presentation.services.EventsService

/** Spaces' consumer group on `events:identity`: a deleted account leaves every space; its own spaces are deleted. */
class IdentityEventHandler(private val events: EventsService) {
    fun subscription() =
        EventSubscription(
            group = "spaces",
            stream = EventEnvelope.streamOf("identity"),
            handlers = mapOf(AccountDeleted.TYPE to EventHandler { accountDeleted(it) }),
        )

    private suspend fun accountDeleted(envelope: EventEnvelope) {
        val result = events.accountDeleted(envelope.decode<AccountDeleted>().userId)
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
