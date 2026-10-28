package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CustodyTransaction
import com.circaso.users.api.Profiles
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.ports.FrameSender
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.ports.WalletFrame
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.repositories.WalletRepository

/**
 * Circle's `transactions.inbound` for one of our wallets: the recipient's `IN` row, inserted once per Circle
 * transaction and user (a repeated notification adds nothing), then moved by the transition guard. When the money is
 * on chain, and only by the update that got it there, the recipient gets `payment.received`, naming the sender when
 * the sending address is a Circaso wallet.
 */
class IncomingUpdates(
    private val wallets: WalletRepository,
    private val transactions: TransactionRepository,
    private val transactor: Transactor,
    private val frames: FrameSender,
    private val profiles: Profiles,
    private val ids: OrderedIds,
) {
    suspend fun apply(
        wallet: ChainWallet,
        update: CustodyTransaction,
        next: TransferState,
        change: TransferChange,
    ) {
        val sender = wallets.walletAt(Addresses.normalise(update.sourceAddress), update.blockchain)
        val row = rowOf(wallet, update, next, change, sender)
        val received =
            transactor.run("transactions.apply-inbound") {
                val landed =
                    if (transactions.insert(row)) {
                        row
                    } else {
                        transactions.moveByCircle(update.circleTxId, wallet.userId, next, change)
                    }
                landed?.takeIf { it.state == TransferState.CONFIRMED }
            } ?: return

        val name = received.counterpartyId?.let { profiles.get(setOf(it))[it]?.displayName }
        frames.send(
            wallet.userId,
            WalletFrame.PaymentReceived(
                received.id,
                received.blockchain,
                received.amount,
                received.counterpartyId,
                name,
            ),
        )
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
}
