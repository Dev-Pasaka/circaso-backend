package com.circaso.wallet.domain.support

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.ports.PendingSetups
import com.circaso.wallet.domain.ports.SetupChecks
import com.circaso.wallet.domain.repositories.WalletRepository
import java.time.Instant
import java.util.UUID

/**
 * A wallet being set up turns `ACTIVE` as soon as Circle has it, not at the next sweep. Circle creates the wallets once
 * the PIN challenge is signed in the SDK; two ways bring them to our rows through [WalletSync], the path every other
 * reader of Circle's list shares (so the insert claim decides who announces `WalletCreated`, whichever comes first):
 *
 * - [onChallenge]: Circle's `challenges.initialize` / `challenges.createWallet` webhook for one of our Circle users.
 * - [refresh]: the setup-status reads the app polls (W1, W15, W19) while no chain is recorded, at most once per
 *   [SetupChecks] window per wallet.
 * - [sweep]: `wallet.setup-sweep`, every 30 seconds over the setups begun in the last 30 minutes ([PendingSetups]).
 */
class SetupProgress(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val checks: SetupChecks,
    private val pending: PendingSetups,
) {
    /**
     * Reads Circle for [holder]'s wallets while one is being set up (a Circle user, no active chain recorded) and the
     * window allows. Never fails the read: Circle down leaves our rows, the next poll or the webhook tries again.
     */
    suspend fun refresh(holder: UserId) {
        val user = wallets.circleUser(holder) ?: return
        refresh(user)
    }

    /** [refresh] for a Circle user already read; the rows after it, or null when Circle wasn't asked or didn't answer. */
    suspend fun refresh(user: CircleUser): List<ChainWallet>? {
        if (wallets.wallets(user.userId).any { it.state == ChainWalletState.ACTIVE }) return null
        if (!checks.claim(user.userId)) return null
        return (sync.sync(user) as? Resource.Success)?.data
    }

    /**
     * Circle says [circleUserId] finished a challenge that creates wallets. Not one of ours: nothing to do. Circle
     * unreachable: the error makes the webhook answer non-200, so Circle delivers it again.
     */
    suspend fun onChallenge(circleUserId: String, completed: Boolean): Resource<Unit> {
        val id = runCatching { UUID.fromString(circleUserId) }.getOrNull() ?: return Resource.success(Unit)
        val user = wallets.circleUserByCircleId(id) ?: return Resource.success(Unit)
        // A failed challenge created nothing; the next "set up" tap starts a fresh one.
        if (!completed) {
            pending.clear(user.userId)
            return Resource.success(Unit)
        }
        return when (val synced = sync.sync(user)) {
            is Resource.Success -> {
                if (synced.data.any { it.state == ChainWalletState.ACTIVE }) pending.clear(user.userId)
                Resource.success(Unit)
            }

            is Resource.Error -> {
                synced
            }
        }
    }

    /**
     * `wallet.setup-sweep`, the backstop for a lost webhook while nobody polls: every wallet whose setup began within
     * [since] is read from Circle (through [WalletSync]), at most [batch]; a recorded one leaves the list. Stops at the
     * first Circle failure, the rest wait for the next run. Returns how many were read.
     */
    suspend fun sweep(since: Instant, batch: Int): Int {
        var read = 0
        for (holder in pending.since(since).take(batch)) {
            val user = wallets.circleUser(holder)
            if (user == null || wallets.wallets(holder).any { it.state == ChainWalletState.ACTIVE }) {
                pending.clear(holder)
                continue
            }
            val synced = sync.sync(user) as? Resource.Success ?: return read
            read++
            if (synced.data.any { it.state == ChainWalletState.ACTIVE }) pending.clear(holder)
        }
        return read
    }
}
