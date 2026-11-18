package com.circaso.ops.presentation.mappers

import com.circaso.ops.presentation.dto.NetworkAccessDto
import com.circaso.ops.presentation.dto.NetworkAssetDto
import com.circaso.ops.presentation.dto.NetworkChainDto
import com.circaso.ops.presentation.dto.UpdateWalletNetworkRequest
import com.circaso.ops.presentation.dto.WalletNetworkDto
import com.circaso.shared.Network
import com.circaso.wallet.api.OpsNetworkAccess
import com.circaso.wallet.api.OpsNetworkAsset
import com.circaso.wallet.api.OpsNetworkChain
import com.circaso.wallet.api.OpsNetworkSettings

const val TAG_NETWORKS = "Ops · Wallet networks"

fun OpsNetworkAccess.toDto() = NetworkAccessDto(network.name, source, grantedBy?.toString(), reason, at.toString())

fun OpsNetworkSettings.toDto() =
    WalletNetworkDto(
        network = network.name,
        state = state,
        appId = appId,
        defaultChain = defaultChain,
        chains =
            chains.map { chain ->
                NetworkChainDto(
                    blockchain = chain.blockchain,
                    enabled = chain.enabled,
                    receiveEnabled = chain.receiveEnabled,
                    accountType = chain.accountType,
                    assets = chain.assets.map { NetworkAssetDto(it.symbol, it.tokenAddress, it.decimals, it.usd) },
                )
            },
        version = version,
        custodyConfigured = custodyConfigured,
    )

fun UpdateWalletNetworkRequest.toSettings(network: Network) =
    OpsNetworkSettings(
        network = network,
        state = state,
        appId = appId,
        defaultChain = defaultChain,
        chains =
            chains.map { chain ->
                val name = chain.blockchain.trim().uppercase()
                OpsNetworkChain(
                    blockchain = name,
                    enabled = chain.enabled,
                    receiveEnabled = chain.receiveEnabled,
                    accountType = chain.accountType ?: if (name.startsWith("SOL")) "EOA" else "SCA",
                    assets =
                        chain.assets.map { OpsNetworkAsset(it.symbol, it.tokenAddress.orEmpty(), it.decimals, it.usd) },
                )
            },
        version = version,
    )
