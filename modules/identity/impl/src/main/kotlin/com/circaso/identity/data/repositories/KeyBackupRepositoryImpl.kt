package com.circaso.identity.data.repositories

import com.circaso.identity.data.tables.GroupKeyBackupsTable
import com.circaso.identity.data.tables.KeyBackupRootsTable
import com.circaso.identity.domain.models.GroupKeyBackup
import com.circaso.identity.domain.models.GroupKeyPosition
import com.circaso.identity.domain.models.KeyBackupRoot
import com.circaso.identity.domain.repositories.KeyBackupRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.util.UUID

class KeyBackupRepositoryImpl(private val database: Database) : KeyBackupRepository {
    override suspend fun findRoot(userId: UserId): KeyBackupRoot? =
        database.tx("key-backup.find-root") {
            KeyBackupRootsTable
                .selectAll()
                .where { KeyBackupRootsTable.userId eq userId.value }
                .map { it.toRoot() }
                .singleOrNull()
        }

    override suspend fun findGroupKey(
        userId: UserId,
        chatId: UUID,
        metaVersion: Int,
    ): GroupKeyBackup? =
        database.tx("key-backup.find-group-key") {
            GroupKeyBackupsTable
                .selectAll()
                .where {
                    (GroupKeyBackupsTable.userId eq userId.value) and (GroupKeyBackupsTable.chatId eq chatId) and
                        (GroupKeyBackupsTable.metaVersion eq metaVersion)
                }.map { it.toGroupKey() }
                .singleOrNull()
        }

    override suspend fun hasGroupKeys(userId: UserId, chatId: UUID): Boolean =
        database.tx("key-backup.has-group-keys") {
            GroupKeyBackupsTable
                .select(GroupKeyBackupsTable.metaVersion)
                .where { (GroupKeyBackupsTable.userId eq userId.value) and (GroupKeyBackupsTable.chatId eq chatId) }
                .limit(1)
                .firstOrNull() != null
        }

    /** A range of the primary key, at most 10,000 rows. */
    override suspend fun countGroupKeys(userId: UserId): Int =
        database.tx("key-backup.count-group-keys") {
            val count = GroupKeyBackupsTable.chatId.count()
            GroupKeyBackupsTable
                .select(count)
                .where { GroupKeyBackupsTable.userId eq userId.value }
                .map { it[count] }
                .single()
                .toInt()
        }

    /**
     * Keyset paging on the primary key: `WHERE user_id = ? AND (chat_id > ? OR (chat_id = ? AND meta_version > ?))
     * ORDER BY chat_id, meta_version LIMIT ?`.
     */
    override suspend fun listGroupKeys(
        userId: UserId,
        after: GroupKeyPosition?,
        limit: Int,
    ): List<GroupKeyBackup> =
        database.tx("key-backup.list-group-keys") {
            GroupKeyBackupsTable
                .selectAll()
                .where {
                    val own = GroupKeyBackupsTable.userId eq userId.value
                    if (after == null) {
                        own
                    } else {
                        own and
                            (
                                (GroupKeyBackupsTable.chatId greater after.chatId) or
                                    (
                                        (GroupKeyBackupsTable.chatId eq after.chatId) and
                                            (GroupKeyBackupsTable.metaVersion greater after.metaVersion)
                                    )
                            )
                    }
                }.orderBy(
                    GroupKeyBackupsTable.chatId to SortOrder.ASC,
                    GroupKeyBackupsTable.metaVersion to SortOrder.ASC,
                ).limit(limit)
                .map { it.toGroupKey() }
                .toList()
        }

    /** For the store: writes the root as it is now, inside the open transaction; a replace keeps `created_at`. */
    internal suspend fun saveRoot(root: KeyBackupRoot) {
        database.tx("key-backup.save-root") {
            KeyBackupRootsTable.upsert(
                KeyBackupRootsTable.userId,
                onUpdateExclude = listOf(KeyBackupRootsTable.createdAt),
            ) {
                it[userId] = root.userId.value
                it[credentialId] = root.credentialId
                it[salt] = root.salt
                it[sealedRoot] = root.sealedRoot
                it[algorithm] = root.algorithm
                it[version] = root.version
                it[createdAt] = root.updatedAt
                it[updatedAt] = root.updatedAt
            }
        }
    }

    /** For the store: writes one group key as it is now, inside the open transaction. */
    internal suspend fun saveGroupKey(key: GroupKeyBackup) {
        database.tx("key-backup.save-group-key") {
            GroupKeyBackupsTable.upsert(
                GroupKeyBackupsTable.userId,
                GroupKeyBackupsTable.chatId,
                GroupKeyBackupsTable.metaVersion,
            ) {
                it[userId] = key.userId.value
                it[chatId] = key.chatId
                it[metaVersion] = key.metaVersion
                it[ciphertext] = key.ciphertext
                it[updatedAt] = key.updatedAt
            }
        }
    }

    /** For the store: deletes the account's root and every group key, inside the open transaction. */
    internal suspend fun delete(userId: UserId) {
        database.tx("key-backup.delete") {
            GroupKeyBackupsTable.deleteWhere { GroupKeyBackupsTable.userId eq userId.value }
            KeyBackupRootsTable.deleteWhere { KeyBackupRootsTable.userId eq userId.value }
        }
    }
}

private fun ResultRow.toRoot() =
    KeyBackupRoot(
        userId = UserId(this[KeyBackupRootsTable.userId]),
        credentialId = this[KeyBackupRootsTable.credentialId],
        salt = this[KeyBackupRootsTable.salt],
        sealedRoot = this[KeyBackupRootsTable.sealedRoot],
        algorithm = this[KeyBackupRootsTable.algorithm],
        version = this[KeyBackupRootsTable.version],
        updatedAt = this[KeyBackupRootsTable.updatedAt],
    )

private fun ResultRow.toGroupKey() =
    GroupKeyBackup(
        userId = UserId(this[GroupKeyBackupsTable.userId]),
        chatId = this[GroupKeyBackupsTable.chatId],
        metaVersion = this[GroupKeyBackupsTable.metaVersion],
        ciphertext = this[GroupKeyBackupsTable.ciphertext],
        updatedAt = this[GroupKeyBackupsTable.updatedAt],
    )
