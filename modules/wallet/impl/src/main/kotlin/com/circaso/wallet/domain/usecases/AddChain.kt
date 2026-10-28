package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.AddChainCommand
import com.circaso.wallet.domain.models.ChainAddition
import com.circaso.wallet.domain.models.ChainStatus
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.CircleUser
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
 * Before asking Circle for a challenge, a chain that is being added is looked up at Circle: the user may already have
 * signed, and a second challenge could make a second wallet on the same chain.
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

        val known = current(user, command.blockchain)
        if (known.none { it.isOpen() }) return Resource.error(WalletError.NotReady)
        if (known.any { it.isOpen() && it.blockchain == command.blockchain }) return Resource.success(enabled(command))

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

    /**
     * Our rows, after asking Circle when the wallet has none yet or this chain is already being added; an error from
     * Circle there leaves our rows, and the challenge request that follows will meet the same outage.
     */
    private suspend fun current(user: CircleUser, blockchain: String): List<ChainWallet> {
        val known = wallets.wallets(user.userId)
        val askCircle = known.none { it.isOpen() } || blockchain in adding.list(user.userId)
        if (!askCircle) return known
        return (sync.sync(user) as? Resource.Success)?.data ?: known
    }

    private fun enabled(command: AddChainCommand) =
        ChainAddition(command.blockchain, ChainStatus.ENABLED, challengeId = null, sdk = null)

    private fun ChainWallet.isOpen() = state != ChainWalletState.CLOSED
}
