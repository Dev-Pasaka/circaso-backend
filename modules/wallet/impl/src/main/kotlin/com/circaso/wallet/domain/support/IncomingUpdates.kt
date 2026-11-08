package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CustodyTransaction
import com.circaso.users.api.Profiles
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
import com.circaso.wallet.domain.repositories.WalletRepository
import java.time.Duration

/**
 * Circle's `transactions.inbound` for one of our wallets: the recipient's `IN` row, inserted once per Circle
 * transaction and user (a repeated notification adds nothing), then moved by the transition guard. When the money is
 * on chain, and only by the update that got it there, the recipient gets `payment.received`, naming the sender when
 * the sending address is a Circaso wallet.
 *
 * Money from a Circaso wallet is linked to the transfer that paid it (2026-11-05), so the recipient's phone can show
 * the chat's Received card from the server's record even when it can't open the end-to-end payment card: the row
 * gets `paid_by_transfer_id` and the transfer's chat, and the frame carries both. See [payer] for the matching rule.
 */
class IncomingUpdates(
    private val wallets: WalletRepository,
    private val transactions: TransactionRepository,
    private val transactor: Transactor,
    private val frames: FrameSender,
    private val profiles: Profiles,
    private val ids: OrderedIds,
    private val transfers: TransferRepository,
) {
    /** True when the history changed: a new row, or the row moved on (a repeated notification changes nothing). */
    suspend fun apply(
        wallet: ChainWallet,
        update: CustodyTransaction,
        next: TransferState,
        change: TransferChange,
    ): Boolean {
        val sender = wallets.walletAt(Addresses.normalise(update.sourceAddress), update.blockchain)
        val row = rowOf(wallet, update, next, change, sender)
        var changed = false
        val received =
            transactor.run("transactions.apply-inbound") {
                val landed =
                    if (transactions.insert(row)) {
                        row
                    } else {
                        transactions.moveByCircle(update.circleTxId, wallet.userId, next, change)
                    }
                changed = landed != null
                landed
                    ?.takeIf { it.state == TransferState.CONFIRMED }
                    ?.let { confirmed -> if (sender == null) confirmed else linked(confirmed, sender, change) }
            } ?: return changed

        val name = received.counterpartyId?.let { profiles.get(setOf(it))[it]?.displayName }
        frames.send(
            wallet.userId,
            WalletFrame.PaymentReceived(
                received.id,
                received.blockchain,
                received.amount,
                received.counterpartyId,
                name,
                transferId = received.paidByTransferId,
                chatId = received.chatId,
            ),
        )
        return true
    }

    /** [row] linked to the transfer from [sender]'s wallet that paid it, when one is certain; else [row] as it is. */
    private suspend fun linked(
        row: WalletTransaction,
        sender: ChainWallet,
        change: TransferChange,
    ): WalletTransaction {
        if (row.paidByTransferId != null) return row
        val candidates =
            transfers
                .paidTo(
                    recipientId = row.userId,
                    senderWalletId = sender.circleWalletId,
                    blockchain = row.blockchain,
                    amount = row.amount,
                    createdAfter = change.at.minus(LOOKBACK),
                    limit = CANDIDATES,
                ).filter { it.amount == row.amount }
        val paid = payer(candidates, row.txHash ?: change.txHash) ?: return row
        return transactions.linkPayment(row.id, paid.transferId, paid.chatId) ?: row
    }

    /**
     * The transfer that paid the row, or null when it isn't certain; never a guess between two. The one whose
     * `txHash` is the inbound hash (any case) wins. Otherwise the candidates that can't be told apart by hash (the
     * transfer has none yet, or the inbound update carries none; a different hash rules a transfer out) must be
     * exactly one, and it must not have paid another `IN` row already.
     */
    private suspend fun payer(candidates: List<Transfer>, txHash: String?): Transfer? {
        val hash = txHash?.takeIf { it.isNotBlank() }
        if (hash != null) {
            val same = candidates.filter { it.txHash.equals(hash, ignoreCase = true) }
            if (same.isNotEmpty()) return same.singleOrNull()
        }
        val open = candidates.filter { hash == null || it.txHash.isNullOrBlank() }
        val only = open.singleOrNull() ?: return null
        return only.takeUnless { transactions.isPaidBy(it.transferId) }
    }

    private fun rowOf(
        wallet: ChainWallet,
        update: CustodyTransaction,
        next: TransferState,
        change: TransferChange,
        sender: ChainWallet?,
    ) = WalletTransaction(
        id = ids.next(),
        userId = wallet.userId,
        direction = Direction.IN,
        state = next,
        amount = update.amount,
        blockchain = update.blockchain,
        counterpartyId = sender?.userId,
        counterpartyAddress = Addresses.normalise(update.sourceAddress),
        transferId = null,
        circleTxId = update.circleTxId,
        txHash = change.txHash,
        chatId = null,
        origin = TransferOrigin.APP,
        occurredAt = change.at,
    )

    private companion object {
        /** How far back a paying transfer may have been prepared. */
        val LOOKBACK: Duration = Duration.ofHours(24)

        /** Enough to see that two transfers match; more is never needed. */
        const val CANDIDATES = 5
    }
}
