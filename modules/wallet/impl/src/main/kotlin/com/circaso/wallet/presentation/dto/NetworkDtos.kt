package com.circaso.wallet.presentation.dto

import kotlinx.serialization.Serializable

/** W20 `GET /wallet/networks`. */
@Serializable
data class NetworksResponse(
    /** Only the networks you may use, mainnet first. */
    val networks: List<NetworkOptionDto>,
    /** `MAINNET` when you may use it, else `TESTNET`. */
    val default: String,
)

@Serializable
data class NetworkOptionDto(
    /** `MAINNET` or `TESTNET`. */
    val network: String,
    /** `NONE`, `SETTING_UP` or `ACTIVE`: your wallet on this network. */
    val walletStatus: String,
)
