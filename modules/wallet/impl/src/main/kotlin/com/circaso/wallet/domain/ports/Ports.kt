package com.circaso.wallet.domain.ports

import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.domain.models.Holding
import java.time.Instant

/**
 * `wal:balance:{circleWalletId}`: what one chain's wallet holds, for 15 seconds, so opening the wallet screen doesn't
 * call Circle every time (Wallet → Data → Redis). Cleared by any webhook for the wallet (WS3).
 */
interface BalanceCache {
    suspend fun get(circleWalletId: String): List<Holding>?

    suspend fun put(circleWalletId: String, holdings: List<Holding>)

    suspend fun clear(circleWalletId: String)
}

/**
 * What the wallet tells operators about in the log (the domain doesn't log itself). Never a user id, an address or an
 * amount.
 */
interface WalletWarnings {
    /** A wallet sits on [blockchain], which is no longer in the custody configuration, so its balance can't be read. */
    fun chainNotConfigured(blockchain: String, circleWalletId: String)

    /** Circle told us about a transaction of a wallet that isn't ours, even after asking Circle for its user. */
    fun unknownWallet(blockchain: String, circleWalletId: String)

    /** An update carries a transfer's `refId` but comes from another wallet than the transfer's; it isn't applied. */
    fun refIdOfAnotherWallet(transferId: String, circleWalletId: String)

    /**
     * A split payment's batch matched [candidates] unsettled transfers of its wallet by its legs (the same payee,
     * amount and fee); the newest was taken.
     */
    fun splitPaymentAmbiguous(circleTxId: String, candidates: Int) {}

    /** A transfer is still `SUBMITTED` an hour after it was prepared (`wallet.stuck-transfers`); said once. */
    fun transferStuck(transferId: String, blockchain: String)

    /** A reconciliation job couldn't read Circle and backs off; [skipRuns] runs are skipped before the next try. */
    fun jobBackingOff(job: String, skipRuns: Int)

    /** A confirmed payment's network cost came to more than its rule allows of the fee (25 %: §7b's alert). */
    fun gasAboveShare(
        transferId: String,
        blockchain: String,
        shareBps: Int,
    )

    /** A rule asks for a fee on [blockchain] but none can be taken there ([reason]); the payment goes without one. */
    fun feeNotTaken(blockchain: String, reason: String)
}

/**
 * `wal:active`: a hash of the users who opened the wallet (W1) or a Circle session (W3) lately, field the user id,
 * value when (epoch ms); the key expires one active window after the last write. The light reconciliation pass reads
 * it, so money that came in while a webhook was lost shows within minutes for anyone looking. Recording never fails
 * the request that records it.
 */
interface ActiveWallets {
    suspend fun touch(userId: UserId)

    /** The users active since [cutoff], most recent first; older entries are dropped. */
    suspend fun since(cutoff: Instant): List<UserId>
}

/** `wal:stuck:{transferId}`: a stuck transfer is reported once (`SET NX`, 2 days). */
interface StuckNotices {
    /** True if this call took the notice for [transferId]. */
    suspend fun claim(transferId: TransferId): Boolean
}

/**
 * `wal:adding:{userId}`: the chains the user asked to add (W12) and hasn't finished in the SDK, for an hour (a
 * challenge's session lasts 60 minutes). While one is listed, W1 asks Circle for the user's wallets so the new chain
 * shows up as soon as Circle has it.
 */
interface AddingChains {
    suspend fun add(userId: UserId, blockchain: String)

    suspend fun list(userId: UserId): Set<String>

    suspend fun remove(userId: UserId, blockchain: String)
}
