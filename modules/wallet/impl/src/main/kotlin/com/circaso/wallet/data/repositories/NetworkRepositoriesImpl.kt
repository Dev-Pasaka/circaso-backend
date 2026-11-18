package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.integrations.api.CustodyAsset
import com.circaso.shared.Network
import com.circaso.shared.UserId
import com.circaso.wallet.data.tables.ChainFeesTable
import com.circaso.wallet.data.tables.NetworkAccessTable
import com.circaso.wallet.data.tables.NetworkAssetsTable
import com.circaso.wallet.data.tables.NetworkChainsTable
import com.circaso.wallet.data.tables.NetworkSettingsTable
import com.circaso.wallet.data.tables.WalletsTable
import com.circaso.wallet.domain.models.AccessSource
import com.circaso.wallet.domain.models.ChainFee
import com.circaso.wallet.domain.models.ChainSettings
import com.circaso.wallet.domain.models.NetworkGrant
import com.circaso.wallet.domain.models.NetworkSettings
import com.circaso.wallet.domain.models.NetworkSnapshot
import com.circaso.wallet.domain.models.NetworkState
import com.circaso.wallet.domain.repositories.NetworkAccessRepository
import com.circaso.wallet.domain.repositories.NetworkSettingsRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant
import java.util.UUID

/**
 * The settings tables (V12). They are small (two networks, a handful of chains), so [load] reads them whole: four
 * primary-key-ordered reads in one transaction, cached above for a few seconds.
 */
class NetworkSettingsRepositoryImpl(private val database: Database) : NetworkSettingsRepository {
    override suspend fun load(): NetworkSnapshot =
        database.tx("network-settings.load") {
            val assets =
                NetworkAssetsTable
                    .selectAll()
                    .orderBy(
                        NetworkAssetsTable.blockchain to SortOrder.ASC,
                        NetworkAssetsTable.position to SortOrder.ASC,
                    ).map {
                        it[NetworkAssetsTable.blockchain] to
                            CustodyAsset(
                                symbol = it[NetworkAssetsTable.symbol],
                                tokenAddress = it[NetworkAssetsTable.tokenAddress],
                                decimals = it[NetworkAssetsTable.decimals].toInt(),
                                usdPegged = it[NetworkAssetsTable.usdPegged],
                            )
                    }.toList()
                    .groupBy({ it.first }, { it.second })
            val chains =
                NetworkChainsTable
                    .selectAll()
                    .orderBy(NetworkChainsTable.network to SortOrder.ASC, NetworkChainsTable.position to SortOrder.ASC)
                    .map {
                        val blockchain = it[NetworkChainsTable.blockchain]
                        it[NetworkChainsTable.network] to
                            ChainSettings(
                                blockchain = blockchain,
                                enabled = it[NetworkChainsTable.enabled],
                                receiveEnabled = it[NetworkChainsTable.receiveEnabled],
                                accountType = it[NetworkChainsTable.accountType],
                                assets = assets[blockchain].orEmpty(),
                            )
                    }.toList()
                    .groupBy({ it.first }, { it.second })
            val settings =
                NetworkSettingsTable
                    .selectAll()
                    .map {
                        val network = it[NetworkSettingsTable.network]
                        NetworkSettings(
                            network = Network.valueOf(network),
                            state = NetworkState.valueOf(it[NetworkSettingsTable.state]),
                            appId = it[NetworkSettingsTable.appId],
                            defaultChain = it[NetworkSettingsTable.defaultChain],
                            chains = chains[network].orEmpty(),
                            version = it[NetworkSettingsTable.version],
                        )
                    }.toList()
            val fees =
                ChainFeesTable
                    .selectAll()
                    .map {
                        ChainFee(
                            blockchain = it[ChainFeesTable.blockchain],
                            feeAddress = it[ChainFeesTable.feeAddress],
                            nativeUsd = it[ChainFeesTable.nativeUsd],
                        )
                    }.toList()
            NetworkSnapshot(settings.associateBy { it.network }, fees.associateBy { it.blockchain })
        }

    override suspend fun seed(
        settings: NetworkSettings,
        fees: List<ChainFee>,
        at: Instant,
    ): Boolean =
        database.tx("network-settings.seed") {
            val inserted =
                NetworkSettingsTable
                    .insertIgnore {
                        it[network] = settings.network.name
                        it[state] = settings.state.name
                        it[appId] = settings.appId
                        it[defaultChain] = settings.defaultChain
                        it[version] = settings.version
                        it[updatedAt] = at
                    }.insertedCount > 0
            if (inserted) {
                writeChains(settings)
                fees.forEach { fee ->
                    ChainFeesTable.insertIgnore {
                        it[blockchain] = fee.blockchain
                        it[feeAddress] = fee.feeAddress
                        it[nativeUsd] = fee.nativeUsd
                        it[updatedAt] = at
                    }
                }
            }
            inserted
        }

    override suspend fun replace(
        settings: NetworkSettings,
        by: UUID,
        at: Instant,
    ): NetworkSettings =
        database.tx("network-settings.replace") {
            val next = settings.version + 1
            NetworkSettingsTable.update({ NetworkSettingsTable.network eq settings.network.name }) {
                it[state] = settings.state.name
                it[appId] = settings.appId
                it[defaultChain] = settings.defaultChain
                it[version] = next
                it[updatedBy] = by
                it[updatedAt] = at
            }
            // The assets go with their chains (ON DELETE CASCADE).
            NetworkChainsTable.deleteWhere { NetworkChainsTable.network eq settings.network.name }
            writeChains(settings)
            settings.copy(version = next)
        }

    override suspend fun lock(network: Network) {
        database.tx("network-settings.lock") {
            NetworkSettingsTable
                .select(NetworkSettingsTable.network)
                .where { NetworkSettingsTable.network eq network.name }
                .forUpdate(ForUpdateOption.ForUpdate)
                .toList()
        }
    }

    override suspend fun chainsInUse(network: Network): Set<String> =
        database.tx("network-settings.chains-in-use") {
            WalletsTable
                .select(WalletsTable.blockchain)
                .where { WalletsTable.network eq network.name }
                .withDistinct()
                .map { it[WalletsTable.blockchain] }
                .toList()
                .toSet()
        }

    private suspend fun writeChains(settings: NetworkSettings) {
        settings.chains.forEachIndexed { index, chain ->
            NetworkChainsTable.insert {
                it[blockchain] = chain.blockchain
                it[network] = settings.network.name
                it[position] = index
                it[enabled] = chain.enabled
                it[receiveEnabled] = chain.receiveEnabled
                it[accountType] = chain.accountType
            }
            chain.assets.forEachIndexed { position, asset ->
                NetworkAssetsTable.insert {
                    it[blockchain] = chain.blockchain
                    it[this.position] = position
                    it[symbol] = asset.symbol
                    it[tokenAddress] = asset.tokenAddress
                    it[decimals] = asset.decimals.toShort()
                    it[usdPegged] = asset.usdPegged
                }
            }
        }
    }
}

/** `network_access` (V12), by its primary key. */
class NetworkAccessRepositoryImpl(private val database: Database) : NetworkAccessRepository {
    override suspend fun grants(userId: UserId): List<NetworkGrant> =
        database.tx("network-access.of-user") {
            NetworkAccessTable
                .selectAll()
                .where { NetworkAccessTable.userId eq userId.value }
                .map {
                    NetworkGrant(
                        userId = UserId(it[NetworkAccessTable.userId]),
                        network = Network.valueOf(it[NetworkAccessTable.network]),
                        source = AccessSource.valueOf(it[NetworkAccessTable.grantSource]),
                        grantedBy = it[NetworkAccessTable.grantedBy],
                        reason = it[NetworkAccessTable.reason],
                        createdAt = it[NetworkAccessTable.createdAt],
                    )
                }.toList()
        }

    override suspend fun grant(grant: NetworkGrant): Boolean =
        database.tx("network-access.grant") {
            NetworkAccessTable
                .insertIgnore {
                    it[userId] = grant.userId.value
                    it[network] = grant.network.name
                    it[grantSource] = grant.source.name
                    it[grantedBy] = grant.grantedBy
                    it[reason] = grant.reason
                    it[createdAt] = grant.createdAt
                }.insertedCount > 0
        }

    override suspend fun revoke(userId: UserId, network: Network): Boolean =
        database.tx("network-access.revoke") {
            NetworkAccessTable.deleteWhere {
                (NetworkAccessTable.userId eq userId.value) and (NetworkAccessTable.network eq network.name)
            } > 0
        }
}
