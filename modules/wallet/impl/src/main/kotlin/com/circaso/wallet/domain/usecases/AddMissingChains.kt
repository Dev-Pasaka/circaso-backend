package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.ChainAddition
import com.circaso.wallet.domain.models.ChainStatus
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.ports.AddingChains
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.SupportAccountRule
import com.circaso.wallet.domain.support.WalletSync
import com.circaso.wallet.domain.support.orCustodyUnavailable
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * W13 `POST /wallet/chains/missing`: puts the wallet on every supported chain it isn't on yet (decision 2026-10-30:
 * everyone holds every supported chain, and a chain added to the list later is offered the same way). Chains that
 * can share a challenge are asked for together, so the person enters the PIN once per group (once for the EVM chains,
 * once for Solana). Circle's list is read first, as in W12, so nothing is made twice; each group's key is the user,
 * the group and the hour, so two calls at once share one challenge.
 */
class AddMissingChains(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val adding: AddingChains,
    private val custody: StablecoinCustody,
    private val support: SupportAccountRule,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: UserId): Resource<List<ChainAddition>> {
        support.refusal(userId)?.let { return it }
        val user = wallets.circleUser(userId) ?: return Resource.error(WalletError.NotFound)
        val known =
            when (val synced = sync.sync(user)) {
                is Resource.Success -> synced.data.filter { it.state != ChainWalletState.CLOSED }
                is Resource.Error -> return synced
            }
        if (known.isEmpty()) return Resource.error(WalletError.NotReady)
        val missing =
            custody.supported.chains
                .filter { it.receiveEnabled && known.none { w -> w.blockchain == it.blockchain } }
                .map { it.blockchain }
        val additions = mutableListOf<ChainAddition>()
        for ((group, chains) in missing.groupBy { custody.creationGroup(it) }) {
            val challenge =
                when (
                    val created =
                        custody
                            .addWallets(
                                user.circleUserId.toString(),
                                chains,
                                key(userId, group),
                            ).orCustodyUnavailable()
                ) {
                    is Resource.Success -> created.data
                    is Resource.Error -> return created
                }
            chains.forEach { blockchain ->
                adding.add(userId, blockchain)
                additions += ChainAddition(blockchain, ChainStatus.ADDING, challenge.challengeId, challenge.sdk)
            }
        }
        return Resource.success(additions)
    }

    private fun key(userId: UserId, group: String): UUID {
        val window = clock.instant().truncatedTo(ChronoUnit.HOURS)
        return UUID.nameUUIDFromBytes("$userId|wallet.add-missing|$group|$window".toByteArray())
    }
}
