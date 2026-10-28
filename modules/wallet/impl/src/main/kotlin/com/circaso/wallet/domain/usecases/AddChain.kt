package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.AddChainCommand
import com.circaso.wallet.domain.models.ChainAddition
import com.circaso.wallet.domain.models.ChainStatus
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.WalletSync
import com.circaso.wallet.domain.support.orCustodyUnavailable

/**
 * W12 `POST /wallet/chains`: the user adds one more supported chain to their wallet (decision 2026-10-28). Circle
 * creates the wallet there once the user signs the challenge with the PIN; until then the chain is listed as being
 * added, and W1 picks the new wallet up from Circle. A chain already on the wallet answers at once with nothing to
 * sign, so the call can be repeated safely.
 *
 * Before asking Circle for a challenge, Circle's wallet list is always read and recorded: the user may already have
 * signed (on this phone or another, whatever `wal:adding` says), and a second challenge could make a second wallet on
 * the same chain. `wal:adding` only tells W1 and W12's list when to poll Circle.
 */
class AddChain(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val adding: AddingChains,
    private val custody: StablecoinCustody,
) {
    suspend operator fun invoke(command: AddChainCommand): Resource<ChainAddition> {
        custody.supported.find(command.blockchain) ?: return Resource.error(WalletError.ChainUnsupported)
        val user = wallets.circleUser(command.userId) ?: return Resource.error(WalletError.NotFound)

        if (wallets.wallets(command.userId).isOn(command.blockchain)) return Resource.success(enabled(command))
        val known =
            when (val synced = sync.sync(user)) {
                is Resource.Success -> synced.data
                is Resource.Error -> return synced
            }
        if (known.none { it.isOpen() }) return Resource.error(WalletError.NotReady)
        if (known.isOn(command.blockchain)) return Resource.success(enabled(command))

        val challenge =
            when (
                val created =
                    custody
                        .addWallet(user.circleUserId.toString(), command.blockchain, command.idempotencyKey)
                        .orCustodyUnavailable()
            ) {
                is Resource.Success -> created.data
                is Resource.Error -> return created
            }
        adding.add(command.userId, command.blockchain)
        return Resource.success(
            ChainAddition(command.blockchain, ChainStatus.ADDING, challenge.challengeId, challenge.sdk),
        )
    }

    private fun List<ChainWallet>.isOn(blockchain: String) = any { it.isOpen() && it.blockchain == blockchain }

    private fun enabled(command: AddChainCommand) =
        ChainAddition(command.blockchain, ChainStatus.ENABLED, challengeId = null, sdk = null)

    private fun ChainWallet.isOpen() = state != ChainWalletState.CLOSED
}
