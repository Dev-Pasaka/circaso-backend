package com.circaso.messaging.presentation.events

import com.circaso.identity.api.DeviceReplaced
import com.circaso.messaging.presentation.services.IdentityEventsService
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.decode

/**
 * Messaging's consumer group on `events:identity`. A handler that fails throws, so the event is retried and finally
 * dead-lettered; each one is safe to run twice. The replacement time is the event's, so messages sent to the old
 * phone before it count as undeliverable however late the event is handled.
 */
class IdentityEventHandler(private val events: IdentityEventsService) {
    fun subscription() =
        EventSubscription(
            group = "messaging",
            stream = EventEnvelope.streamOf("identity"),
            handlers =
                mapOf(
                    DeviceReplaced.TYPE to EventHandler { deviceReplaced(it) },
                ),
        )

    private suspend fun deviceReplaced(envelope: EventEnvelope) {
        val event = envelope.decode<DeviceReplaced>()
        orThrow(events.deviceReplaced(event.userId, event.oldDeviceId, envelope.occurredAt))
    }

    private fun orThrow(result: Resource<Unit>) {
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
