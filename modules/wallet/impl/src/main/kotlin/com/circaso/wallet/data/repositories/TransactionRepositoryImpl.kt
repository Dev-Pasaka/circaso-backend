package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.data.tables.TransactionsTable
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.HistoryFilter
import com.circaso.wallet.domain.models.HistoryPosition
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.repositories.TransactionRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.time.Instant
import java.util.UUID

/**
 * `transactions`. An inbound row is inserted with `ON CONFLICT DO NOTHING` (`transactions_circle_key`), so a repeated
 * notification adds nothing; states move by the same transition guard as transfers, found through
 * `transactions_transfer_key` (the sender's row) or `transactions_circle_key` (a row Circle reported).
 */
class TransactionRepositoryImpl(private val database: Database) : TransactionRepository {
    override suspend fun insert(transaction: WalletTransaction): Boolean =
        database.tx("transactions.insert") {
            val statement =
                TransactionsTable.insertIgnore {
                    it[id] = transaction.id
                    it[userId] = transaction.userId.value
                    it[direction] = transaction.direction.name
                    it[state] = transaction.state.name
                    it[amountMinor] = transaction.amount.amountMinor
                    it[asset] = transaction.amount.asset.name
                    it[blockchain] = transaction.blockchain
                    it[counterpartyId] = transaction.counterpartyId?.value
                    it[counterpartyAddress] = transaction.counterpartyAddress
                    it[transferId] = transaction.transferId?.value
                    it[circleTxId] = transaction.circleTxId
                    it[txHash] = transaction.txHash
                    it[chatId] = transaction.chatId?.value
                    it[origin] = transaction.origin.name
                    it[occurredAt] = transaction.occurredAt
                    it[paidByTransferId] = transaction.paidByTransferId?.value
                }
            statement.insertedCount > 0
        }

    override suspend fun moveByTransfer(
        transferId: TransferId,
        to: TransferState,
        change: TransferChange,
    ): Boolean =
        database.tx("transactions.move-by-transfer") {
            move(to, change) { TransactionsTable.transferId eq transferId.value }.isNotEmpty()
        }

    override suspend fun moveByCircle(
        circleTxId: String,
        userId: UserId,
        to: TransferState,
        change: TransferChange,
    ): WalletTransaction? =
        database.tx("transactions.move-by-circle") {
            move(to, change) {
                (TransactionsTable.circleTxId eq circleTxId) and (TransactionsTable.userId eq userId.value)
            }.singleOrNull()
        }

    override suspend fun parties(since: Instant, limit: Int): List<UserId> =
        database.tx("transactions.parties") {
            val owners =
                TransactionsTable
                    .select(TransactionsTable.userId)
                    .where { TransactionsTable.occurredAt greaterEq since }
                    .withDistinct()
                    .limit(limit)
                    .map { UserId(it[TransactionsTable.userId]) }
                    .toList()
            val counterparties =
                TransactionsTable
                    .select(TransactionsTable.counterpartyId)
                    .where {
                        (TransactionsTable.occurredAt greaterEq since) and TransactionsTable.counterpartyId.isNotNull()
                    }.withDistinct()
                    .limit(limit)
                    .mapNotNull { row -> row[TransactionsTable.counterpartyId]?.let(::UserId) }
                    .toList()
            (owners + counterparties).distinct().take(limit)
        }

    /**
     * `WHERE user_id = ? AND state IN (…) [AND occurred_at >= ? AND occurred_at < ?]
     * [AND (occurred_at < ? OR (occurred_at = ? AND id < ?))] ORDER BY occurred_at DESC, id DESC LIMIT ?`, walking
     * `transactions_user_idx` backwards from the cursor.
     */
    override suspend fun history(
        userId: UserId,
        states: Set<TransferState>,
        from: Instant?,
        until: Instant?,
        after: HistoryPosition?,
        limit: Int,
        filter: HistoryFilter,
    ): List<WalletTransaction> {
        if (states.isEmpty()) return emptyList()
        return database.tx("transactions.history") {
            TransactionsTable
                .selectAll()
                .where {
                    var where =
                        (TransactionsTable.userId eq userId.value) and
                            (TransactionsTable.state inList states.map { it.name })
                    from?.let { where = where and (TransactionsTable.occurredAt greaterEq it) }
                    until?.let { where = where and (TransactionsTable.occurredAt less it) }
                    after?.let { where = where and before(it) }
                    matching(filter)?.let { where = where and it }
                    where
                }.orderBy(TransactionsTable.occurredAt to SortOrder.DESC, TransactionsTable.id to SortOrder.DESC)
                .limit(limit)
                .map { it.toTransaction() }
                .toList()
        }
    }

    override suspend fun find(userId: UserId, id: UUID): WalletTransaction? =
        database.tx("transactions.find") {
            TransactionsTable
                .selectAll()
                .where { (TransactionsTable.id eq id) and (TransactionsTable.userId eq userId.value) }
                .map { it.toTransaction() }
                .singleOrNull()
        }

    override suspend fun isPaidBy(transferId: TransferId): Boolean =
        database.tx("transactions.is-paid-by") {
            TransactionsTable
                .select(TransactionsTable.id)
                .where { TransactionsTable.paidByTransferId eq transferId.value }
                .limit(1)
                .toList()
                .isNotEmpty()
        }

    /** `UPDATE … SET paid_by_transfer_id = ?, chat_id = ? WHERE id = ? AND paid_by_transfer_id IS NULL RETURNING *`. */
    override suspend fun linkPayment(
        id: UUID,
        transferId: TransferId,
        chatId: ChatId?,
    ): WalletTransaction? =
        database.tx("transactions.link-payment") {
            TransactionsTable
                .updateReturning(where = {
                    (TransactionsTable.id eq id) and TransactionsTable.paidByTransferId.isNull()
                }) {
                    it[paidByTransferId] = transferId.value
                    it[TransactionsTable.chatId] = chatId?.value
                }.map { it.toTransaction() }
                .singleOrNull()
        }

    /** The filter's parts as one condition, or null when it has none. */
    private fun matching(filter: HistoryFilter): Op<Boolean>? {
        val parts =
            listOfNotNull(
                filter.direction?.let { TransactionsTable.direction eq it.name },
                filter.counterpartyId?.let { TransactionsTable.counterpartyId eq it.value },
                filter.counterpartyAddress?.let { TransactionsTable.counterpartyAddress eq it },
                filter.asset?.let { TransactionsTable.asset eq it.name },
                filter.blockchain?.let { TransactionsTable.blockchain eq it },
            )
        return parts.reduceOrNull { all, part -> all and part }
    }

    /** Rows that come after [position] newest first: older, or as old with a smaller id. */
    private fun before(position: HistoryPosition): Op<Boolean> =
        (TransactionsTable.occurredAt less position.occurredAt) or
            (
                (TransactionsTable.occurredAt eq position.occurredAt) and
                    (TransactionsTable.id less position.id)
            )

    /**
     * The transition guard, inside the caller's transaction: rows matching [where] in one of `to.from`, moved to [to];
     * the rows as they now are.
     */
    private suspend fun move(
        to: TransferState,
        change: TransferChange,
        where: () -> Op<Boolean>,
    ): List<WalletTransaction> {
        val from = to.from.map { it.name }
        if (from.isEmpty()) return emptyList()
        return TransactionsTable
            .updateReturning(where = { where() and (TransactionsTable.state inList from) }) {
                it.apply(to, change)
            }.map { it.toTransaction() }
            .toList()
    }

    private fun UpdateStatement.apply(to: TransferState, change: TransferChange) {
        this[TransactionsTable.state] = to.name
        change.circleTxId?.let { this[TransactionsTable.circleTxId] = it }
        change.txHash?.let { this[TransactionsTable.txHash] = it }
    }

    private fun ResultRow.toTransaction() =
        WalletTransaction(
            id = this[TransactionsTable.id],
            userId = UserId(this[TransactionsTable.userId]),
            direction = Direction.valueOf(this[TransactionsTable.direction]),
            state = TransferState.valueOf(this[TransactionsTable.state]),
            amount = Money(this[TransactionsTable.amountMinor], Asset.valueOf(this[TransactionsTable.asset])),
            blockchain = this[TransactionsTable.blockchain],
            counterpartyId = this[TransactionsTable.counterpartyId]?.let(::UserId),
            counterpartyAddress = this[TransactionsTable.counterpartyAddress],
            transferId = this[TransactionsTable.transferId]?.let(::TransferId),
            circleTxId = this[TransactionsTable.circleTxId],
            txHash = this[TransactionsTable.txHash],
            chatId = this[TransactionsTable.chatId]?.let(::ChatId),
            origin = TransferOrigin.valueOf(this[TransactionsTable.origin]),
            occurredAt = this[TransactionsTable.occurredAt],
            paidByTransferId = this[TransactionsTable.paidByTransferId]?.let(::TransferId),
        )
}
