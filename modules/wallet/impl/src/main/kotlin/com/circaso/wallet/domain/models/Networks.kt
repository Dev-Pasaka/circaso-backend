package com.circaso.wallet.domain.models

import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.CustodyChain
import com.circaso.integrations.api.CustodySetup
import com.circaso.integrations.api.SupportedChains
import com.circaso.shared.Network
import com.circaso.shared.UserId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Whether a network is open (decision 2026-11-18): `OFF` to nobody, `PREVIEW` only to people granted access to it
 * (staff trying it before launch), `ON` to everyone the access rule lets in.
 */
enum class NetworkState { OFF, PREVIEW, ON }

/**
 * One chain of a network as staff set it. [enabled] false takes it out of what is offered altogether (only while no
 * wallet is on it); [receiveEnabled] false retires it (decision 2026-10-28): readable, never added, nobody paid on it.
 */
data class ChainSettings(
    val blockchain: String,
    val enabled: Boolean,
    val receiveEnabled: Boolean,
    val accountType: String,
    val assets: List<CustodyAsset>,
)

/** A network's settings, as the `network_*` tables hold them. [version] rises by one on each change. */
data class NetworkSettings(
    val network: Network,
    val state: NetworkState,
    val appId: String,
    val defaultChain: String,
    val chains: List<ChainSettings>,
    val version: Long = 1,
) {
    /** What custody runs on: the enabled chains only. Null when they don't make a valid set (never, once validated). */
    fun toSetup(): CustodySetup? =
        runCatching {
            val offered = chains.filter { it.enabled }
            CustodySetup(
                appId = appId,
                supported =
                    SupportedChains(
                        default = defaultChain,
                        chains = offered.map { CustodyChain(it.blockchain, it.assets, it.receiveEnabled) },
                    ),
                accountTypes = offered.associate { it.blockchain to it.accountType },
            )
        }.getOrNull()
}

/**
 * Circaso's fee wallet on one chain and the fallback dollar price of the chain's own coin (`chain_fees`). No
 * [feeAddress]: no fee is taken there.
 */
data class ChainFee(val blockchain: String, val feeAddress: String?, val nativeUsd: BigDecimal?)

/** Everything read from the settings tables at one moment. */
data class NetworkSnapshot(val settings: Map<Network, NetworkSettings>, val fees: Map<String, ChainFee>)

/** How a person came to have access to a network. */
enum class AccessSource {
    /** They had a testnet wallet before networks existed (the V12 backfill). */
    GRANDFATHERED,

    /** A staff member granted it. */
    ADMIN,

    /** They created a testnet wallet while mainnet wasn't open yet, so they keep the testnet at launch. */
    AUTO,
}

/** A `network_access` row. [grantedBy] is the staff member for [AccessSource.ADMIN]. */
data class NetworkGrant(
    val userId: UserId,
    val network: Network,
    val source: AccessSource,
    val grantedBy: UUID?,
    val reason: String?,
    val createdAt: Instant,
)

/** One network a person may use, and where their wallet on it stands (`GET /wallet/networks`). */
data class NetworkOption(val network: Network, val walletStatus: WalletStatus)

/** The networks a person may use and the one the app opens on: mainnet when they may use it, else testnet. */
data class NetworkOptions(val networks: List<NetworkOption>, val default: Network)
