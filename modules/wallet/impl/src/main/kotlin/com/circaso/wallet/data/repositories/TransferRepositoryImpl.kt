package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.data.tables.TransfersTable
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.repositories.TransferRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.time.Instant
import java.util.UUID

/**
 * `transfers`, by primary key and by `transfers_idempotency_key`. A state moves only through [move], the transition
 * guard: one `UPDATE … WHERE state IN (…) RETURNING`, so of two webhooks racing only one moves it.
 */
class TransferRepositoryImpl(private val database: Database) : TransferRepository {
    override suspend fun insert(transfer: Transfer): Boolean =
        database.tx("transfers.insert") {
            val statement =
                TransfersTable.insertIgnore {
                    it[transferId] = transfer.transferId.value
                    it[senderId] = transfer.senderId.value
                    it[senderWalletId] = transfer.senderWalletId
                    it[blockchain] = transfer.blockchain
                    it[idempotencyKey] = transfer.idempotencyKey
                    it[recipientId] = transfer.recipientId?.value
                    it[destinationAddress] = transfer.destinationAddress
                    it[amountMinor] = transfer.amount.amountMinor
                    it[asset] = transfer.amount.asset.name
                    it[state] = transfer.state.name
                    it[circleTxId] = transfer.circleTxId
                    it[txHash] = transfer.txHash
                    it[chatId] = transfer.chatId?.value
                    it[origin] = transfer.origin.name
                    it[proposalId] = transfer.proposalId
                    it[failureReason] = transfer.failureReason
                    it[createdAt] = transfer.createdAt
                    it[updatedAt] = transfer.updatedAt
                    it[signerId] = transfer.signerId?.value
                    it[payoutRequestId] = transfer.payoutRequestId
                    it[fromGroupId] = transfer.fromGroupId?.value
                    it[toGroupId] = transfer.toGroupId?.value
                }
            statement.insertedCount > 0
        }

    override suspend fun byKey(senderId: UserId, idempotencyKey: UUID): Transfer? =
        database.tx("transfers.by-key") {
            TransfersTable
                .selectAll()
                .where {
                    (TransfersTable.senderId eq senderId.value) and (TransfersTable.idempotencyKey eq idempotencyKey)
                }.map { it.toTransfer() }
                .singleOrNull()
        }

    override suspend fun forPayout(payoutRequestId: UUID): List<Transfer> =
        database.tx("transfers.for-payout") {
            TransfersTable
                .selectAll()
                .where { TransfersTable.payoutRequestId eq payoutRequestId }
                .map { it.toTransfer() }
                .toList()
        }

    override suspend fun find(transferId: TransferId): Transfer? =
        database.tx("transfers.find") {
            TransfersTable
                .selectAll()
                .where { TransfersTable.transferId eq transferId.value }
                .map { it.toTransfer() }
                .singleOrNull()
        }

    override suspend fun move(
        transferId: TransferId,
        to: TransferState,
        change: TransferChange,
        from: Set<TransferState>,
    ): Transfer? {
        val allowed = from.intersect(to.from).map { it.name }
        if (allowed.isEmpty()) return null
        return database.tx("transfers.move") {
            TransfersTable
                .updateReturning(where = {
                    (TransfersTable.transferId eq transferId.value) and (TransfersTable.state inList allowed)
                }) {
                    it[state] = to.name
                    it[updatedAt] = change.at
                    change.circleTxId?.let { value -> it[circleTxId] = value }
                    change.txHash?.let { value -> it[txHash] = value }
                    change.failureReason?.let { value -> it[failureReason] = value }
                }.map { it.toTransfer() }
                .singleOrNull()
        }
    }

    override suspend fun inState(
        state: TransferState,
        createdAfter: Instant,
        createdBefore: Instant,
        limit: Int,
    ): List<Transfer> =
        database.tx("transfers.in-state") {
            TransfersTable
                .selectAll()
                .where {
                    (TransfersTable.state eq state.name) and
                        (TransfersTable.createdAt greaterEq createdAfter) and
                        (TransfersTable.createdAt less createdBefore)
                }.orderBy(TransfersTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toTransfer() }
                .toList()
        }

    private fun ResultRow.toTransfer() =
        Transfer(
            transferId = TransferId(this[TransfersTable.transferId]),
            senderId = UserId(this[TransfersTable.senderId]),
            senderWalletId = this[TransfersTable.senderWalletId],
            blockchain = this[TransfersTable.blockchain],
            idempotencyKey = this[TransfersTable.idempotencyKey],
            recipientId = this[TransfersTable.recipientId]?.let(::UserId),
            destinationAddress = this[TransfersTable.destinationAddress],
            amount = Money(this[TransfersTable.amountMinor], Asset.valueOf(this[TransfersTable.asset])),
            state = TransferState.valueOf(this[TransfersTable.state]),
            circleTxId = this[TransfersTable.circleTxId],
            txHash = this[TransfersTable.txHash],
            chatId = this[TransfersTable.chatId]?.let(::ChatId),
            origin = TransferOrigin.valueOf(this[TransfersTable.origin]),
            proposalId = this[TransfersTable.proposalId],
            failureReason = this[TransfersTable.failureReason],
            createdAt = this[TransfersTable.createdAt],
            updatedAt = this[TransfersTable.updatedAt],
            signerId = this[TransfersTable.signerId]?.let(::UserId),
            payoutRequestId = this[TransfersTable.payoutRequestId],
            fromGroupId = this[TransfersTable.fromGroupId]?.let(::ChatId),
            toGroupId = this[TransfersTable.toGroupId]?.let(::ChatId),
        )
}
