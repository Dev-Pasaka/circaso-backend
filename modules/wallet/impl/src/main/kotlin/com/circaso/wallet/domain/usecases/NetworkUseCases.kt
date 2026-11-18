package com.circaso.wallet.domain.usecases

import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.WalletNetwork
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.AccessSource
import com.circaso.wallet.domain.models.ChainSettings
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.NetworkGrant
import com.circaso.wallet.domain.models.NetworkOption
import com.circaso.wallet.domain.models.NetworkOptions
import com.circaso.wallet.domain.models.NetworkSettings
import com.circaso.wallet.domain.models.NetworkState
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.NetworkAccessRepository
import com.circaso.wallet.domain.repositories.NetworkSettingsRepository
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.NetworkAccess
import com.circaso.wallet.domain.support.Networks
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID

/**
 * `GET /wallet/networks` (decision 2026-11-18): the networks the caller may use, mainnet first, each with where their
 * wallet on it stands (from our rows, never Circle), and the one the app opens on: mainnet when they may use it, else
 * testnet. The app shows its Mainnet | Testnet switch only when there is more than one.
 */
class ListNetworks(private val access: NetworkAccess, private val wallets: WalletRepository) {
    suspend operator fun invoke(userId: UserId): Resource<NetworkOptions> {
        val usable = access.usable(userId)
        val options =
            usable.map { network ->
                NetworkOption(network, withContext(WalletNetwork(network)) { status(userId) })
            }
        val default = if (Network.MAINNET in usable) Network.MAINNET else Network.TESTNET
        return Resource.success(NetworkOptions(options, default))
    }

    private suspend fun status(userId: UserId): WalletStatus {
        wallets.circleUser(userId) ?: return WalletStatus.NONE
        val open = wallets.wallets(userId).any { it.state != ChainWalletState.CLOSED }
        return if (open) WalletStatus.ACTIVE else WalletStatus.SETTING_UP
    }
}

/** Staff: a person's access rows. */
class GetNetworkAccess(private val grants: NetworkAccessRepository) {
    suspend operator fun invoke(userId: UserId): List<NetworkGrant> = grants.grants(userId).sortedBy { it.network }
}

/**
 * Staff: grant or revoke a person's access row for a network. A grant over an existing row and a revoke of none
 * change nothing (and say so, so nothing is audited twice). A revoke only removes the row: wallets, funds and history
 * stay, and a later grant brings the same wallet back.
 */
class SetNetworkAccess(private val grants: NetworkAccessRepository, private val clock: Clock) {
    suspend operator fun invoke(
        userId: UserId,
        network: Network,
        enabled: Boolean,
        by: UUID,
        reason: String,
    ): Pair<List<NetworkGrant>, Boolean> {
        val changed =
            if (enabled) {
                grants.grant(NetworkGrant(userId, network, AccessSource.ADMIN, by, reason, clock.instant()))
            } else {
                grants.revoke(userId, network)
            }
        return grants.grants(userId).sortedBy { it.network } to changed
    }
}

/** Staff: every network's settings as the database has them now, mainnet first. */
class GetNetworkSettings(private val networks: Networks) {
    suspend operator fun invoke(): List<NetworkSettings> {
        networks.invalidate()
        return Network.entries.map { networks.settings(it).copy(custodyConfigured = networks.isReachable(it)) }
    }
}

/**
 * Staff: replace one network's settings (decision 2026-11-18), in one transaction under a lock on the network's row.
 * Refused with `wallet.network_settings_invalid` (the field and the problem in the details) unless: every chain's
 * name belongs to the network and is named once; each has an account type of `SCA` or `EOA` and, when enabled, at
 * least one asset with a symbol and 0 to 36 decimals; the default chain is one of them, enabled and receiving; every
 * chain a wallet is on is still there and enabled (it may be retired from receiving, never removed: decision
 * 2026-10-28); and a network that isn't `OFF` has an app id for the phone's SDK. With a version, a change that landed
 * since it was read answers `wallet.network_settings_changed`.
 */
class UpdateNetworkSettings(
    private val repository: NetworkSettingsRepository,
    private val networks: Networks,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        proposed: NetworkSettings,
        expectedVersion: Long?,
        by: UUID,
    ): Resource<Pair<NetworkSettings, NetworkSettings>> {
        // Makes sure the network has its row (the seed) before it is locked.
        networks.invalidate()
        networks.fresh()
        val cleaned = proposed.copy(appId = proposed.appId.trim(), chains = proposed.chains.map { it.cleaned() })
        shapeProblem(cleaned)?.let { return it }
        val result =
            transactor.attempt("wallet.update-network-settings") {
                repository.lock(cleaned.network)
                val before =
                    repository.load().settings[cleaned.network]
                        ?: return@attempt Resource.error(WalletError.NetworkSettingsChanged)
                if (expectedVersion != null && expectedVersion != before.version) {
                    return@attempt Resource.error(WalletError.NetworkSettingsChanged)
                }
                val kept =
                    cleaned.chains
                        .filter { it.enabled }
                        .map { it.blockchain }
                        .toSet()
                val orphaned = repository.chainsInUse(cleaned.network).firstOrNull { it !in kept }
                if (orphaned != null) {
                    return@attempt invalid(
                        "chains",
                        "$orphaned has wallets on it: retire it, don't remove or disable it",
                    )
                }
                val after = repository.replace(cleaned.copy(version = before.version), by, clock.instant())
                val reachable = networks.isReachable(cleaned.network)
                Resource.success(
                    before.copy(custodyConfigured = reachable) to after.copy(custodyConfigured = reachable),
                )
            }
        networks.invalidate()
        return result
    }

    private fun ChainSettings.cleaned() =
        copy(
            blockchain = blockchain.trim().uppercase(),
            accountType = accountType.trim().uppercase(),
            assets =
                assets.map {
                    it.copy(
                        symbol = it.symbol.trim().uppercase(),
                        tokenAddress = it.tokenAddress.trim(),
                    )
                },
        )

    private fun shapeProblem(settings: NetworkSettings): Resource.Error? {
        val names = settings.chains.map { it.blockchain }
        if (names.isEmpty()) return invalid("chains", "at least one chain is needed")
        names.firstOrNull { it.isBlank() }?.let { return invalid("chains", "a chain has no name") }
        names.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let {
            return invalid("chains", "${it.key} is listed twice")
        }
        names.firstOrNull { Network.ofChain(it) != settings.network }?.let {
            return invalid("chains", "$it is not a ${settings.network} chain")
        }
        settings.chains.firstNotNullOfOrNull { chainProblem(it) }?.let { return it }
        val default =
            settings.chains.firstOrNull { it.blockchain == settings.defaultChain.trim().uppercase() }
                ?: return invalid("defaultChain", "must be one of the chains")
        if (!default.enabled || !default.receiveEnabled) {
            return invalid("defaultChain", "the default chain must be enabled and receiving")
        }
        if (settings.state != NetworkState.OFF && settings.appId.isBlank()) {
            return invalid("appId", "a network that isn't OFF needs its app id")
        }
        return null
    }

    private fun chainProblem(chain: ChainSettings): Resource.Error? {
        if (chain.accountType !in
            ACCOUNT_TYPES
        ) {
            return invalid("accountType", "${chain.blockchain}: must be SCA or EOA")
        }
        if (chain.accountType == SCA && !custodyAllowsSca(chain.blockchain)) {
            return invalid("accountType", "${chain.blockchain}: Solana wallets are EOA")
        }
        if (chain.enabled && chain.assets.isEmpty()) return invalid("assets", "${chain.blockchain}: needs an asset")
        chain.assets.firstOrNull { it.symbol.isBlank() || it.decimals !in 0..MAX_DECIMALS }?.let {
            return invalid("assets", "${chain.blockchain}: an asset needs a symbol and 0 to $MAX_DECIMALS decimals")
        }
        chain.assets.groupBy { it.tokenAddress.lowercase() }.entries.firstOrNull { it.value.size > 1 }?.let {
            return invalid("assets", "${chain.blockchain}: a token is listed twice")
        }
        return null
    }

    private fun custodyAllowsSca(blockchain: String) = !blockchain.startsWith("SOL")

    private fun invalid(field: String, problem: String) =
        Resource.Error(WalletError.NetworkSettingsInvalid(field, problem))

    private companion object {
        const val SCA = "SCA"
        val ACCOUNT_TYPES = setOf(SCA, "EOA")
        const val MAX_DECIMALS = 36
    }
}
