package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CustodyTransaction
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.envelope
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.api.TransferFailed
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.ports.FrameSender
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.ports.WalletFrame
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletOutbox

/**
 * Circle's `transactions.outbound`. A transfer prepared here is found by `refId`: its state, its `OUT` row and the
 * event for a final state (`TransferConfirmed`, `TransferFailed`) are written in one transaction, and only the update
 * that moves it publishes and sends `transfer.updated`. A failed transfer gives its amount back to the daily limit.
 */
class OutgoingUpdates(
    private val transfers: TransferRepository,
    private val transactions: TransactionRepository,
    private val transactor: Transactor,
    private val outbox: WalletOutbox,
    private val bus: EventBus,
    private val frames: FrameSender,
    private val rules: SenderRules,
    private val ids: OrderedIds,
) {
    /** False when the update isn't about a transfer prepared here (no or unknown `refId`). */
    suspend fun applyToTransfer(
        update: CustodyTransaction,
        next: TransferState,
        change: TransferChange,
    ): Boolean {
        val transfer = TransferId.parse(update.refId)?.let { transfers.find(it) } ?: return false
        val event = eventFor(transfer, next, change)
        val moved =
            transactor.run("transfers.apply-update") {
                transfers.move(transfer.transferId, next, change)?.also {
                    transactions.moveByTransfer(transfer.transferId, next, change)
                    outbox.write(listOfNotNull(event))
                }
            } ?: return true

        event?.let { bus.publish(it) }
        if (next == TransferState.FAILED) rules.release(moved.senderId, moved.createdAt, moved.amount)
        frames.send(moved.senderId, moved.toFrame())
        return true
    }

    /**
     * An outgoing transaction of one of our wallets that wasn't prepared here (no `refId` of ours): recorded in the
     * history so it isn't missing there; nothing else to tell.
     */
    suspend fun applyUntracked(
        wallet: ChainWallet,
        update: CustodyTransaction,
        next: TransferState,
        change: TransferChange,
    ) {
        val row =
            WalletTransaction(
                id = ids.next(),
                userId = wallet.userId,
                direction = Direction.OUT,
                state = next,
                amount = update.amount,
                blockchain = update.blockchain,
                counterpartyId = null,
                counterpartyAddress = Addresses.normalise(update.destinationAddress),
                transferId = null,
                circleTxId = update.circleTxId,
                txHash = change.txHash,
                chatId = null,
                origin = TransferOrigin.APP,
                occurredAt = change.at,
            )
        transactor.run("transactions.apply-untracked") {
            if (!transactions.insert(row)) transactions.moveByCircle(update.circleTxId, wallet.userId, next, change)
        }
    }

    private fun eventFor(
        transfer: Transfer,
        next: TransferState,
        change: TransferChange,
    ): EventEnvelope? =
        when (next) {
            TransferState.CONFIRMED -> {
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
            }

            TransferState.FAILED -> {
                bus.envelope(TransferFailed(transfer.transferId, transfer.senderId, change.failureReason ?: "FAILED"))
            }

            else -> {
                null
            }
        }

    private fun Transfer.toFrame() =
        WalletFrame.TransferUpdated(transferId, state.name, blockchain, amount, txHash, failureReason)
}
