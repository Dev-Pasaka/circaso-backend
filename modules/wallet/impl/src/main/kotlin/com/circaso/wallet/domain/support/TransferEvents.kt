package com.circaso.wallet.domain.support

import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.envelope
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.api.TransferFailed
import com.circaso.wallet.domain.models.Transfer

/**
 * The events that end a transfer (Wallet → Events → Published), built before the transaction that writes their outbox
 * row: building an envelope is pure, publishing waits for the commit. Exactly one per transfer, written only by the
 * operation whose transition guard moved the transfer to its final state.
 */
class TransferEvents(private val bus: EventBus) {
    fun confirmed(transfer: Transfer): EventEnvelope =
        bus.envelope(
            TransferConfirmed(
                transferId = transfer.transferId,
                senderId = transfer.senderId,
                recipientId = transfer.recipientId,
                blockchain = transfer.blockchain,
                amount = Amounts.of(transfer.amount),
                origin = transfer.origin.name,
                proposalId = transfer.proposalId?.toString(),
            ),
        )

    fun failed(transfer: Transfer, reason: String?): EventEnvelope =
        bus.envelope(TransferFailed(transfer.transferId, transfer.senderId, reason ?: FAILED_WITHOUT_REASON))

    private companion object {
        const val FAILED_WITHOUT_REASON = "FAILED"
    }
}
