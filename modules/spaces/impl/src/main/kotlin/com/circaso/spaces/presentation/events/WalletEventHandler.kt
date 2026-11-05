package com.circaso.spaces.presentation.events

import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import com.circaso.shared.decode
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.presentation.services.EventsService
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.api.WalletCreated

/**
 * Spaces' consumer group on `events:wallet`: a confirmed membership payment makes the payer a member (or renews them);
 * a space wallet that came to be may put its space live. Both are safe to run twice.
 */
class WalletEventHandler(private val events: EventsService) {
    fun subscription() =
        EventSubscription(
            group = "spaces",
            stream = EventEnvelope.streamOf("wallet"),
            handlers =
                mapOf(
                    TransferConfirmed.TYPE to EventHandler { transferConfirmed(it) },
                    WalletCreated.TYPE to EventHandler { walletCreated(it) },
                ),
        )

    private suspend fun transferConfirmed(envelope: EventEnvelope) {
        val event = envelope.decode<TransferConfirmed>()
        val paymentId = event.spacePaymentId?.let { Uuid7.parse(it) } ?: return
        val minor = event.amount.amountMinor.toLongOrNull() ?: return
        // A membership is paid from the personal wallet, whose holder id is the payer's own.
        orThrow(events.paymentConfirmed(paymentId, event.signerId ?: event.senderId, minor))
    }

    private suspend fun walletCreated(envelope: EventEnvelope) {
        val event = envelope.decode<WalletCreated>()
        val spaceId = event.spaceId?.let { SpaceId.parse(it) } ?: return
        orThrow(events.walletReady(spaceId))
    }

    private fun orThrow(result: Resource<Unit>) {
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
