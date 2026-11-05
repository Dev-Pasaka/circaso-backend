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

/**
 * `wal:setupcheck:{holder}`: Circle's wallet list is read for a wallet being set up at most once per window (`SET NX`,
 * [SETUP_CHECK_WINDOW]), so the app polling W1, W15 or W19 every 3 seconds, from one device or several, can't hammer
 * Circle.
 */
interface SetupChecks {
    /** True if this call may ask Circle now for [holder]'s wallets. */
    suspend fun claim(holder: UserId): Boolean
}

/** Shorter than the app's 3-second poll, so each poll reads Circle while extra callers in between share it. */
val SETUP_CHECK_WINDOW: java.time.Duration = java.time.Duration.ofMillis(2_500)

/**
 * Wallets being set up: `wal:setup:{holder}` the challenge handed out (so a second "set up" tap gives back the one
 * still open instead of a second `user/initialize`), and `wal:setups`, a hash of holder to when the setup began, for
 * `wallet.setup-sweep`. Both last [SETUP_SWEEP_WINDOW] from the last challenge handed out.
 */
interface PendingSetups {
    suspend fun put(holder: UserId, challengeId: String)

    /** The challenge handed out for [holder]'s setup and not yet finished, if any. */
    suspend fun challenge(holder: UserId): String?

    /** The holders whose setup began since [cutoff]; older entries are dropped. */
    suspend fun since(cutoff: Instant): List<UserId>

    /** The setup finished (the wallet recorded) or its challenge failed. */
    suspend fun clear(holder: UserId)
}

/** How long a wallet setup is followed by `wallet.setup-sweep`, and its challenge handed out again. */
val SETUP_SWEEP_WINDOW: java.time.Duration = java.time.Duration.ofMinutes(30)
