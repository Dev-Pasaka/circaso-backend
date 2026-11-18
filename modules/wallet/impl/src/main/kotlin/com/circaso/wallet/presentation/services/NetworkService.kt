package com.circaso.wallet.presentation.services

import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.NetworkGrant
import com.circaso.wallet.domain.models.NetworkOptions
import com.circaso.wallet.domain.models.NetworkSettings
import com.circaso.wallet.domain.usecases.GetNetworkAccess
import com.circaso.wallet.domain.usecases.GetNetworkSettings
import com.circaso.wallet.domain.usecases.ListNetworks
import com.circaso.wallet.domain.usecases.SetNetworkAccess
import com.circaso.wallet.domain.usecases.UpdateNetworkSettings
import java.util.UUID

/** Networks (decision 2026-11-18): the app's list of the ones a person may use, and staff's access rows and settings. */
class NetworkService(
    private val listNetworks: ListNetworks,
    private val getNetworkAccess: GetNetworkAccess,
    private val setNetworkAccess: SetNetworkAccess,
    private val getNetworkSettings: GetNetworkSettings,
    private val updateNetworkSettings: UpdateNetworkSettings,
) {
    suspend fun networks(userId: UserId): Resource<NetworkOptions> = listNetworks(userId)

    suspend fun access(userId: UserId): List<NetworkGrant> = getNetworkAccess(userId)

    suspend fun setAccess(
        userId: UserId,
        network: Network,
        enabled: Boolean,
        by: UUID,
        reason: String,
    ): Pair<List<NetworkGrant>, Boolean> = setNetworkAccess(userId, network, enabled, by, reason)

    suspend fun settings(): List<NetworkSettings> = getNetworkSettings()

    suspend fun updateSettings(
        settings: NetworkSettings,
        expectedVersion: Long?,
        by: UUID,
    ): Resource<Pair<NetworkSettings, NetworkSettings>> = updateNetworkSettings(settings, expectedVersion, by)

    fun custodyConfigured(network: Network): Boolean = updateNetworkSettings.custodyConfigured(network)
}
