package com.circaso.users.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.users.data.tables.BlocksTable
import com.circaso.users.domain.models.Block
import com.circaso.users.domain.repositories.BlockRepository
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** One table; both directions of a pair are primary-key reads. */
class BlockRepositoryImpl(private val database: Database) : BlockRepository {
    override suspend fun blocked(userId: UserId, blockedId: UserId): Boolean =
        database.tx("blocks.find") {
            BlocksTable
                .selectAll()
                .where { (BlocksTable.userId eq userId.value) and (BlocksTable.blockedId eq blockedId.value) }
                .limit(1)
                .toList()
                .isNotEmpty()
        }

    override suspend fun eitherWay(a: UserId, b: UserId): Boolean =
        database.tx("blocks.either-way") {
            BlocksTable
                .selectAll()
                .where {
                    ((BlocksTable.userId eq a.value) and (BlocksTable.blockedId eq b.value)) or
                        ((BlocksTable.userId eq b.value) and (BlocksTable.blockedId eq a.value))
                }.limit(1)
                .toList()
                .isNotEmpty()
        }

    override suspend fun list(userId: UserId): List<Block> =
        database.tx("blocks.list") {
            BlocksTable
                .selectAll()
                .where { BlocksTable.userId eq userId.value }
                .orderBy(BlocksTable.blockedId to SortOrder.ASC)
                .toList()
                .map {
                    Block(
                        UserId(it[BlocksTable.userId]),
                        UserId(it[BlocksTable.blockedId]),
                        it[BlocksTable.blockedAt],
                    )
                }
        }
}
