package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.api.WalletOwnerType
import com.circaso.wallet.data.tables.WalletOwnersTable
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.util.UUID

/**
 * `wallet_owners` by its primary key and by `wallet_owners_chat_key`. The insert is the claim behind W14: of two
 * owners' taps at once, one row lands and the other reads it.
 */
class WalletOwnerRepositoryImpl(private val database: Database) : WalletOwnerRepository {
    override suspend fun find(walletId: WalletId): WalletOwner? =
        database.tx("wallet-owners.find") {
            WalletOwnersTable
                .selectAll()
                .where { WalletOwnersTable.walletId eq walletId.value }
                .map { it.toOwner() }
                .singleOrNull()
        }

    override suspend fun byChat(chatId: ChatId): WalletOwner.Group? =
        database.tx("wallet-owners.by-chat") {
            WalletOwnersTable
                .selectAll()
                .where {
                    (WalletOwnersTable.chatId eq chatId.value) and
                        (WalletOwnersTable.ownerType eq WalletOwnerType.GROUP.name)
                }.map { it.toGroup() }
                .singleOrNull()
        }

    override suspend fun byChats(chatIds: Collection<ChatId>): List<WalletOwner.Group> {
        if (chatIds.isEmpty()) return emptyList()
        return database.tx("wallet-owners.by-chats") {
            WalletOwnersTable
                .selectAll()
                .where {
                    (WalletOwnersTable.chatId inList chatIds.map { it.value }) and
                        (WalletOwnersTable.ownerType eq WalletOwnerType.GROUP.name)
                }.map { it.toGroup() }
                .toList()
        }
    }

    override suspend fun bySigner(signerId: UserId): List<WalletOwner> =
        database.tx("wallet-owners.by-signer") {
            WalletOwnersTable
                .selectAll()
                .where { WalletOwnersTable.signerId eq signerId.value }
                .map { it.toOwner() }
                .toList()
        }

    override suspend fun bySpace(spaceId: UUID): WalletOwner.Space? =
        database.tx("wallet-owners.by-space") {
            WalletOwnersTable
                .selectAll()
                .where {
                    (WalletOwnersTable.spaceId eq spaceId) and
                        (WalletOwnersTable.ownerType eq WalletOwnerType.SPACE.name)
                }.map { it.toSpace() }
                .singleOrNull()
        }

    override suspend fun bySpaces(spaceIds: Collection<UUID>): List<WalletOwner.Space> {
        if (spaceIds.isEmpty()) return emptyList()
        return database.tx("wallet-owners.by-spaces") {
            WalletOwnersTable
                .selectAll()
                .where {
                    (WalletOwnersTable.spaceId inList spaceIds.distinct()) and
                        (WalletOwnersTable.ownerType eq WalletOwnerType.SPACE.name)
                }.map { it.toSpace() }
                .toList()
        }
    }

    override suspend fun insertSpace(owner: WalletOwner.Space): Boolean =
        database.tx("wallet-owners.insert-space") {
            val statement =
                WalletOwnersTable.insertIgnore {
                    it[walletId] = owner.walletId.value
                    it[ownerType] = WalletOwnerType.SPACE.name
                    it[spaceId] = owner.spaceId
                    it[signerId] = owner.signerId.value
                    it[createdBy] = owner.createdBy.value
                    it[createdAt] = owner.createdAt
                }
            statement.insertedCount > 0
        }

    /** A group's or a space's wallet, by the row's owner type. */
    private fun ResultRow.toOwner(): WalletOwner =
        if (this[WalletOwnersTable.ownerType] == WalletOwnerType.SPACE.name) toSpace() else toGroup()

    private fun ResultRow.toSpace() =
        WalletOwner.Space(
            walletId = WalletId(this[WalletOwnersTable.walletId]),
            spaceId = checkNotNull(this[WalletOwnersTable.spaceId]),
            signerId = UserId(this[WalletOwnersTable.signerId]),
            createdBy = UserId(this[WalletOwnersTable.createdBy]),
            createdAt = this[WalletOwnersTable.createdAt],
        )

    override suspend fun insert(owner: WalletOwner.Group): Boolean =
        database.tx("wallet-owners.insert") {
            val statement =
                WalletOwnersTable.insertIgnore {
                    it[walletId] = owner.walletId.value
                    it[ownerType] = WalletOwnerType.GROUP.name
                    it[chatId] = owner.chatId.value
                    it[signerId] = owner.signerId.value
                    it[createdBy] = owner.createdBy.value
                    it[createdAt] = owner.createdAt
                }
            statement.insertedCount > 0
        }

    private fun ResultRow.toGroup() =
        WalletOwner.Group(
            walletId = WalletId(this[WalletOwnersTable.walletId]),
            chatId = ChatId(checkNotNull(this[WalletOwnersTable.chatId])),
            signerId = UserId(this[WalletOwnersTable.signerId]),
            createdBy = UserId(this[WalletOwnersTable.createdBy]),
            createdAt = this[WalletOwnersTable.createdAt],
        )
}
