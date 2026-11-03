package com.circaso.chama.presentation.events

import com.circaso.chama.domain.models.WalletTransfer
import com.circaso.chama.presentation.services.ChamaEventsService
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import com.circaso.shared.decode
import com.circaso.wallet.api.TransferConfirmed

/**
 * Chama's consumer group on `events:wallet`: a confirmed transfer may be a contribution into a group's wallet, or the
 * payment of an approved request from it. Safe to run twice (a contribution is recorded once per transfer; a request moves once).
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
        val transfer =
            WalletTransfer(
                transferId = event.transferId,
                senderId = event.senderId,
                amountMinor = minor,
                chatId = event.chatId,
                fromGroupId = event.fromGroupId,
                toGroupId = event.toGroupId,
                payoutRequestId = event.payoutRequestId?.let { Uuid7.parse(it) },
            )
        val result = events.transferConfirmed(transfer)
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
