package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.UserAccount
import com.circaso.ops.domain.usecases.GetWalletNetworks
import com.circaso.ops.domain.usecases.SetUserNetworkAccess
import com.circaso.ops.domain.usecases.UpdateWalletNetwork
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.OpsNetworkSettings
import com.circaso.wallet.api.OpsNetworkSettingsChange

/** Wallet networks for staff (decision 2026-11-18): a person's access, and each network's settings. */
class NetworkService(
    private val setUserNetworkAccess: SetUserNetworkAccess,
    private val getWalletNetworks: GetWalletNetworks,
    private val updateWalletNetwork: UpdateWalletNetwork,
) {
    suspend fun setAccess(
        actor: Actor,
        userId: UserId,
        network: Network,
        enabled: Boolean,
        reason: String,
    ): Resource<UserAccount> = setUserNetworkAccess(actor, userId, network, enabled, reason)

    suspend fun networks(): Resource<List<OpsNetworkSettings>> = getWalletNetworks()

    suspend fun update(
        actor: Actor,
        settings: OpsNetworkSettings,
        reason: String,
    ): Resource<OpsNetworkSettingsChange> = updateWalletNetwork(actor, settings, reason)
}
