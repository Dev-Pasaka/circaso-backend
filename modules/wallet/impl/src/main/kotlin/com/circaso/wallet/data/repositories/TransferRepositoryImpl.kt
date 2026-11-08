package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.data.tables.CircleUsersTable
import com.circaso.wallet.data.tables.TransfersTable
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.repositories.CountedTransfer
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
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
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
                    it[amountMinor] = transfer.amount.amountMinor.toBigDecimal()
                    it[asset] = transfer.amount.asset.symbol
                    it[assetDecimals] =
                        transfer.amount.asset.decimals
                            .toShort()
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
                    it[fromSpaceId] = transfer.fromSpaceId
                    it[toSpaceId] = transfer.toSpaceId
                    it[spacePaymentId] = transfer.spacePaymentId
                    it[feeMinor] = transfer.feeMinor.toBigDecimal()
                    it[feeAddress] = transfer.feeAddress
                    it[tokenAddress] = transfer.tokenAddress
                    it[usdPrice] = transfer.usdPrice
                    it[usdTotalMinor] = transfer.usdTotalMinor
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

    override suspend fun byCircleTx(senderWalletId: String, circleTxId: String): Transfer? =
        database.tx("transfers.by-circle-tx") {
            TransfersTable
                .selectAll()
                .where {
                    (TransfersTable.senderWalletId eq senderWalletId) and (TransfersTable.circleTxId eq circleTxId)
                }.limit(1)
                .map { it.toTransfer() }
                .singleOrNull()
        }

    override suspend fun unsettledSplits(
        senderWalletId: String?,
        createdAfter: Instant,
        createdBefore: Instant,
        limit: Int,
    ): List<Transfer> =
        database.tx("transfers.unsettled-splits") {
            TransfersTable
                .selectAll()
                .where {
                    val open =
                        TransfersTable.feeAddress.isNotNull() and
                            TransfersTable.circleTxId.isNull() and
                            (TransfersTable.state inList UNSETTLED) and
                            (TransfersTable.createdAt greaterEq createdAfter) and
                            (TransfersTable.createdAt less createdBefore)
                    if (senderWalletId == null) open else open and (TransfersTable.senderWalletId eq senderWalletId)
                }.orderBy(TransfersTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toTransfer() }
                .toList()
        }

    override suspend fun lockHolder(holder: UserId) {
        database.tx("transfers.lock-holder") {
            CircleUsersTable
                .select(CircleUsersTable.userId)
                .where { CircleUsersTable.userId eq holder.value }
                .forUpdate(ForUpdateOption.ForUpdate)
                .toList()
        }
    }

    override suspend fun spentSince(holder: UserId, since: Instant): List<CountedTransfer> =
        database.tx("transfers.spent-since") {
            TransfersTable
                .select(
                    TransfersTable.createdAt,
                    TransfersTable.usdTotalMinor,
                    TransfersTable.state,
                ).where {
                    (TransfersTable.senderId eq holder.value) and
                        (TransfersTable.createdAt greaterEq since) and
                        (TransfersTable.state inList COUNTED)
                }.map {
                    CountedTransfer(
                        createdAt = it[TransfersTable.createdAt],
                        usdTotalMinor = it[TransfersTable.usdTotalMinor],
                        awaiting = it[TransfersTable.state] == TransferState.AWAITING_SIGNATURE.name,
                    )
                }.toList()
        }

    override suspend fun paidTo(
        recipientId: UserId,
        senderWalletId: String,
        blockchain: String,
        amount: Money,
        createdAfter: Instant,
        limit: Int,
    ): List<Transfer> =
        database.tx("transfers.paid-to") {
            TransfersTable
                .selectAll()
                .where {
                    (TransfersTable.recipientId eq recipientId.value) and
                        (TransfersTable.senderWalletId eq senderWalletId) and
                        (TransfersTable.createdAt greaterEq createdAfter) and
                        (TransfersTable.blockchain eq blockchain) and
                        (TransfersTable.amountMinor eq amount.amountMinor.toBigDecimal()) and
                        (TransfersTable.asset eq amount.asset.symbol) and
                        (TransfersTable.state inList PAID)
                }.orderBy(TransfersTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toTransfer() }
                .toList()
        }

    private companion object {
        /**
         * A transfer that may have reached its recipient: on its way or on chain, or still awaiting a signature or
         * expired here while the chain already has it (the inbound update can come before the outbound one).
         */
        val PAID =
            listOf(
                TransferState.AWAITING_SIGNATURE,
                TransferState.SUBMITTED,
                TransferState.CONFIRMED,
                TransferState.EXPIRED,
            ).map { it.name }

        /** A split payment Circle may still settle (`transfers_unsettled_split_idx`). */
        val UNSETTLED = listOf(TransferState.AWAITING_SIGNATURE, TransferState.EXPIRED).map { it.name }

        /** What counts against a limit (§7a): signed or about to be. */
        val COUNTED =
            listOf(TransferState.AWAITING_SIGNATURE, TransferState.SUBMITTED, TransferState.CONFIRMED).map { it.name }
    }
}

/** A `transfers` row as the domain's [Transfer]; the staff reports read the same rows. */
internal fun ResultRow.toTransfer() =
    Transfer(
        transferId = TransferId(this[TransfersTable.transferId]),
        senderId = UserId(this[TransfersTable.senderId]),
        senderWalletId = this[TransfersTable.senderWalletId],
        blockchain = this[TransfersTable.blockchain],
        idempotencyKey = this[TransfersTable.idempotencyKey],
        recipientId = this[TransfersTable.recipientId]?.let(::UserId),
        destinationAddress = this[TransfersTable.destinationAddress],
        amount =
            moneyOf(
                this[TransfersTable.amountMinor],
                this[TransfersTable.asset],
                this[TransfersTable.assetDecimals],
            ),
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
        fromSpaceId = this[TransfersTable.fromSpaceId],
        toSpaceId = this[TransfersTable.toSpaceId],
        spacePaymentId = this[TransfersTable.spacePaymentId],
        feeMinor = this[TransfersTable.feeMinor].toBigIntegerExact(),
        feeAddress = this[TransfersTable.feeAddress],
        tokenAddress = this[TransfersTable.tokenAddress],
        usdPrice = this[TransfersTable.usdPrice]?.stripTrailingZeros(),
        usdTotalMinor = this[TransfersTable.usdTotalMinor],
    )
