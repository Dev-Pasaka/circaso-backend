package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

/** A person's access row for a wallet network. `source`: GRANDFATHERED | ADMIN | AUTO. */
@Serializable
data class NetworkAccessDto(
    val network: String,
    val source: String,
    /** The staff member's id, for ADMIN. */
    val grantedBy: String? = null,
    val reason: String? = null,
    val at: String,
)

/** `POST /ops/users/{userId}/network-access`. */
@Serializable
data class NetworkAccessRequest(val network: String, val enabled: Boolean, val reason: String = "")

@Serializable
data class NetworkAssetDto(
    val symbol: String,
    /** The token's contract or mint; null or empty for the chain's own coin. */
    val tokenAddress: String? = null,
    val decimals: Int,
    /** True for a stablecoin worth one US dollar. */
    val usd: Boolean = false,
)

@Serializable
data class NetworkChainDto(
    val blockchain: String,
    val enabled: Boolean = true,
    val receiveEnabled: Boolean = true,
    /** SCA | EOA; left out, EOA on Solana and SCA elsewhere. */
    val accountType: String? = null,
    val assets: List<NetworkAssetDto> = emptyList(),
)

/**
 * One network's settings. `state`: OFF | PREVIEW | ON. `version` rises on each change; sent back on an update, a
 * change made meanwhile answers `wallet.network_settings_changed` instead of being overwritten. `custodyConfigured`
 * (read-only) is false when this server has no custody key for the network: it is then off whatever `state` says.
 */
@Serializable
data class WalletNetworkDto(
    val network: String,
    val state: String,
    val appId: String = "",
    val defaultChain: String,
    val chains: List<NetworkChainDto>,
    val version: Long? = null,
    val custodyConfigured: Boolean = true,
)

@Serializable
data class WalletNetworksResponse(val networks: List<WalletNetworkDto>)

/** `PUT /ops/wallet/networks/{network}`: the network's settings and why they change. */
@Serializable
data class UpdateWalletNetworkRequest(
    val state: String,
    val appId: String = "",
    val defaultChain: String,
    val chains: List<NetworkChainDto>,
    val version: Long? = null,
    val reason: String = "",
    /** Ignored when sent (the path names the network); accepted so the object read can be sent back whole. */
    val network: String? = null,
    val custodyConfigured: Boolean? = null,
)
