package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.TransferRequest
import com.circaso.shared.Resource
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.PreparedTransfer
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.Destination
import com.circaso.wallet.domain.support.OrderedIds
import com.circaso.wallet.domain.support.Payees
import com.circaso.wallet.domain.support.Sender
import com.circaso.wallet.domain.support.SenderRules
import com.circaso.wallet.domain.support.isRefusal
import com.circaso.wallet.domain.support.orTransferError
import java.time.Clock

/**
 * W4 `POST /wallet/transfers`: checks the rules in order (account `ACTIVE`; wallet active on the transfer's chain; no
 * payment hold; amount within limits; the payee reachable with a wallet on that chain, or the address valid on it;
 * enough on that chain), writes the transfer `AWAITING_SIGNATURE` with the sender's `OUT` row, then asks Circle for
 * the challenge the user signs with the PIN, with `refId` = the transfer id, so Circle's updates lead back to it.
 *
 * A transfer is unique per sender and `Idempotency-Key` for good: a repeated key returns the transfer already
 * prepared, with Circle's same challenge (same Circle key) while it still awaits a signature. The row is claimed before
 * Circle is asked, so two requests with one key never make two transfers. When Circle refuses, the transfer is
 * `FAILED` and its amount goes back to the daily limit; when Circle is down it stays `AWAITING_SIGNATURE`, and the same
 * request later asks again.
 */
class PrepareTransfer(
    private val transfers: TransferRepository,
    private val transactions: TransactionRepository,
    private val wallets: WalletRepository,
    private val transactor: Transactor,
    private val rules: SenderRules,
    private val payees: Payees,
    private val custody: StablecoinCustody,
    private val ids: OrderedIds,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: TransferCommand): Resource<PreparedTransfer> {
        transfers.byKey(command.senderId, command.idempotencyKey)?.let { return resume(it) }

        val sender =
            when (val checked = check(command)) {
                is Resource.Success -> checked.data
                is Resource.Error -> return checked
            }
        val destination =
            when (val resolved = payees.resolve(command.senderId, command.payee, command.blockchain, command.chatId)) {
                is Resource.Success -> resolved.data
                is Resource.Error -> return resolved
            }
        rules.checkBalance(sender, command.amount).let { if (it is Resource.Error) return it }

        val transfer = transferOf(command, sender, destination)
        rules.reserve(command.senderId, transfer.createdAt, command.amount).let { if (it is Resource.Error) return it }
        if (!record(transfer)) {
            rules.release(command.senderId, transfer.createdAt, command.amount)
            return resume(checkNotNull(transfers.byKey(command.senderId, command.idempotencyKey)))
        }
        return challenge(sender, transfer)
    }

    /** The rules about the sender, before the payee is looked at (Wallet → W4's order). */
    private suspend fun check(command: TransferCommand): Resource<Sender> {
        val sender =
            when (val found = rules.sender(command.senderId, command.blockchain)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        return when (val amount = rules.checkAmount(command.senderId, command.blockchain, command.amount)) {
            is Resource.Success -> Resource.success(sender)
            is Resource.Error -> amount
        }
    }

    /** The transfer and the sender's history row, in one transaction; false when the key was already used. */
    private suspend fun record(transfer: Transfer): Boolean =
        transactor.run("transfers.prepare") {
            if (!transfers.insert(transfer)) return@run false
            transactions.insert(outgoingRow(transfer))
            true
        }

    /** A transfer already prepared for this key: its challenge again while it can still be signed, else as it is. */
    private suspend fun resume(transfer: Transfer): Resource<PreparedTransfer> {
        val unsigned = PreparedTransfer(transfer, challengeId = null, sdk = null)
        val signable = transfer.state == TransferState.AWAITING_SIGNATURE && clock.instant() < transfer.expiresAt
        if (!signable) return Resource.success(unsigned)
        val user = wallets.circleUser(transfer.senderId)
        val wallet = wallets.wallet(transfer.senderWalletId)
        if (user == null || wallet == null) return Resource.success(unsigned)
        return challenge(Sender(user, wallet), transfer)
    }

    private suspend fun challenge(sender: Sender, transfer: Transfer): Resource<PreparedTransfer> {
        val request =
            TransferRequest(
                blockchain = transfer.blockchain,
                walletId = transfer.senderWalletId,
                destinationAddress = transfer.destinationAddress,
                amount = transfer.amount,
                idempotencyKey = transfer.idempotencyKey,
                refId = transfer.transferId.toString(),
            )
        return when (val asked = custody.prepareTransfer(sender.user.circleUserId.toString(), request)) {
            is Resource.Success -> Resource.success(PreparedTransfer(transfer, asked.data.challengeId, asked.data.sdk))
            is Resource.Error -> refused(transfer, asked)
        }
    }

    /** Circle refused (the same request can't work later): the transfer fails and its amount goes back. */
    private suspend fun refused(transfer: Transfer, error: Resource.Error): Resource<PreparedTransfer> {
        val answer = error.orTransferError()
        if (!error.isRefusal) return answer
        val change = TransferChange(failureReason = (answer as Resource.Error).error.code, at = clock.instant())
        val failed =
            transactor.run("transfers.refused") {
                transfers.move(transfer.transferId, TransferState.FAILED, change)?.also {
                    transactions.moveByTransfer(transfer.transferId, TransferState.FAILED, change)
                }
            }
        if (failed != null) rules.release(transfer.senderId, transfer.createdAt, transfer.amount)
        return answer
    }

    private fun transferOf(
        command: TransferCommand,
        sender: Sender,
        destination: Destination,
    ): Transfer {
        val now = clock.instant()
        return Transfer(
            transferId = TransferId.new(),
            senderId = command.senderId,
            senderWalletId = sender.wallet.circleWalletId,
            blockchain = command.blockchain,
            idempotencyKey = command.idempotencyKey,
            recipientId = destination.recipientId,
            destinationAddress = destination.address,
            amount = command.amount,
            state = TransferState.AWAITING_SIGNATURE,
            circleTxId = null,
            txHash = null,
            chatId = command.chatId,
            origin = if (command.proposalId != null) TransferOrigin.ASHA else TransferOrigin.APP,
            proposalId = command.proposalId,
            failureReason = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun outgoingRow(transfer: Transfer) =
        WalletTransaction(
            id = ids.next(),
            userId = transfer.senderId,
            direction = Direction.OUT,
            state = transfer.state,
            amount = transfer.amount,
            blockchain = transfer.blockchain,
            counterpartyId = transfer.recipientId,
            counterpartyAddress = transfer.destinationAddress,
            transferId = transfer.transferId,
            circleTxId = null,
            txHash = null,
            chatId = transfer.chatId,
            origin = transfer.origin,
            occurredAt = transfer.createdAt,
        )
}
