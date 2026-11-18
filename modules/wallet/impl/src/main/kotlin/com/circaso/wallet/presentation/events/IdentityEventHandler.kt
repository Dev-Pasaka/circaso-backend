package com.circaso.wallet.presentation.events

import com.circaso.identity.api.PinVaultOpened
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.WalletNetwork
import com.circaso.shared.decode
import com.circaso.wallet.presentation.services.IdentityEventsService
import kotlinx.coroutines.withContext

/**
 * The wallet's consumer group on `events:identity`. A handler that fails throws, so the event is retried and finally
 * dead-lettered; each one is safe to run twice.
 */
class IdentityEventHandler(private val events: IdentityEventsService) {
    fun subscription() =
        EventSubscription(
            group = "wallet",
            stream = EventEnvelope.streamOf("identity"),
            handlers =
                mapOf(
                    // the hold counts from the opening, so a retried or late delivery ends it at the same time
                    PinVaultOpened.TYPE to
                        EventHandler {
                            val opened = it.decode<PinVaultOpened>()
                            // the vault holds one network's PIN, so payments pause on that network only
                            withContext(WalletNetwork(opened.network)) {
                                orThrow(events.vaultOpened(opened.userId, it.occurredAt))
                            }
                        },
                ),
        )

    private fun orThrow(result: Resource<Unit>) {
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
