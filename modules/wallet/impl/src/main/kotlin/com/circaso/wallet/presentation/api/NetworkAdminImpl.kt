package com.circaso.wallet.presentation.api

import com.circaso.integrations.api.CustodyAsset
import com.circaso.shared.FieldError
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.map
import com.circaso.wallet.api.NetworkAdmin
import com.circaso.wallet.api.OpsNetworkAccess
import com.circaso.wallet.api.OpsNetworkAccessChange
import com.circaso.wallet.api.OpsNetworkAsset
import com.circaso.wallet.api.OpsNetworkChain
import com.circaso.wallet.api.OpsNetworkSettings
import com.circaso.wallet.api.OpsNetworkSettingsChange
import com.circaso.wallet.domain.models.ChainSettings
import com.circaso.wallet.domain.models.NetworkGrant
import com.circaso.wallet.domain.models.NetworkSettings
import com.circaso.wallet.domain.models.NetworkState
import com.circaso.wallet.presentation.services.NetworkService
import java.util.UUID

/**
 * wallet:api's staff contract for networks (decision 2026-11-18), bound for the ops module only: the app gateway
 * never mounts it. Every call goes through [NetworkService].
 */
class NetworkAdminImpl(private val networks: NetworkService) : NetworkAdmin {
    override suspend fun access(userId: UserId): List<OpsNetworkAccess> = networks.access(userId).map { it.toOps() }

    override suspend fun setAccess(
        userId: UserId,
        network: Network,
        enabled: Boolean,
        by: UUID,
        reason: String,
    ): OpsNetworkAccessChange {
        val (rows, changed) = networks.setAccess(userId, network, enabled, by, reason)
        return OpsNetworkAccessChange(rows.map { it.toOps() }, changed)
    }

    override suspend fun settings(): List<OpsNetworkSettings> = networks.settings().map { it.toOps() }

    override suspend fun updateSettings(settings: OpsNetworkSettings, by: UUID): Resource<OpsNetworkSettingsChange> {
        val state =
            NetworkState.entries.firstOrNull { it.name == settings.state.trim().uppercase() }
                ?: return Resource.error(
                    SharedError.Malformed(listOf(FieldError("state", "must be OFF, PREVIEW or ON"))),
                )
        val proposed =
            NetworkSettings(
                network = settings.network,
                state = state,
                appId = settings.appId,
                defaultChain = settings.defaultChain.trim().uppercase(),
                chains = settings.chains.map { it.toDomain() },
            )
        return networks
            .updateSettings(proposed, settings.version, by)
            .map { (before, after) -> OpsNetworkSettingsChange(before.toOps(), after.toOps()) }
    }

    private fun NetworkGrant.toOps() = OpsNetworkAccess(network, source.name, grantedBy, reason, createdAt)

    private fun NetworkSettings.toOps() =
        OpsNetworkSettings(
            network = network,
            state = state.name,
            appId = appId,
            defaultChain = defaultChain,
            chains =
                chains.map { chain ->
                    OpsNetworkChain(
                        blockchain = chain.blockchain,
                        enabled = chain.enabled,
                        receiveEnabled = chain.receiveEnabled,
                        accountType = chain.accountType,
                        assets =
                            chain.assets.map {
                                OpsNetworkAsset(
                                    it.symbol,
                                    it.tokenAddress,
                                    it.decimals,
                                    it.usdPegged,
                                )
                            },
                    )
                },
            version = version,
            custodyConfigured = networks.custodyConfigured(network),
        )

    private fun OpsNetworkChain.toDomain() =
        ChainSettings(
            blockchain = blockchain,
            enabled = enabled,
            receiveEnabled = receiveEnabled,
            accountType = accountType,
            assets = assets.map { CustodyAsset(it.symbol, it.tokenAddress, it.decimals, it.usd) },
        )
}
