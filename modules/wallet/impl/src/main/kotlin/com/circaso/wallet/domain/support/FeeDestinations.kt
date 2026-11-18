package com.circaso.wallet.domain.support

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import com.circaso.shared.WalletNetwork
import com.circaso.wallet.domain.errors.FeeWalletError
import com.circaso.wallet.domain.models.FeeDestination
import com.circaso.wallet.domain.models.FeeStaff
import com.circaso.wallet.domain.repositories.FeeDestinationRepository
import com.circaso.wallet.domain.repositories.FeeWalletRepository
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Where collected fees may be withdrawn to (decision 2026-11-18). A destination is one address on one chain, added by
 * an ADMIN with a label; it can be withdrawn to only once the delay has passed (`fee_wallet_settings`, 24 hours unless
 * changed), so an address added by mistake or by someone who shouldn't have can be seen and removed first. Removing
 * and adding it again starts the wait again.
 */
class FeeDestinations(
    private val destinations: FeeDestinationRepository,
    private val wallets: FeeWalletRepository,
    private val networks: Networks,
    private val custody: StablecoinCustody,
    private val clock: Clock,
) {
    suspend fun delay(): Duration = Duration.ofHours(wallets.destinationDelayHours().toLong())

    suspend fun list(network: Network?): List<FeeDestination> = destinations.live(network)

    /** One destination, removed or not. */
    suspend fun find(id: UUID): FeeDestination? = destinations.find(id)

    suspend fun add(
        network: Network,
        blockchain: String,
        address: String,
        label: String,
        by: FeeStaff,
    ): Resource<FeeDestination> {
        val chain = blockchain.trim().uppercase()
        val name = label.trim()
        val given = address.trim()
        if (name.length !in 1..LABEL_LENGTH) return invalid("label", "must be 1 to $LABEL_LENGTH characters")
        if (chain.isEmpty() ||
            Network.ofChain(chain) != network
        ) {
            return invalid("blockchain", "isn't a chain on $network")
        }
        val known = networks.settings(network).chains.any { it.blockchain == chain }
        if (!known || !FeeChains.canCarryFee(chain)) {
            return invalid("blockchain", "isn't a chain fees are collected on")
        }
        if (!EVM_ADDRESS.matches(given)) return invalid("address", "isn't an address on $chain")
        // The wallet's own check (W11): Circle says whether the address is valid on that chain, on that network.
        when (val checked = withContext(WalletNetwork(network)) { custody.validateAddress(chain, given) }) {
            is Resource.Error -> return Resource.error(FeeWalletError.CustodyUnavailable, checked.cause)
            is Resource.Success -> if (!checked.data) return invalid("address", "isn't an address on $chain")
        }
        val normal = Addresses.normalise(given)
        if (isOwn(normal)) return Resource.error(FeeWalletError.DestinationIsFeeWallet)
        val destination =
            FeeDestination(
                id = Uuid7.generate(clock.instant()),
                blockchain = chain,
                address = normal,
                label = name,
                createdBy = by.staffId,
                createdByEmail = by.email,
                createdAt = clock.instant(),
            )
        if (!destinations.add(destination)) return Resource.error(FeeWalletError.DestinationExists)
        return Resource.success(destination)
    }

    /** The destination as it was, now removed. */
    suspend fun remove(id: UUID, by: FeeStaff): Resource<FeeDestination> {
        val found = destinations.find(id)?.takeIf { it.removedAt == null }
        if (found == null || !destinations.remove(id, by.staffId, clock.instant())) {
            return Resource.error(FeeWalletError.DestinationNotFound)
        }
        return Resource.success(found)
    }

    /**
     * Whether [address] is one of Circaso's own fee wallets, on any chain of either network: an EVM address is the
     * same account everywhere, so a fee wallet's address is refused on every chain.
     */
    suspend fun isOwn(address: String): Boolean {
        val normal = Addresses.normalise(address)
        if (wallets.feeAddresses().values.any { Addresses.normalise(it) == normal }) return true
        return Network.entries.any { network -> wallets.wallets(network).any { it.address == normal } }
    }

    private fun <T> invalid(field: String, problem: String): Resource<T> =
        Resource.error(FeeWalletError.Invalid(field, problem))

    companion object {
        const val LABEL_LENGTH = 80
        private val EVM_ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    }
}
