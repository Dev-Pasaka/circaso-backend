package com.circaso.wallet.api

import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

// The wallet's staff contract for networks (decision 2026-11-18), for the ops module behind the ops gateway only:
// who may use which network, and each network's settings, which live in the database instead of the environment.

/** A person's access row for a network. [source] is `GRANDFATHERED`, `ADMIN` or `AUTO`. */
data class OpsNetworkAccess(
    val network: Network,
    val source: String,
    val grantedBy: UUID?,
    val reason: String?,
    val at: Instant,
)

data class OpsNetworkAsset(
    val symbol: String,
    val tokenAddress: String,
    val decimals: Int,
    val usd: Boolean,
)

data class OpsNetworkChain(
    val blockchain: String,
    val enabled: Boolean,
    val receiveEnabled: Boolean,
    val accountType: String,
    val assets: List<OpsNetworkAsset>,
)

/**
 * One network's settings. [state] is `OFF`, `PREVIEW` or `ON`. [version] rises on each change: sent back with an
 * update, a change made meanwhile is refused instead of overwritten (null: save over whatever is there).
 * [custodyConfigured] says whether this server holds the network's custody key; without it the network is off
 * whatever [state] says.
 */
data class OpsNetworkSettings(
    val network: Network,
    val state: String,
    val appId: String,
    val defaultChain: String,
    val chains: List<OpsNetworkChain>,
    val version: Long? = null,
    val custodyConfigured: Boolean = true,
)

data class OpsNetworkSettingsChange(val before: OpsNetworkSettings, val after: OpsNetworkSettings)

/** What a grant or a revoke did: the person's rows afterwards, and whether anything changed. */
data class OpsNetworkAccessChange(val access: List<OpsNetworkAccess>, val changed: Boolean)

/**
 * Staff's view of networks. Errors: `wallet.network_settings_invalid` (400, the field named in the details) and
 * `wallet.network_settings_changed` (409).
 */
interface NetworkAdmin {
    suspend fun access(userId: UserId): List<OpsNetworkAccess>

    /**
     * Grants ([enabled]) or revokes a person's access row for [network]. Revoking the testnet takes away the app's
     * Mainnet | Testnet switch once mainnet is open; it never deletes a wallet or its funds, and granting it back
     * brings the same wallet back.
     */
    suspend fun setAccess(
        userId: UserId,
        network: Network,
        enabled: Boolean,
        by: UUID,
        reason: String,
    ): OpsNetworkAccessChange

    suspend fun settings(): List<OpsNetworkSettings>

    /**
     * Replaces one network's settings. The default chain must be enabled and receiving; a chain some wallet is on may
     * be retired (`receiveEnabled = false`) but not removed or disabled; a chain's name must belong to the network.
     */
    suspend fun updateSettings(settings: OpsNetworkSettings, by: UUID): Resource<OpsNetworkSettingsChange>
}
