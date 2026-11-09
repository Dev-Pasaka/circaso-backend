package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CustodyTransaction
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
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
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletOutbox

/**
 * Circle's `transactions.outbound`. A transfer prepared here is found by `refId`: its state, its `OUT` row and the
 * event for a final state (`TransferConfirmed`, `TransferFailed`; `TransferSubmitted` when a payment into a group's wallet
 * is on its way) are written in one transaction, and only the update
 * that moves it publishes and sends `transfer.updated`. Its fee ledger row follows it
 * (collected, or void).
 */
class OutgoingUpdates(
    private val transfers: TransferRepository,
    private val transactions: TransactionRepository,
    private val transactor: Transactor,
    private val outbox: WalletOutbox,
    private val bus: EventBus,
    private val events: TransferEvents,
    private val frames: FrameSender,
    private val fees: FeeSettlement,
    private val warnings: WalletWarnings,
    private val ids: OrderedIds,
) {
    /**
     * [UpdateOutcome.NOT_OURS] when the update isn't about a transfer prepared here (no or unknown `refId`). An update
     * whose wallet isn't the transfer's sending wallet is not applied: Circle's `refId` must never move another
     * wallet's transfer. [UpdateOutcome.CHANGED] only when the transfer moved.
     */
    suspend fun applyToTransfer(
        update: CustodyTransaction,
        next: TransferState,
        change: TransferChange,
    ): UpdateOutcome {
        val transfer = TransferId.parse(update.refId)?.let { transfers.find(it) } ?: return UpdateOutcome.NOT_OURS
        if (update.walletId != transfer.senderWalletId) {
            warnings.refIdOfAnotherWallet(transfer.transferId.toString(), update.walletId)
            return UpdateOutcome.UNCHANGED
        }
        val moved = move(transfer, next, change) ?: return UpdateOutcome.UNCHANGED
        announce(moved)
        return UpdateOutcome.CHANGED
    }

    /**
     * `ExpireUnsignedTransfers`: a transfer still awaiting its signature when the signing window closed is `EXPIRED`,
     * with its `OUT` row, through the same guard; its fee is void and the sender is told, as
     * for any other move. False if it had moved on already (Circle's update came first). No event: only a final state
     * from Circle has one.
     */
    suspend fun expire(transfer: Transfer, change: TransferChange): Boolean {
        val id = transfer.transferId
        val moved =
            transactor.run("transfers.expire") {
                transfers.move(id, TransferState.EXPIRED, change)?.also {
                    transactions.moveByTransfer(id, TransferState.EXPIRED, change)
                    fees.follow(it, TransferState.EXPIRED, change)
                }
            } ?: return false
        announce(Moved(moved, event = null))
        return true
    }

    /** After the commit: the event, then `transfer.updated` to the sender. */
    private suspend fun announce(moved: Moved) {
        moved.event?.let { bus.publish(it) }
        val transfer = moved.transfer
        frames.send(transfer.senderId, transfer.toFrame())
    }

    /** What one update did to a transfer: the transfer now and its event. */
    private class Moved(val transfer: Transfer, val event: EventEnvelope?)

    /**
     * The transfer, its `OUT` row, its fee ledger row and the event for a final state, in one transaction. The limits
     * need nothing here: they count transfers by state (phase 2F), so a failed or expired one simply stops counting,
     * and an expired one the chain says was signed after all counts again.
     */
    private suspend fun move(
        transfer: Transfer,
        next: TransferState,
        change: TransferChange,
    ): Moved? {
        val event = eventFor(transfer, next, change)
        val id = transfer.transferId
        return transactor.run("transfers.apply-update") {
            transfers.move(id, next, change)?.let { moved ->
                transactions.moveByTransfer(id, next, change)
                fees.follow(moved, next, change)
                outbox.write(listOfNotNull(event))
                Moved(moved, event)
            }
        }
    }

    /**
     * An outgoing transaction of one of our wallets that wasn't prepared here (no `refId` of ours): recorded in the
     * history so it isn't missing there; nothing else to tell. True when the history changed: a new row, or the row
     * moved on.
     */
    suspend fun applyUntracked(
        wallet: ChainWallet,
        update: CustodyTransaction,
        next: TransferState,
        change: TransferChange,
    ): Boolean {
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
        return transactor.run("transactions.apply-untracked") {
            transactions.insert(row) ||
                transactions.moveByCircle(update.circleTxId, wallet.userId, next, change) != null
        }
    }

    private fun eventFor(
        transfer: Transfer,
        next: TransferState,
        change: TransferChange,
    ): EventEnvelope? =
        when (next) {
            TransferState.CONFIRMED -> events.confirmed(transfer)

            TransferState.FAILED -> events.failed(transfer, change.failureReason)

            // A payment into a group's wallet is told to its members as soon as it is on its way (2026-11-09).
            TransferState.SUBMITTED -> events.submitted(transfer)

            else -> null
        }

    private fun Transfer.toFrame() =
        WalletFrame.TransferUpdated(transferId, state.name, blockchain, amount, txHash, failureReason)
}

/** What a Circle update did to one of our transfers: not ours, ours but nothing moved (late or repeated), or moved. */
enum class UpdateOutcome { NOT_OURS, UNCHANGED, CHANGED }
