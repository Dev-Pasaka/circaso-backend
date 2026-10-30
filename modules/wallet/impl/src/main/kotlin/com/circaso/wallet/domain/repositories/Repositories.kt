package com.circaso.wallet.domain.repositories

import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferState
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

/** `transfers`: one row per prepared transfer, unique per sender and `Idempotency-Key` for good. */
interface TransferRepository {
    /** Inserts [transfer] unless the sender already used its key; true if this call inserted it. */
    suspend fun insert(transfer: Transfer): Boolean

    suspend fun byKey(senderId: UserId, idempotencyKey: UUID): Transfer?

    suspend fun find(transferId: TransferId): Transfer?

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
}

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
}

/** Wallet's outbox rows, written inside the transaction that makes the change. */
interface WalletOutbox {
    suspend fun write(events: List<EventEnvelope>)
}
