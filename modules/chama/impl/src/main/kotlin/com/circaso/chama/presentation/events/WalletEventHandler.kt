package com.circaso.chama.presentation.events

import com.circaso.chama.presentation.services.ChamaEventsService
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.decode
import com.circaso.wallet.api.TransferConfirmed

/**
 * Chama's consumer group on `events:wallet`: a confirmed transfer may be a contribution to a goal, or the payment of
 * an approved request. Safe to run twice (a contribution is recorded once per transfer; a request moves once).
 */
class WalletEventHandler(private val events: ChamaEventsService) {
    fun subscription() =
        EventSubscription(
            group = "chama",
            stream = EventEnvelope.streamOf("wallet"),
            handlers = mapOf(TransferConfirmed.TYPE to EventHandler { transferConfirmed(it) }),
        )

    private suspend fun transferConfirmed(envelope: EventEnvelope) {
        val event = envelope.decode<TransferConfirmed>()
        val minor = event.amount.amountMinor.toLongOrNull() ?: return
        val result = events.transferConfirmed(event.transferId, event.senderId, event.recipientId, minor, event.chatId)
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
