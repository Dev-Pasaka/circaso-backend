package com.circaso.wallet.domain.support

import com.circaso.shared.FieldError
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.WalletNetworkGate
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.AccessSource
import com.circaso.wallet.domain.models.NetworkGrant
import com.circaso.wallet.domain.models.NetworkState
import com.circaso.wallet.domain.repositories.NetworkAccessRepository
import java.time.Clock

/**
 * Who may use which network (decision 2026-11-18).
 *
 * - **Mainnet**: everyone once it is `ON`; while it is `PREVIEW`, only people with a mainnet access row (staff trying
 *   it before launch); nobody while it is `OFF` or custody has no mainnet key.
 * - **Testnet**: while it is `ON`, people with a testnet access row (they had a testnet wallet, or staff granted it),
 *   and, **until mainnet is `ON`, everyone**: before launch the testnet is all there is, so nothing changes for
 *   anyone. While it is `PREVIEW`, only people with a row.
 *
 * A person who creates a testnet wallet before launch gets a testnet row ([rememberTestnet]), so they keep the testnet
 * and its Mainnet | Testnet switch when mainnet opens; someone who joins after launch has no row and is mainnet only.
 */
class NetworkAccess(
    private val networks: Networks,
    private val grants: NetworkAccessRepository,
    private val clock: Clock,
) : WalletNetworkGate {
    /** The networks [userId] may use now, mainnet first. */
    suspend fun usable(userId: UserId): List<Network> {
        val states = Network.entries.associateWith { networks.state(it) }
        if (states.values.all { it == NetworkState.OFF }) return emptyList()
        val rows = lazyRows(userId)
        return Network.entries.filter { allowed(it, states, rows) }
    }

    suspend fun mayUse(userId: UserId, network: Network): Boolean {
        val states = Network.entries.associateWith { networks.state(it) }
        return allowed(network, states, lazyRows(userId))
    }

    /**
     * The network of a request: the one named in `X-Wallet-Network` when the person may use it, else
     * `wallet.network_unavailable`. An app from before networks sends no header: testnet when the person may use it,
     * else mainnet when they may, else testnet as it always was.
     */
    override suspend fun resolve(userId: UserId, requested: String?): Resource<Network> {
        val named = requested?.trim()?.takeIf { it.isNotEmpty() }
        if (named == null) {
            val usable = usable(userId)
            return Resource.success(
                when {
                    Network.TESTNET in usable -> Network.TESTNET
                    Network.MAINNET in usable -> Network.MAINNET
                    else -> Network.TESTNET
                },
            )
        }
        val network =
            Network.parse(named)
                ?: return Resource.error(SharedError.Malformed(listOf(FieldError(HEADER, "must be MAINNET or TESTNET"))))
        return if (mayUse(userId, network)) Resource.success(network) else Resource.error(WalletError.NetworkUnavailable)
    }

    /**
     * Called when a person starts a wallet on [network]: on the testnet before mainnet is `ON`, they are given a
     * testnet row (`AUTO`) so the testnet stays theirs after launch. Anything else changes nothing.
     */
    suspend fun rememberTestnet(userId: UserId, network: Network) {
        if (network != Network.TESTNET || networks.state(Network.MAINNET) == NetworkState.ON) return
        grants.grant(NetworkGrant(userId, Network.TESTNET, AccessSource.AUTO, null, null, clock.instant()))
    }

    private suspend fun allowed(
        network: Network,
        states: Map<Network, NetworkState>,
        rows: Rows,
    ): Boolean =
        when (states.getValue(network)) {
            NetworkState.OFF -> false
            NetworkState.PREVIEW -> network in rows.load()
            NetworkState.ON ->
                when (network) {
                    Network.MAINNET -> true
                    Network.TESTNET -> states.getValue(Network.MAINNET) != NetworkState.ON || network in rows.load()
                }
        }

    /** The person's access rows, read only when a rule needs them: before launch no testnet request does. */
    private fun lazyRows(userId: UserId): Rows = Rows { grants.grants(userId).map { it.network }.toSet() }

    private class Rows(private val read: suspend () -> Set<Network>) {
        private var value: Set<Network>? = null

        suspend fun load(): Set<Network> = value ?: read().also { value = it }
    }

    private companion object {
        const val HEADER = "X-Wallet-Network"
    }
}
