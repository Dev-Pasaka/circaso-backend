package com.circaso.wallet.domain.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.models.FeeEntry
import com.circaso.wallet.domain.models.FeeQuote
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.FeeState
import com.circaso.wallet.domain.models.HistoryFilter
import com.circaso.wallet.domain.models.HistoryPosition
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.models.WalletTransaction
import java.time.Instant
import java.util.UUID

/** `circle_users` and `wallets`: the wallet and the chains it is on. */
interface WalletRepository {
    suspend fun circleUser(userId: UserId): CircleUser?

    /** The account behind Circle's user id, for a Circle notification about a wallet we haven't recorded yet. */
    suspend fun circleUserByCircleId(circleUserId: UUID): CircleUser?

    /** Inserts [user] unless the account already has a Circle user; true if this call inserted it. */
    suspend fun insertCircleUser(user: CircleUser): Boolean

    /** The user's wallets on every chain, oldest first. */
    suspend fun wallets(userId: UserId): List<ChainWallet>

    /** One wallet by Circle's id (webhooks carry it). */
    suspend fun wallet(circleWalletId: String): ChainWallet?

    /** The wallet at [address] (normalised) on [blockchain], through `wallets_address_key`. */
    suspend fun walletAt(address: String, blockchain: String): ChainWallet?

    /**
     * Inserts the wallet on one chain unless it is already recorded (same Circle wallet id, same user and chain, or
     * same address on that chain); true if this call inserted it, which is what announces it.
     */
    suspend fun insertWallet(wallet: ChainWallet): Boolean
}

/**
 * `wallet_owners`: who the wallets that aren't a person's own belong to (decision 2026-11-03). A wallet id with no row
 * is a person's own wallet.
 */
interface WalletOwnerRepository {
    /** The group's or space's wallet filed under [walletId]; null for a person's own (they have no row). */
    suspend fun find(walletId: WalletId): WalletOwner?

    /** The group's wallet, through `wallet_owners_chat_key`. */
    suspend fun byChat(chatId: ChatId): WalletOwner.Group?

    /** The wallets of [chatIds] that have one (W16: the groups the caller is in). */
    suspend fun byChats(chatIds: Collection<ChatId>): List<WalletOwner.Group>

    /** The group and space wallets [signerId] signs for (`wallet_owners_signer_idx`). */
    suspend fun bySigner(signerId: UserId): List<WalletOwner>

    /** Inserts [owner] unless the wallet id or the group already has a row; true if this call inserted it. */
    suspend fun insert(owner: WalletOwner.Group): Boolean

    /** The space's wallet, through `wallet_owners_space_key` (Spaces phase 3). */
    suspend fun bySpace(spaceId: UUID): WalletOwner.Space? = null

    /** The wallets of [spaceIds] that have one (W16: the spaces the caller administers). */
    suspend fun bySpaces(spaceIds: Collection<UUID>): List<WalletOwner.Space> = emptyList()

    /** Inserts [owner] unless the wallet id or the space already has a row; true if this call inserted it. */
    suspend fun insertSpace(owner: WalletOwner.Space): Boolean = false
}

/** `transfers`: one row per prepared transfer, unique per sender and `Idempotency-Key` for good. */
interface TransferRepository {
    /** Inserts [transfer] unless the sender already used its key; true if this call inserted it. */
    suspend fun insert(transfer: Transfer): Boolean

    suspend fun byKey(senderId: UserId, idempotencyKey: UUID): Transfer?

    suspend fun find(transferId: TransferId): Transfer?

    /** Every transfer prepared for the approved payout [payoutRequestId] (`transfers_payout_idx`), in any state. */
    suspend fun forPayout(payoutRequestId: UUID): List<Transfer>

    /**
     * The transition guard: moves the transfer to [to] only from one of [from] (by default every allowed predecessor,
     * `to.from`; a caller narrows it to learn where the transfer came from), applying [change]; the transfer as it now
     * is, or null when it wasn't in one of those states (a late or repeated update changes nothing).
     */
    suspend fun move(
        transferId: TransferId,
        to: TransferState,
        change: TransferChange,
        from: Set<TransferState> = to.from,
    ): Transfer?

    /**
     * Transfers in [state] prepared in [createdAfter, createdBefore), newest first, at most [limit]: the open ones the
     * jobs look after (`transfers_open_idx`).
     */
    suspend fun inState(
        state: TransferState,
        createdAfter: Instant,
        createdBefore: Instant,
        limit: Int,
    ): List<Transfer>

    /**
     * The transfer from the wallet [senderWalletId] that Circle's transaction [circleTxId] already moved
     * (`transfers_circle_tx_idx`): a split payment's batch carries no `refId`, so its later updates find it this way.
     */
    suspend fun byCircleTx(senderWalletId: String, circleTxId: String): Transfer?

    /**
     * Split payments (a fee leg, phase 2F) prepared in [createdAfter, createdBefore) that no Circle transaction has
     * moved yet: still awaiting a signature, or expired while Circle may have them signed after all
     * (`transfers_unsettled_split_idx`). Only the wallet [senderWalletId]'s when given. Newest first, at most [limit].
     */
    suspend fun unsettledSplits(
        senderWalletId: String?,
        createdAfter: Instant,
        createdBefore: Instant,
        limit: Int,
    ): List<Transfer>

    /**
     * Holds [holder]'s spending for the transaction that is open (a row lock on its `circle_users` row), so two
     * payments prepared at once can't both fit under a limit only one of them fits.
     */
    suspend fun lockHolder(holder: UserId)

    /**
     * What [holder] has sent or may still send since [since] (§7a): transfers awaiting a signature, submitted or
     * confirmed, each with what it takes out, fee included. A failed or expired one doesn't count.
     */
    suspend fun spentSince(holder: UserId, since: Instant): List<CountedTransfer>

    /**
     * The transfers that may have paid [recipientId]'s `IN` row (2026-11-05): from the wallet [senderWalletId] on
     * [blockchain], of exactly [amountMinor], prepared since [createdAfter], in any state but `FAILED` (an
     * `AWAITING_SIGNATURE` or `EXPIRED` one too: Circle's inbound update can arrive before its outbound one moves the
     * transfer); newest first, at most [limit] (`transfers_paid_to_idx`).
     */
    suspend fun paidTo(
        recipientId: UserId,
        senderWalletId: String,
        blockchain: String,
        amountMinor: Long,
        createdAfter: Instant,
        limit: Int,
    ): List<Transfer>
}

/** A transfer counted against a limit; [awaiting] while it still awaits its signature. */
data class CountedTransfer(val createdAt: Instant, val totalMinor: Long, val awaiting: Boolean)

/** `transactions`: the history rows, the sender's `OUT` and the recipient's `IN`. */
interface TransactionRepository {
    /**
     * Inserts [transaction] unless a row for the same Circle transaction and user (or the same transfer) exists; true
     * if this call inserted it, so a repeated inbound notification adds nothing.
     */
    suspend fun insert(transaction: WalletTransaction): Boolean

    /** The transition guard on the `OUT` row of [transferId]; true if it moved. */
    suspend fun moveByTransfer(
        transferId: TransferId,
        to: TransferState,
        change: TransferChange,
    ): Boolean

    /** The transition guard on [userId]'s row of Circle's transaction; the row as it now is, or null if not moved. */
    suspend fun moveByCircle(
        circleTxId: String,
        userId: UserId,
        to: TransferState,
        change: TransferChange,
    ): WalletTransaction?

    /**
     * The users with a history row since [since], on either side (the row's user and its counterparty), at most
     * [limit]: whom `wallet.reconcile-daily` (and the light pass) compare with Circle (`transactions_occurred_idx`).
     */
    suspend fun parties(since: Instant, limit: Int): List<UserId>

    /**
     * [userId]'s rows in one of [states] with `occurred_at` in [from, until) (either end open when null), matching
     * [filter], strictly after [after] in the order `occurred_at DESC, id DESC`, at most [limit]: W6's keyset page
     * (`transactions_user_idx`).
     */
    suspend fun history(
        userId: UserId,
        states: Set<TransferState>,
        from: Instant?,
        until: Instant?,
        after: HistoryPosition?,
        limit: Int,
        filter: HistoryFilter = HistoryFilter.ANY,
    ): List<WalletTransaction>

    /** [userId]'s own row [id], or null when there is none or it is someone else's. */
    suspend fun find(userId: UserId, id: UUID): WalletTransaction?

    /** True when an `IN` row already names [transferId] as the transfer that paid it (`transactions_paid_by_idx`). */
    suspend fun isPaidBy(transferId: TransferId): Boolean

    /**
     * Names [transferId] (and its [chatId]) as what paid the row [id], only while the row names none
     * (`paid_by_transfer_id IS NULL`); the row as it now is, or null when it was linked already.
     */
    suspend fun linkPayment(
        id: UUID,
        transferId: TransferId,
        chatId: ChatId?,
    ): WalletTransaction?
}

/** Wallet's outbox rows, written inside the transaction that makes the change. */
interface WalletOutbox {
    suspend fun write(events: List<EventEnvelope>)
}

/** `fee_rules` and `limit_rules` (phase 2F): every version, edited with SQL now and from the admin site later. */
interface RuleRepository {
    /** Every version of every fee rule; `RuleVersions.current` picks the ones in force. */
    suspend fun feeRules(): List<FeeRule>

    /** Every version of every limit rule, as for [feeRules]. */
    suspend fun limitRules(): List<LimitRule>
}

/** `fee_quotes`: what W17 showed, held for the signing window. */
interface FeeQuoteRepository {
    suspend fun insert(quote: FeeQuote)

    suspend fun find(quoteId: UUID): FeeQuote?
}

/** `fee_ledger`: one row per transfer, written with it, settled when the chain says how it ended. */
interface FeeLedgerRepository {
    suspend fun insert(entry: FeeEntry)

    /**
     * Moves the transfer's row from `QUOTED` to [state], with the transaction hash and the network cost the chain
     * reported ([actualGasNative] in the native coin, [actualGasMinor] in dollars); null when it had moved already.
     */
    suspend fun settle(
        transferId: TransferId,
        state: FeeState,
        txHash: String?,
        actualGasNative: String?,
        actualGasMinor: Long?,
        at: Instant,
    ): FeeEntry?
}
