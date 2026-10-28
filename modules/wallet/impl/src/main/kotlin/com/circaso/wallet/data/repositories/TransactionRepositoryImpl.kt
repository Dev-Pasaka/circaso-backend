package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.data.tables.TransactionsTable
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.repositories.TransactionRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.updateReturning

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
        )
}
