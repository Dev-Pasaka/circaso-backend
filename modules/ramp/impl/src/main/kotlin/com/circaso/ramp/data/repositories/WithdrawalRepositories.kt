package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.tables.WithdrawalDestinationsTable
import com.circaso.ramp.data.tables.WithdrawalsTable
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.Withdrawal
import com.circaso.ramp.domain.models.WithdrawalDestination
import com.circaso.ramp.domain.models.WithdrawalState
import com.circaso.ramp.domain.repositories.WithdrawalDestinationRepository
import com.circaso.ramp.domain.repositories.WithdrawalRepository
import com.circaso.shared.Network
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant
import java.util.UUID

/** `ramp.withdrawal_destinations`. An insert that meets a live row for the same address adds nothing. */
class WithdrawalDestinationRepositoryImpl(private val database: Database) : WithdrawalDestinationRepository {
    override suspend fun insert(destination: WithdrawalDestination): Boolean =
        database.tx("withdrawal-destinations.insert") {
            WithdrawalDestinationsTable
                .insertIgnore {
                    it[destinationId] = destination.destinationId
                    it[merchantId] = destination.merchantId.value
                    it[network] = destination.network.name
                    it[blockchain] = destination.blockchain
                    it[address] = destination.address
                    it[label] = destination.label
                    it[createdAt] = destination.createdAt
                    it[usableAt] = destination.usableAt
                    it[removedAt] = destination.removedAt
                }.insertedCount > 0
        }

    override suspend fun find(destinationId: UUID): WithdrawalDestination? =
        database.tx("withdrawal-destinations.find") {
            WithdrawalDestinationsTable
                .selectAll()
                .where { WithdrawalDestinationsTable.destinationId eq destinationId }
                .map { it.toDestination() }
                .singleOrNull()
        }

    override suspend fun live(merchantId: MerchantId, network: Network): List<WithdrawalDestination> =
        list(merchantId, network, MAX_LIVE, liveOnly = true)

    override suspend fun all(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
    ): List<WithdrawalDestination> = list(merchantId, network, limit, liveOnly = false)

    private suspend fun list(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
        liveOnly: Boolean,
    ): List<WithdrawalDestination> =
        database.tx("withdrawal-destinations.list") {
            WithdrawalDestinationsTable
                .selectAll()
                .where {
                    val mine =
                        (WithdrawalDestinationsTable.merchantId eq merchantId.value) and
                            (WithdrawalDestinationsTable.network eq network.name)
                    if (liveOnly) mine and WithdrawalDestinationsTable.removedAt.isNull() else mine
                }.orderBy(
                    WithdrawalDestinationsTable.createdAt to SortOrder.DESC,
                    WithdrawalDestinationsTable.destinationId to SortOrder.DESC,
                ).limit(limit)
                .map { it.toDestination() }
                .toList()
        }

    override suspend fun remove(destinationId: UUID, at: Instant): Boolean =
        database.tx("withdrawal-destinations.remove") {
            WithdrawalDestinationsTable.update({
                (WithdrawalDestinationsTable.destinationId eq destinationId) and
                    WithdrawalDestinationsTable.removedAt.isNull()
            }) { it[removedAt] = at } > 0
        }

    private fun ResultRow.toDestination() =
        WithdrawalDestination(
            destinationId = this[WithdrawalDestinationsTable.destinationId],
            merchantId = MerchantId(this[WithdrawalDestinationsTable.merchantId]),
            network = Network.valueOf(this[WithdrawalDestinationsTable.network]),
            blockchain = this[WithdrawalDestinationsTable.blockchain],
            address = this[WithdrawalDestinationsTable.address],
            label = this[WithdrawalDestinationsTable.label],
            createdAt = this[WithdrawalDestinationsTable.createdAt],
            usableAt = this[WithdrawalDestinationsTable.usableAt],
            removedAt = this[WithdrawalDestinationsTable.removedAt],
        )

    private companion object {
        /** Far above the portal's own cap: a bound, so a read never runs without one. */
        const val MAX_LIVE = 100
    }
}

/** `ramp.withdrawals`. Every move is guarded by the states it may come from. */
class WithdrawalRepositoryImpl(private val database: Database) : WithdrawalRepository {
    override suspend fun insert(withdrawal: Withdrawal): Boolean =
        database.tx("withdrawals.insert") {
            WithdrawalsTable
                .insertIgnore {
                    it[withdrawalId] = withdrawal.withdrawalId
                    it[merchantId] = withdrawal.merchantId.value
                    it[network] = withdrawal.network.name
                    it[blockchain] = withdrawal.blockchain
                    it[destinationId] = withdrawal.destinationId
                    it[destinationAddress] = withdrawal.destinationAddress
                    it[destinationLabel] = withdrawal.destinationLabel
                    it[amountMinor] = withdrawal.amountMinor
                    it[asset] = RampSettings.ASSET
                    it[state] = withdrawal.state.name
                    it[idempotencyKey] = withdrawal.idempotencyKey
                    it[custodyTxId] = withdrawal.custodyTxId
                    it[txHash] = withdrawal.txHash
                    it[failureReason] = withdrawal.failureReason
                    it[createdAt] = withdrawal.createdAt
                    it[updatedAt] = withdrawal.updatedAt
                    it[sentAt] = withdrawal.sentAt
                    it[closedAt] = withdrawal.closedAt
                }.insertedCount > 0
        }

    override suspend fun find(withdrawalId: UUID): Withdrawal? =
        database.tx("withdrawals.find") {
            WithdrawalsTable
                .selectAll()
                .where { WithdrawalsTable.withdrawalId eq withdrawalId }
                .map { it.toWithdrawal() }
                .singleOrNull()
        }

    override suspend fun byKey(merchantId: MerchantId, idempotencyKey: String): Withdrawal? =
        database.tx("withdrawals.by-key") {
            WithdrawalsTable
                .selectAll()
                .where {
                    (WithdrawalsTable.merchantId eq merchantId.value) and
                        (WithdrawalsTable.idempotencyKey eq idempotencyKey)
                }.limit(1)
                .map { it.toWithdrawal() }
                .singleOrNull()
        }

    override suspend fun page(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
        before: UUID?,
    ): List<Withdrawal> =
        database.tx("withdrawals.page") {
            WithdrawalsTable
                .selectAll()
                .where {
                    val mine =
                        (WithdrawalsTable.merchantId eq merchantId.value) and
                            (WithdrawalsTable.network eq network.name)
                    if (before == null) mine else mine and (WithdrawalsTable.withdrawalId less before)
                }.orderBy(WithdrawalsTable.withdrawalId to SortOrder.DESC)
                .limit(limit)
                .map { it.toWithdrawal() }
                .toList()
        }

    override suspend fun open(network: Network, limit: Int): List<Withdrawal> =
        database.tx("withdrawals.open") {
            WithdrawalsTable
                .selectAll()
                .where { (WithdrawalsTable.network eq network.name) and (WithdrawalsTable.state inList OPEN_STATES) }
                .orderBy(WithdrawalsTable.updatedAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toWithdrawal() }
                .toList()
        }

    override suspend fun totalSince(
        merchantId: MerchantId,
        network: Network,
        since: Instant,
    ): Long =
        database.tx("withdrawals.total-since") {
            val total = WithdrawalsTable.amountMinor.sum()
            WithdrawalsTable
                .select(total)
                .where {
                    (WithdrawalsTable.merchantId eq merchantId.value) and
                        (WithdrawalsTable.network eq network.name) and
                        (WithdrawalsTable.state neq WithdrawalState.FAILED.name) and
                        (WithdrawalsTable.createdAt greaterEq since)
                }.single()[total] ?: 0
        }

    override suspend fun sent(
        withdrawalId: UUID,
        custodyTxId: String,
        at: Instant,
    ): Boolean =
        database.tx("withdrawals.sent") {
            WithdrawalsTable.update({ moving(withdrawalId, WithdrawalState.REQUESTED) }) {
                it[state] = WithdrawalState.SENT.name
                it[WithdrawalsTable.custodyTxId] = custodyTxId
                it[sentAt] = at
                it[updatedAt] = at
            } > 0
        }

    override suspend fun noteHash(
        withdrawalId: UUID,
        txHash: String,
        at: Instant,
    ) {
        database.tx("withdrawals.note-hash") {
            WithdrawalsTable.update({
                moving(withdrawalId, WithdrawalState.SENT) and WithdrawalsTable.txHash.isNull()
            }) {
                it[WithdrawalsTable.txHash] = txHash
                it[updatedAt] = at
            }
        }
    }

    override suspend fun complete(
        withdrawalId: UUID,
        txHash: String?,
        at: Instant,
    ): Boolean =
        database.tx("withdrawals.complete") {
            WithdrawalsTable.update({ moving(withdrawalId, WithdrawalState.SENT) }) {
                it[state] = WithdrawalState.COMPLETED.name
                if (txHash != null) it[WithdrawalsTable.txHash] = txHash
                it[updatedAt] = at
                it[closedAt] = at
            } > 0
        }

    override suspend fun fail(
        withdrawalId: UUID,
        reason: String,
        at: Instant,
    ): Boolean =
        database.tx("withdrawals.fail") {
            WithdrawalsTable.update({
                (WithdrawalsTable.withdrawalId eq withdrawalId) and (WithdrawalsTable.state inList OPEN_STATES)
            }) {
                it[state] = WithdrawalState.FAILED.name
                it[failureReason] = reason
                it[updatedAt] = at
                it[closedAt] = at
            } > 0
        }

    private fun moving(withdrawalId: UUID, from: WithdrawalState): Op<Boolean> =
        (WithdrawalsTable.withdrawalId eq withdrawalId) and (WithdrawalsTable.state eq from.name)

    private fun ResultRow.toWithdrawal() =
        Withdrawal(
            withdrawalId = this[WithdrawalsTable.withdrawalId],
            merchantId = MerchantId(this[WithdrawalsTable.merchantId]),
            network = Network.valueOf(this[WithdrawalsTable.network]),
            blockchain = this[WithdrawalsTable.blockchain],
            destinationId = this[WithdrawalsTable.destinationId],
            destinationAddress = this[WithdrawalsTable.destinationAddress],
            destinationLabel = this[WithdrawalsTable.destinationLabel],
            amountMinor = this[WithdrawalsTable.amountMinor],
            state = WithdrawalState.valueOf(this[WithdrawalsTable.state]),
            idempotencyKey = this[WithdrawalsTable.idempotencyKey],
            custodyTxId = this[WithdrawalsTable.custodyTxId],
            txHash = this[WithdrawalsTable.txHash],
            failureReason = this[WithdrawalsTable.failureReason],
            createdAt = this[WithdrawalsTable.createdAt],
            updatedAt = this[WithdrawalsTable.updatedAt],
            sentAt = this[WithdrawalsTable.sentAt],
            closedAt = this[WithdrawalsTable.closedAt],
        )

    private companion object {
        val OPEN_STATES = WithdrawalState.entries.filter { it.isOpen }.map { it.name }
    }
}
