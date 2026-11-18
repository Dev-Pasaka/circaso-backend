package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CustodySeeds
import com.circaso.integrations.api.CustodySetup
import com.circaso.integrations.api.CustodySetups
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Network
import com.circaso.wallet.domain.models.ChainFee
import com.circaso.wallet.domain.models.ChainSettings
import com.circaso.wallet.domain.models.NetworkSettings
import com.circaso.wallet.domain.models.NetworkSnapshot
import com.circaso.wallet.domain.models.NetworkState
import com.circaso.wallet.domain.ports.FeeConfig
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.NetworkSettingsRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * The network settings in force (decision 2026-11-18: the environment holds keys and secrets only; the state, app id,
 * chains, assets and fee wallets live in the database, where staff edit them). Read from the tables at most once
 * every [maxAge] per process, so the app and the ops servers agree within seconds of a change; a network with no row
 * yet is seeded once from the built-in defaults and the old variables ([seeds], [seedFees]), and after that the
 * database wins.
 *
 * Custody reads the same settings through [CustodySetups]: until the first read they are the seeds, which is what the
 * first read writes, so nothing changes at the moment the tables take over.
 */
class Networks(
    private val repository: NetworkSettingsRepository,
    private val seeds: CustodySeeds,
    /** The old `WALLET_FEES_CHAIN_<CHAIN>_*` settings, read once for the seed. */
    private val seedFees: FeeConfig,
    private val custody: StablecoinCustody,
    private val warnings: WalletWarnings,
    private val clock: Clock,
    private val maxAge: Duration = MAX_AGE,
) : CustodySetups {
    private class Loaded(val snapshot: NetworkSnapshot, val at: Instant)

    @Volatile
    private var loaded: Loaded? = null

    /** The settings as the database has them now (at most [maxAge] old); the seeds when it can't be read at all. */
    suspend fun fresh(): NetworkSnapshot {
        val current = loaded
        if (current != null && clock.instant().isBefore(current.at.plus(maxAge))) return current.snapshot
        return try {
            read().also { loaded = Loaded(it, clock.instant()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The settings we had stay in force: a database hiccup must not close a network or change its chains.
            warnings.networkSettingsUnread(e::class.simpleName.orEmpty())
            current?.snapshot ?: defaults()
        }
    }

    /** Forgets what was read, so the next [fresh] reads the tables (after a change made in this process). */
    fun invalidate() {
        loaded = null
    }

    /** [network]'s settings now. */
    suspend fun settings(network: Network): NetworkSettings = fresh().settings[network] ?: seedOf(network)

    /**
     * Whether [network] is open, and to whom. A network custody can't reach (mainnet without its API key) is `OFF`
     * whatever its row says.
     */
    suspend fun state(network: Network): NetworkState =
        if (network in custody.networks) settings(network).state else NetworkState.OFF

    override fun current(network: Network): CustodySetup? = loaded?.snapshot?.settings?.get(network)?.toSetup()

    /** The chain's fee settings as last read; null before the first read or when it has none. */
    fun feeOf(blockchain: String): ChainFee? = loaded?.snapshot?.fees?.get(blockchain)

    /** Whether the tables have been read at least once in this process. */
    val isLoaded: Boolean get() = loaded != null

    private suspend fun read(): NetworkSnapshot {
        val snapshot = repository.load()
        val missing = Network.entries.filter { it !in snapshot.settings }
        if (missing.isEmpty()) return snapshot
        missing.forEach { network ->
            val seed = seedOf(network)
            repository.seed(seed, seed.chains.map { feeSeed(it.blockchain) }, clock.instant())
        }
        return repository.load()
    }

    private fun defaults() = NetworkSnapshot(Network.entries.associateWith { seedOf(it) }, emptyMap())

    /** What a network starts as: testnet open as it always was, mainnet off until staff turn it on. */
    private fun seedOf(network: Network): NetworkSettings {
        val setup = seeds.seed(network)
        return NetworkSettings(
            network = network,
            state = if (network == Network.TESTNET) NetworkState.ON else NetworkState.OFF,
            appId = setup.appId,
            defaultChain = setup.supported.default,
            chains =
                setup.supported.chains.map {
                    ChainSettings(
                        blockchain = it.blockchain,
                        enabled = true,
                        receiveEnabled = it.receiveEnabled,
                        accountType = setup.accountTypes[it.blockchain] ?: defaultAccountType(it.blockchain),
                        assets = it.assets,
                    )
                },
        )
    }

    private fun feeSeed(blockchain: String) =
        ChainFee(blockchain, seedFees.feeAddress(blockchain), seedFees.configuredNativeUsd(blockchain))

    companion object {
        val MAX_AGE: Duration = Duration.ofSeconds(10)

        /** Solana has no smart-contract accounts; every EVM chain uses them so Gas Station can pay. */
        fun defaultAccountType(blockchain: String): String = if (blockchain.startsWith("SOL")) "EOA" else "SCA"
    }
}
