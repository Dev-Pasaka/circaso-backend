package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.Network
import com.circaso.wallet.data.tables.ChainFeesTable
import com.circaso.wallet.data.tables.FeeDestinationsTable
import com.circaso.wallet.data.tables.FeeWalletNetworksTable
import com.circaso.wallet.data.tables.FeeWalletSettingsTable
import com.circaso.wallet.data.tables.FeeWalletsTable
import com.circaso.wallet.data.tables.FeeWithdrawalsTable
import com.circaso.wallet.domain.models.FeeDestination
import com.circaso.wallet.domain.models.FeeWallet
import com.circaso.wallet.domain.models.FeeWalletNetwork
import com.circaso.wallet.domain.models.FeeWalletOrigin
import com.circaso.wallet.domain.models.FeeWalletStatus
import com.circaso.wallet.domain.models.FeeWithdrawal
import com.circaso.wallet.domain.models.WithdrawalChange
import com.circaso.wallet.domain.models.WithdrawalState
import com.circaso.wallet.domain.repositories.FeeDestinationRepository
import com.circaso.wallet.domain.repositories.FeeWalletRepository
import com.circaso.wallet.domain.repositories.FeeWithdrawalRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant
import java.util.UUID

private const val DEFAULT_DELAY_HOURS = 24
private const val SETTINGS_ROW: Short = 1

/** The fee wallets' rows (V13). Small tables: a handful of chains on two networks. */
class FeeWalletRepositoryImpl(private val database: Database) : FeeWalletRepository {
    override suspend fun network(network: Network): FeeWalletNetwork? =
        database.tx("fee-wallets.network") {
            FeeWalletNetworksTable
                .selectAll()
                .where { FeeWalletNetworksTable.network eq network.name }
                .map {
                    FeeWalletNetwork(
                        network = network,
                        walletSetId = it[FeeWalletNetworksTable.walletSetId],
                        status = FeeWalletStatus.valueOf(it[FeeWalletNetworksTable.status]),
                        reason = it[FeeWalletNetworksTable.reason],
                        updatedAt = it[FeeWalletNetworksTable.updatedAt],
                    )
                }.singleOrNull()
        }

    override suspend fun saveNetwork(row: FeeWalletNetwork) {
        database.tx("fee-wallets.save-network") {
            val inserted =
                FeeWalletNetworksTable
                    .insertIgnore {
                        it[network] = row.network.name
                        it[walletSetId] = row.walletSetId
                        it[status] = row.status.name
                        it[reason] = row.reason
                        it[updatedAt] = row.updatedAt
                    }.insertedCount
            if (inserted == 0) {
                FeeWalletNetworksTable.update({ FeeWalletNetworksTable.network eq row.network.name }) {
                    row.walletSetId?.let { id -> it[walletSetId] = id }
                    it[status] = row.status.name
                    it[reason] = row.reason
                    it[updatedAt] = row.updatedAt
                }
            }
        }
    }

    override suspend fun wallets(network: Network): List<FeeWallet> =
        database.tx("fee-wallets.wallets") {
            FeeWalletsTable
                .selectAll()
                .where { FeeWalletsTable.network eq network.name }
                .orderBy(FeeWalletsTable.blockchain to SortOrder.ASC)
                .map { it.toWallet() }
                .toList()
        }

    override suspend fun record(wallet: FeeWallet): FeeWallet =
        database.tx("fee-wallets.record") {
            val inserted =
                FeeWalletsTable
                    .insertIgnore {
                        it[blockchain] = wallet.blockchain
                        it[address] = wallet.address
                        it[circleWalletId] = wallet.circleWalletId
                        it[walletSetId] = wallet.walletSetId
                        it[origin] = wallet.origin.name
                        it[createdAt] = wallet.createdAt
                        it[updatedAt] = wallet.createdAt
                    }.insertedCount
            if (inserted > 0) {
                // The address fees are charged to: written only where the chain has none, never over one.
                ChainFeesTable.insertIgnore {
                    it[blockchain] = wallet.blockchain
                    it[feeAddress] = wallet.address
                    it[updatedAt] = wallet.createdAt
                }
                ChainFeesTable.update({
                    (ChainFeesTable.blockchain eq wallet.blockchain) and
                        (ChainFeesTable.feeAddress.isNull() or (ChainFeesTable.feeAddress eq ""))
                }) {
                    it[feeAddress] = wallet.address
                    it[updatedAt] = wallet.createdAt
                }
            } else if (wallet.circleWalletId != null) {
                FeeWalletsTable.update({
                    (FeeWalletsTable.blockchain eq wallet.blockchain) and
                        (FeeWalletsTable.address eq wallet.address) and
                        FeeWalletsTable.circleWalletId.isNull()
                }) {
                    it[circleWalletId] = wallet.circleWalletId
                    it[walletSetId] = wallet.walletSetId
                    it[origin] = wallet.origin.name
                    it[updatedAt] = wallet.createdAt
                }
            }
            FeeWalletsTable
                .selectAll()
                .where { FeeWalletsTable.blockchain eq wallet.blockchain }
                .map { it.toWallet() }
                .singleOrNull() ?: wallet
        }

    override suspend fun feeAddresses(): Map<String, String> =
        database.tx("fee-wallets.fee-addresses") {
            ChainFeesTable
                .selectAll()
                .map { it[ChainFeesTable.blockchain] to it[ChainFeesTable.feeAddress]?.trim().orEmpty() }
                .toList()
                .filter { it.second.isNotEmpty() }
                .toMap()
        }

    override suspend fun destinationDelayHours(): Int =
        database.tx("fee-wallets.delay") {
            FeeWalletSettingsTable
                .selectAll()
                .where { FeeWalletSettingsTable.id eq SETTINGS_ROW }
                .map { it[FeeWalletSettingsTable.destinationDelayHours] }
                .singleOrNull()
        } ?: DEFAULT_DELAY_HOURS

    private fun ResultRow.toWallet() =
        FeeWallet(
            blockchain = this[FeeWalletsTable.blockchain],
            address = this[FeeWalletsTable.address],
            circleWalletId = this[FeeWalletsTable.circleWalletId],
            walletSetId = this[FeeWalletsTable.walletSetId],
            origin = FeeWalletOrigin.valueOf(this[FeeWalletsTable.origin]),
            createdAt = this[FeeWalletsTable.createdAt],
        )
}

class FeeDestinationRepositoryImpl(private val database: Database) : FeeDestinationRepository {
    override suspend fun live(network: Network?): List<FeeDestination> =
        database.tx("fee-destinations.live") {
            FeeDestinationsTable
                .selectAll()
                .where {
                    val live: Op<Boolean> = FeeDestinationsTable.removedAt.isNull()
                    if (network == null) live else live and (FeeDestinationsTable.network eq network.name)
                }.orderBy(FeeDestinationsTable.createdAt to SortOrder.DESC)
                .map { it.toDestination() }
                .toList()
        }

    override suspend fun find(id: UUID): FeeDestination? =
        database.tx("fee-destinations.find") {
            FeeDestinationsTable
                .selectAll()
                .where { FeeDestinationsTable.destinationId eq id }
                .map { it.toDestination() }
                .singleOrNull()
        }

    override suspend fun add(destination: FeeDestination): Boolean =
        database.tx("fee-destinations.add") {
            FeeDestinationsTable
                .insertIgnore {
                    it[destinationId] = destination.id
                    it[blockchain] = destination.blockchain
                    it[address] = destination.address
                    it[label] = destination.label
                    it[createdBy] = destination.createdBy
                    it[createdByEmail] = destination.createdByEmail
                    it[createdAt] = destination.createdAt
                }.insertedCount > 0
        }

    override suspend fun remove(
        id: UUID,
        by: UUID,
        at: Instant,
    ): Boolean =
        database.tx("fee-destinations.remove") {
            FeeDestinationsTable.update({
                (FeeDestinationsTable.destinationId eq id) and FeeDestinationsTable.removedAt.isNull()
            }) {
                it[removedAt] = at
                it[removedBy] = by
            } > 0
        }

    private fun ResultRow.toDestination() =
        FeeDestination(
            id = this[FeeDestinationsTable.destinationId],
            blockchain = this[FeeDestinationsTable.blockchain],
            address = this[FeeDestinationsTable.address],
            label = this[FeeDestinationsTable.label],
            createdBy = this[FeeDestinationsTable.createdBy],
            createdByEmail = this[FeeDestinationsTable.createdByEmail],
            createdAt = this[FeeDestinationsTable.createdAt],
            removedAt = this[FeeDestinationsTable.removedAt],
        )
}

class FeeWithdrawalRepositoryImpl(private val database: Database) : FeeWithdrawalRepository {
    override suspend fun insert(withdrawal: FeeWithdrawal): Boolean =
        database.tx("fee-withdrawals.insert") {
            FeeWithdrawalsTable
                .insertIgnore {
                    it[withdrawalId] = withdrawal.id
                    it[blockchain] = withdrawal.blockchain
                    it[asset] = withdrawal.asset
                    it[tokenAddress] = withdrawal.tokenAddress
                    it[decimals] = withdrawal.decimals.toShort()
                    it[amountMinor] = withdrawal.amountMinor.toBigDecimal()
                    it[destinationId] = withdrawal.destinationId
                    it[destinationAddress] = withdrawal.destinationAddress
                    it[destinationLabel] = withdrawal.destinationLabel
                    it[circleWalletId] = withdrawal.circleWalletId
                    it[tokenId] = withdrawal.tokenId
                    it[circleKey] = withdrawal.circleKey
                    it[state] = withdrawal.state.name
                    it[circleTxId] = withdrawal.circleTxId
                    it[txHash] = withdrawal.txHash
                    it[failureReason] = withdrawal.failureReason
                    it[reason] = withdrawal.reason
                    it[requestedBy] = withdrawal.requestedBy
                    it[requestedByEmail] = withdrawal.requestedByEmail
                    it[idempotencyKey] = withdrawal.idempotencyKey
                    it[createdAt] = withdrawal.createdAt
                    it[updatedAt] = withdrawal.updatedAt
                }.insertedCount > 0
        }

    override suspend fun byIdempotencyKey(requestedBy: UUID, idempotencyKey: String): FeeWithdrawal? =
        database.tx("fee-withdrawals.by-key") {
            FeeWithdrawalsTable
                .selectAll()
                .where {
                    (FeeWithdrawalsTable.requestedBy eq requestedBy) and
                        (FeeWithdrawalsTable.idempotencyKey eq idempotencyKey)
                }.map { it.toWithdrawal() }
                .singleOrNull()
        }

    override suspend fun find(id: UUID): FeeWithdrawal? =
        database.tx("fee-withdrawals.find") {
            FeeWithdrawalsTable
                .selectAll()
                .where { FeeWithdrawalsTable.withdrawalId eq id }
                .map { it.toWithdrawal() }
                .singleOrNull()
        }

    override suspend fun page(
        network: Network?,
        before: UUID?,
        limit: Int,
    ): List<FeeWithdrawal> =
        database.tx("fee-withdrawals.page") {
            val conditions =
                listOfNotNull<Op<Boolean>>(
                    network?.let { FeeWithdrawalsTable.network eq it.name },
                    before?.let { FeeWithdrawalsTable.withdrawalId less it },
                )
            val query = FeeWithdrawalsTable.selectAll()
            val filtered = if (conditions.isEmpty()) query else query.where { conditions.reduce { a, b -> a and b } }
            filtered
                .orderBy(FeeWithdrawalsTable.withdrawalId to SortOrder.DESC)
                .limit(limit)
                .map { it.toWithdrawal() }
                .toList()
        }

    override suspend fun open(limit: Int): List<FeeWithdrawal> =
        database.tx("fee-withdrawals.open") {
            FeeWithdrawalsTable
                .selectAll()
                .where { FeeWithdrawalsTable.state inList OPEN }
                .orderBy(FeeWithdrawalsTable.withdrawalId to SortOrder.ASC)
                .limit(limit)
                .map { it.toWithdrawal() }
                .toList()
        }

    override suspend fun openOn(blockchain: String): List<FeeWithdrawal> =
        database.tx("fee-withdrawals.open-on") {
            FeeWithdrawalsTable
                .selectAll()
                .where { (FeeWithdrawalsTable.blockchain eq blockchain) and (FeeWithdrawalsTable.state inList OPEN) }
                .map { it.toWithdrawal() }
                .toList()
        }

    override suspend fun move(
        id: UUID,
        from: WithdrawalState,
        to: WithdrawalState,
        change: WithdrawalChange,
        at: Instant,
    ): Boolean =
        database.tx("fee-withdrawals.move") {
            FeeWithdrawalsTable.update({
                (FeeWithdrawalsTable.withdrawalId eq id) and (FeeWithdrawalsTable.state eq from.name)
            }) {
                it[state] = to.name
                it[updatedAt] = at
                change.circleTxId?.let { value -> it[circleTxId] = value }
                change.txHash?.let { value -> it[txHash] = value }
                change.failureReason?.let { value -> it[failureReason] = value }
            } > 0
        }

    override suspend fun noteHash(
        id: UUID,
        txHash: String,
        at: Instant,
    ) {
        database.tx("fee-withdrawals.note-hash") {
            FeeWithdrawalsTable.update({
                (FeeWithdrawalsTable.withdrawalId eq id) and (FeeWithdrawalsTable.state eq WithdrawalState.SENT.name)
            }) {
                it[FeeWithdrawalsTable.txHash] = txHash
                it[updatedAt] = at
            }
        }
    }

    private fun ResultRow.toWithdrawal() =
        FeeWithdrawal(
            id = this[FeeWithdrawalsTable.withdrawalId],
            blockchain = this[FeeWithdrawalsTable.blockchain],
            asset = this[FeeWithdrawalsTable.asset],
            tokenAddress = this[FeeWithdrawalsTable.tokenAddress],
            decimals = this[FeeWithdrawalsTable.decimals].toInt(),
            amountMinor = this[FeeWithdrawalsTable.amountMinor].toBigIntegerExact(),
            destinationId = this[FeeWithdrawalsTable.destinationId],
            destinationAddress = this[FeeWithdrawalsTable.destinationAddress],
            destinationLabel = this[FeeWithdrawalsTable.destinationLabel],
            circleWalletId = this[FeeWithdrawalsTable.circleWalletId],
            tokenId = this[FeeWithdrawalsTable.tokenId],
            circleKey = this[FeeWithdrawalsTable.circleKey],
            state = WithdrawalState.valueOf(this[FeeWithdrawalsTable.state]),
            circleTxId = this[FeeWithdrawalsTable.circleTxId],
            txHash = this[FeeWithdrawalsTable.txHash],
            failureReason = this[FeeWithdrawalsTable.failureReason],
            reason = this[FeeWithdrawalsTable.reason],
            requestedBy = this[FeeWithdrawalsTable.requestedBy],
            requestedByEmail = this[FeeWithdrawalsTable.requestedByEmail],
            idempotencyKey = this[FeeWithdrawalsTable.idempotencyKey],
            createdAt = this[FeeWithdrawalsTable.createdAt],
            updatedAt = this[FeeWithdrawalsTable.updatedAt],
        )

    private companion object {
        val OPEN = listOf(WithdrawalState.PENDING.name, WithdrawalState.SENT.name)
    }
}
