package com.circaso.wallet.domain.repositories

import com.circaso.shared.Network
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.ChainFee
import com.circaso.wallet.domain.models.NetworkGrant
import com.circaso.wallet.domain.models.NetworkSettings
import com.circaso.wallet.domain.models.NetworkSnapshot
import java.time.Instant
import java.util.UUID

/** `network_settings`, `network_chains`, `network_assets` and `chain_fees`: the settings that live in the database. */
interface NetworkSettingsRepository {
    /** Every network that has a row, with its chains and assets in order, and every chain's fee settings. */
    suspend fun load(): NetworkSnapshot

    /**
     * Writes [settings] and [fees] only when the network has no row yet (the import-once seed); true if this call
     * wrote them. Two servers starting at once settle on the primary key: one writes, the other writes nothing.
     */
    suspend fun seed(
        settings: NetworkSettings,
        fees: List<ChainFee>,
        at: Instant,
    ): Boolean

    /**
     * Replaces the network's settings with [settings]: its row and every chain and asset under it, the version one
     * higher; the settings as stored. The caller holds the transaction and has locked the row ([lock]). A chain's fee
     * settings are left alone.
     */
    suspend fun replace(
        settings: NetworkSettings,
        by: UUID,
        at: Instant,
    ): NetworkSettings

    /** Locks [network]'s row for the open transaction, so two changes at once apply one after the other. */
    suspend fun lock(network: Network)

    /** The chains of [network] that some wallet is on: they may be retired, never removed or disabled. */
    suspend fun chainsInUse(network: Network): Set<String>
}

/** `network_access`: who may use a network beyond the general rule. */
interface NetworkAccessRepository {
    suspend fun grants(userId: UserId): List<NetworkGrant>

    /** Inserts [grant] unless the person already has a row for that network; true if this call inserted it. */
    suspend fun grant(grant: NetworkGrant): Boolean

    /** Removes the person's row for [network]; true if there was one. Never touches a wallet or its funds. */
    suspend fun revoke(userId: UserId, network: Network): Boolean
}
