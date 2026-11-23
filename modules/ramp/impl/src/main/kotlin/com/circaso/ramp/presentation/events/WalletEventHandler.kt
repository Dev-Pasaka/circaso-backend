package com.circaso.ramp.presentation.events

import com.circaso.ramp.presentation.services.RampEventsService
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.WalletNetwork
import com.circaso.shared.decode
import com.circaso.wallet.api.TransferConfirmed
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The ramp's consumer group on `events:wallet` (decision 2026-11-23, the off-ramp): a confirmed transfer that names a
 * ramp order funds a sale, which the ramp then checks with the custodian itself. Every other transfer is acknowledged
 * and ignored. Safe to run twice: the transfer is recorded on the order once and every step is a guard. A failed
 * transfer moved nothing, so it needs nothing: the sale waits for another or expires.
 */
class WalletEventHandler(private val events: RampEventsService) {
    fun subscription() =
        EventSubscription(
            group = "ramp",
            stream = EventEnvelope.streamOf("wallet"),
            handlers = mapOf(TransferConfirmed.TYPE to EventHandler { transferConfirmed(it) }),
        )

    private suspend fun transferConfirmed(envelope: EventEnvelope) {
        val event = envelope.decode<TransferConfirmed>()
        val order = event.rampOrderId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        val result =
            withContext(WalletNetwork(Network.ofChain(event.blockchain))) {
                events.saleTransfer(order, event.transferId.value)
            }
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
