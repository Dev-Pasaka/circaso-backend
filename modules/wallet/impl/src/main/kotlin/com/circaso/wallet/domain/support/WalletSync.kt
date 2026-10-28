package com.circaso.wallet.domain.support

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.envelope
import com.circaso.wallet.api.WalletCreated
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.WalletOutbox
import com.circaso.wallet.domain.repositories.WalletRepository
import java.time.Clock

/**
 * Brings our `wallets` rows up to what Circle has. Circle creates a wallet only once the user signs the challenge in
 * the SDK, and the webhook that would tell us may come late or not at all, so W1, W2 and W12 ask Circle's wallet list
 * while a wallet is being set up or a chain added (Wallet → W2).
 *
 * Each chain Circle reports that we don't have is inserted with its `WalletCreated` in the same transaction. The
 * insert is a claim: when two requests see the new wallet at once, only the one whose insert lands writes and
 * publishes the event.
 */
class WalletSync(
    private val wallets: WalletRepository,
    private val transactor: Transactor,
    private val outbox: WalletOutbox,
    private val bus: EventBus,
    private val custody: StablecoinCustody,
    private val adding: AddingChains,
    private val clock: Clock,
) {
    /** Every wallet of [user] after recording the new ones; `wallet.custody_unavailable` if Circle can't answer. */
    suspend fun sync(user: CircleUser): Resource<List<ChainWallet>> =
        when (val synced = syncAll(user)) {
            is Resource.Success -> Resource.success(synced.data.recorded)
            is Resource.Error -> synced
        }

    /**
     * Our rows and Circle's own list, after recording what Circle has that we don't. Circle's list can hold a wallet
     * our rows never will (a second wallet on a chain, which `wallets_user_chain_key` refuses), so a decision about
     * money (the deletion guard) reads [Synced.atCircle].
     */
    suspend fun syncAll(user: CircleUser): Resource<Synced> {
        val reported =
            when (val listed = custody.wallets(user.circleUserId.toString()).orCustodyUnavailable()) {
                is Resource.Success -> listed.data
                is Resource.Error -> return listed
            }
        val known = wallets.wallets(user.userId).map { it.circleWalletId }.toSet()
        val now = clock.instant()
        val atCircle = reported.map { it.toChainWallet(user.userId, now) }
        val fresh = atCircle.filter { it.circleWalletId !in known }
        if (fresh.isNotEmpty()) record(fresh)

        val all = wallets.wallets(user.userId)
        forgetAdded(user, all)
        return Resource.success(Synced(recorded = all, atCircle = atCircle))
    }

    /** Our rows ([recorded]) and every wallet Circle lists for the user ([atCircle]), recorded or not. */
    data class Synced(val recorded: List<ChainWallet>, val atCircle: List<ChainWallet>)

    private suspend fun record(fresh: List<ChainWallet>) {
        val announced = fresh.associateWith { bus.envelope(WalletCreated(it.userId, it.blockchain)) }
        val inserted =
            transactor.run("wallets.record") {
                val won = fresh.filter { wallets.insertWallet(it) }
                outbox.write(won.map { announced.getValue(it) })
                won
            }
        inserted.forEach { bus.publish(announced.getValue(it)) }
    }

    /** A chain that is now on the wallet is no longer being added. */
    private suspend fun forgetAdded(user: CircleUser, all: List<ChainWallet>) {
        val pending = adding.list(user.userId)
        all.filter { it.blockchain in pending }.forEach { adding.remove(user.userId, it.blockchain) }
    }
}
