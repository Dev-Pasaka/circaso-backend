package com.circaso.identity.presentation.events

import com.circaso.identity.presentation.services.RampEventsService
import com.circaso.ramp.api.UserVerificationChanged
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.decode

/**
 * Identity's consumer group on `events:ramp`. A handler that fails throws, so the event is retried and finally
 * dead-lettered; it is safe to run twice (the event's `changedAt` orders them).
 */
class RampEventHandler(private val events: RampEventsService) {
    fun subscription() =
        EventSubscription(
            group = "identity",
            stream = EventEnvelope.streamOf("ramp"),
            handlers =
                mapOf(
                    UserVerificationChanged.TYPE to
                        EventHandler { orThrow(events.userVerificationChanged(it.decode())) },
                ),
        )

    private fun orThrow(result: Resource<Unit>) {
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
