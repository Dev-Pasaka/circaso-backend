package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.PaymentLeg
import com.circaso.integrations.api.SplitTransferRequest
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.TransferRequest
import com.circaso.shared.Asset
import com.circaso.shared.EventBus
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.wallet.api.PayoutApprovals
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.Fee
import com.circaso.wallet.domain.models.FeeEntry
import com.circaso.wallet.domain.models.FeeState
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.PreparedTransfer
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.FeeLedgerRepository
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletOutbox
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.Addresses
import com.circaso.wallet.domain.support.FeeQuotes
import com.circaso.wallet.domain.support.Holders
import com.circaso.wallet.domain.support.Limits
import com.circaso.wallet.domain.support.OrderedIds
import com.circaso.wallet.domain.support.PaymentPlan
import com.circaso.wallet.domain.support.PaymentPlanner
import com.circaso.wallet.domain.support.Sender
import com.circaso.wallet.domain.support.TransferEvents
import com.circaso.wallet.domain.support.isRefusal
import com.circaso.wallet.domain.support.orTransferError
import java.time.Clock

/**
 * W4 `POST /wallet/transfers`: checks the rules in order (account `ACTIVE`; wallet active on the transfer's chain; no
 * payment hold; an amount that can be sent and within one payment's cap; the payee reachable with a wallet on that
 * chain, or the address valid on it; the fee; enough on that chain for the amount and its fee; the rolling limit),
 * writes the transfer `AWAITING_SIGNATURE` with the sender's `OUT` row and its fee ledger row, then asks Circle for the
 * challenge the user signs with the PIN, with `refId` = the transfer id, so Circle's updates lead back to it.
 *
 * The fee (phase 2F, spaces-plan §7b) is the one the person was shown: the W17 quote named by `quoteId` while it holds
 * (15 minutes). Without one, or once it has run out, the fee is worked out again; if it is now higher, nothing is
 * prepared and `wallet.fee_changed` carries the new fee and a new quote to show first (GW13c). On a chain that can
 * split, the fee travels in the same signature: one batch on the sender's own wallet pays the payee and Circaso's fee
 * wallet, all or nothing.
 *
 * A transfer is unique per sender and `Idempotency-Key` for good: a repeated key returns the transfer already
 * prepared, with Circle's same challenge (same Circle key) while it still awaits a signature. The row is claimed before
 * Circle is asked, so two requests with one key never make two transfers, and the limit is checked again under a lock
 * on the wallet, so two payments at once can't both use what is left. When Circle refuses, the transfer is `FAILED`
 * (with its `TransferFailed`), which gives its amount back to the window; when Circle is down it stays
 * `AWAITING_SIGNATURE`, and the same request later asks again.
 */
class PrepareTransfer(
    private val transfers: TransferRepository,
    private val transactions: TransactionRepository,
    private val wallets: WalletRepository,
    private val transactor: Transactor,
    private val planner: PaymentPlanner,
    private val limits: Limits,
    private val quotes: FeeQuotes,
    private val ledger: FeeLedgerRepository,
    private val custody: StablecoinCustody,
    private val outbox: WalletOutbox,
    private val bus: EventBus,
    private val events: TransferEvents,
    private val ids: OrderedIds,
    private val clock: Clock,
    private val holders: Holders,
    private val approvals: PayoutApprovals,
) {
    suspend operator fun invoke(command: TransferCommand): Resource<PreparedTransfer> {
        // The wallet it leaves (decision 2026-11-03): the caller's own, or a group wallet they sign for.
        val owner =
            when (val access = holders.sign(command.senderId, command.walletId)) {
                is Resource.Success -> access.data.owner
                is Resource.Error -> return access
            }
        val holder = owner.holder
        transfers.byKey(holder, command.idempotencyKey)?.let { return resume(it) }

        payout(owner, command).let { if (it is Resource.Error) return it }
        val plan =
            when (val planned = planner.plan(owner, command, strict = true)) {
                is Resource.Success -> planned.data
                is Resource.Error -> return planned
            }
        val fee =
            when (val locked = feeOf(plan, command)) {
                is Resource.Success -> locked.data
                is Resource.Error -> return locked
            }
        val amount = command.amount.amountMinor
        if (amount + fee.amountMinor > plan.balanceMinor) return Resource.error(short(plan, fee))
        limits.check(plan.allowance, amount, fee.amountMinor, plan.maxAmount()).let {
            if (it is Resource.Error) return it
        }

        val transfer = transferOf(command, plan, fee)
        val recorded =
            when (val written = record(plan, transfer, fee)) {
                is Resource.Success -> written.data
                is Resource.Error -> return written
            }
        if (!recorded) return resume(checkNotNull(transfers.byKey(holder, command.idempotencyKey)))
        return challenge(plan.sender, transfer).let {
            if (it is Resource.Success) Resource.success(it.data.copy(fee = fee)) else it
        }
    }

    /**
     * The fee to charge: the named quote's while it holds and is for exactly this payment; otherwise the fee now, as
     * long as it isn't more than the one shown (none shown is a fee of zero).
     */
    private suspend fun feeOf(plan: PaymentPlan, command: TransferCommand): Resource<Fee> {
        val named = quotes.named(plan, command)
        if (named != null && quotes.holds(named)) return Resource.success(named.fee)
        val now = plan.feeFor(command.amount.amountMinor)
        val shown = named?.fee?.amountMinor ?: 0
        if (now.amountMinor <= shown) return Resource.success(now)
        val fresh = quotes.hold(plan, command, now)
        return Resource.error(
            WalletError.FeeChanged(
                Limits.details(
                    "quotedFeeMinor" to shown.toString(),
                    "feeMinor" to now.amountMinor.toString(),
                    "totalMinor" to (command.amount.amountMinor + now.amountMinor).toString(),
                    "rateBps" to now.rateBps.toString(),
                    "minimum" to now.minimum.toString(),
                    "quoteId" to fresh.quoteId.toString(),
                    "expiresAt" to fresh.expiresAt.toString(),
                ),
            ),
        )
    }

    private fun short(plan: PaymentPlan, fee: Fee) =
        WalletError.FundsShort(
            Limits.details(
                "balanceMinor" to plan.balanceMinor.toString(),
                "feeMinor" to fee.amountMinor.toString(),
                "maxAmountMinor" to plan.maxAmount().toString(),
            ),
        )

    /**
     * Option A (decision 2026-11-03): money leaves a group wallet only for a payout the group's admins approved, to
     * exactly its payee and for exactly its amount, and only once at a time; a personal wallet takes no payout.
     */
    private suspend fun payout(owner: WalletOwner, command: TransferCommand): Resource<Unit> {
        val requestId = command.payoutRequestId
        if (owner !is WalletOwner.Group) {
            return if (requestId == null) Resource.success(Unit) else Resource.error(WalletError.PayoutMismatch)
        }
        requestId ?: return Resource.error(WalletError.PayoutRequired)
        val approved =
            approvals.approved(owner.chatId, requestId) ?: return Resource.error(WalletError.PayoutNotApproved)
        val samePayee =
            when (val payee = command.payee) {
                is Payee.Person -> {
                    approved.payeeUserId == payee.userId
                }

                is Payee.Address -> {
                    approved.payeeAddress?.let { Addresses.normalise(it.trim()) } == Addresses.normalise(payee.address)
                }

                is Payee.Group -> {
                    false
                }
            }
        val sameAmount = command.amount.asset == Asset.USDC && command.amount.amountMinor == approved.amountMinor
        if (!samePayee || !sameAmount) return Resource.error(WalletError.PayoutMismatch)
        val now = clock.instant()
        val live =
            transfers.forPayout(requestId).any {
                it.state == TransferState.SUBMITTED ||
                    it.state == TransferState.CONFIRMED ||
                    (it.state == TransferState.AWAITING_SIGNATURE && now < it.expiresAt)
            }
        return if (live) Resource.error(WalletError.PayoutInProgress) else Resource.success(Unit)
    }

    /**
     * The transfer, the sender's history row and the fee ledger row, in one transaction under a lock on the wallet,
     * with the limit checked again inside it; false when the key was already used.
     */
    private suspend fun record(
        plan: PaymentPlan,
        transfer: Transfer,
        fee: Fee,
    ): Resource<Boolean> =
        transactor.attempt("transfers.prepare") {
            transfers.lockHolder(transfer.senderId)
            val allowance = limits.allowance(plan.owner, transfer.amount.asset)
            val fits = limits.check(allowance, transfer.amount.amountMinor, fee.amountMinor, plan.maxAmount())
            if (fits is Resource.Error) return@attempt fits
            if (!transfers.insert(transfer)) return@attempt Resource.success(false)
            transactions.insert(outgoingRow(transfer))
            ledger.insert(
                FeeEntry(
                    transfer.transferId,
                    transfer.senderId,
                    transfer.amount,
                    fee,
                    FeeState.QUOTED,
                    transfer.createdAt,
                ),
            )
            Resource.success(true)
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

    /** One challenge: a plain transfer, or with a fee the batch that pays the payee and Circaso's fee wallet together. */
    private suspend fun challenge(sender: Sender, transfer: Transfer): Resource<PreparedTransfer> {
        val feeAddress = transfer.feeAddress
        val asked =
            if (feeAddress != null && transfer.feeMinor > 0) {
                custody.prepareSplitTransfer(
                    sender.user.circleUserId.toString(),
                    SplitTransferRequest(
                        blockchain = transfer.blockchain,
                        walletId = transfer.senderWalletId,
                        walletAddress = sender.wallet.address,
                        legs =
                            listOf(
                                PaymentLeg(transfer.destinationAddress, transfer.amount),
                                PaymentLeg(feeAddress, Money(transfer.feeMinor, transfer.amount.asset)),
                            ),
                        idempotencyKey = transfer.idempotencyKey,
                        refId = transfer.transferId.toString(),
                    ),
                )
            } else {
                custody.prepareTransfer(
                    sender.user.circleUserId.toString(),
                    TransferRequest(
                        blockchain = transfer.blockchain,
                        walletId = transfer.senderWalletId,
                        destinationAddress = transfer.destinationAddress,
                        amount = transfer.amount,
                        idempotencyKey = transfer.idempotencyKey,
                        refId = transfer.transferId.toString(),
                    ),
                )
            }
        return when (asked) {
            is Resource.Success -> Resource.success(PreparedTransfer(transfer, asked.data.challengeId, asked.data.sdk))
            is Resource.Error -> refused(transfer, asked)
        }
    }

    /**
     * Circle refused (the same request can't work later): the transfer fails with its `TransferFailed` in the same
     * transaction, published after the commit; a failed transfer no longer counts against the limit, and its fee is
     * void. Only a transfer still awaiting its signature is moved, so the event happens once.
     */
    private suspend fun refused(transfer: Transfer, error: Resource.Error): Resource<PreparedTransfer> {
        val answer = error.orTransferError()
        if (!error.isRefusal) return answer
        val reason = (answer as Resource.Error).error.code
        val change = TransferChange(failureReason = reason, at = clock.instant())
        val event = events.failed(transfer, reason)
        transactor.run("transfers.refused") {
            transfers.move(transfer.transferId, TransferState.FAILED, change, OPEN)?.also {
                transactions.moveByTransfer(transfer.transferId, TransferState.FAILED, change)
                ledger.settle(transfer.transferId, FeeState.VOID, null, null, null, change.at)
                outbox.write(listOf(event))
            }
        } ?: return answer

        bus.publish(event)
        return answer
    }

    private fun transferOf(
        command: TransferCommand,
        plan: PaymentPlan,
        fee: Fee,
    ): Transfer {
        val now = clock.instant()
        val owner = plan.owner
        val destination = plan.destination
        return Transfer(
            transferId = TransferId.new(),
            senderId = owner.holder,
            senderWalletId = plan.sender.wallet.circleWalletId,
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
            signerId = command.senderId,
            payoutRequestId = command.payoutRequestId,
            fromGroupId = (owner as? WalletOwner.Group)?.chatId,
            toGroupId = destination.toGroupId,
            feeMinor = if (fee.charged) fee.amountMinor else 0,
            feeAddress = if (fee.charged) fee.feeAddress else null,
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

    private companion object {
        /** A transfer still waiting for its signature: the only one Circle's refusal at prepare can fail. */
        val OPEN = setOf(TransferState.AWAITING_SIGNATURE)
    }
}
