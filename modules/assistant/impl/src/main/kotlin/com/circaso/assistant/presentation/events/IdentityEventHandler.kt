package com.circaso.assistant.presentation.events

import com.circaso.assistant.presentation.services.IdentityEventsService
import com.circaso.identity.api.AccountDeleted
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.decode

/** The assistant's consumer group on `events:identity`: the chat goes with the account. */
class IdentityEventHandler(private val events: IdentityEventsService) {
    fun subscription() = EventSubscription(
        group = "assistant",
        stream = EventEnvelope.streamOf("identity"),
        handlers = mapOf(
            AccountDeleted.TYPE to EventHandler {
                val result = events.accountDeleted(it.decode<AccountDeleted>())
                if (result is Resource.Error) throw DomainException(result.error, result.cause)
            },
        ),
    )
}
