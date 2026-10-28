package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.wallet.data.tables.CircleUsersTable
import com.circaso.wallet.data.tables.WalletsTable
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.repositories.WalletRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** The wallet over `circle_users` and `wallets`; a user's wallets are read through `wallets_user_chain_key`. */
class WalletRepositoryImpl(private val database: Database) : WalletRepository {
    override suspend fun circleUser(userId: UserId): CircleUser? =
        database.tx("circle-users.find") {
            CircleUsersTable
                .selectAll()
                .where { CircleUsersTable.userId eq userId.value }
                .map { it.toCircleUser() }
                .singleOrNull()
        }

    override suspend fun insertCircleUser(user: CircleUser): Boolean =
        database.tx("circle-users.insert") {
            val statement =
                CircleUsersTable.insertIgnore {
                    it[userId] = user.userId.value
                    it[circleUserId] = user.circleUserId
                    it[createdAt] = user.createdAt
                }
            statement.insertedCount > 0
        }

    override suspend fun wallets(userId: UserId): List<ChainWallet> =
        database.tx("wallets.of-user") {
            WalletsTable
                .selectAll()
                .where { WalletsTable.userId eq userId.value }
                .orderBy(WalletsTable.createdAt to SortOrder.ASC, WalletsTable.blockchain to SortOrder.ASC)
                .map { it.toChainWallet() }
                .toList()
        }

    override suspend fun insertWallet(wallet: ChainWallet): Boolean =
        database.tx("wallets.insert") {
            val statement =
                WalletsTable.insertIgnore {
                    it[circleWalletId] = wallet.circleWalletId
                    it[userId] = wallet.userId.value
                    it[blockchain] = wallet.blockchain
                    it[accountType] = wallet.accountType
                    it[address] = wallet.address
                    it[state] = wallet.state.name
                    it[createdAt] = wallet.createdAt
                }
            statement.insertedCount > 0
        }

    private fun ResultRow.toCircleUser() =
        CircleUser(
            userId = UserId(this[CircleUsersTable.userId]),
            circleUserId = this[CircleUsersTable.circleUserId],
            createdAt = this[CircleUsersTable.createdAt],
        )

    private fun ResultRow.toChainWallet() =
        ChainWallet(
            circleWalletId = this[WalletsTable.circleWalletId],
            userId = UserId(this[WalletsTable.userId]),
            blockchain = this[WalletsTable.blockchain],
            accountType = this[WalletsTable.accountType],
            address = this[WalletsTable.address],
            state = ChainWalletState.valueOf(this[WalletsTable.state]),
            createdAt = this[WalletsTable.createdAt],
        )
}
