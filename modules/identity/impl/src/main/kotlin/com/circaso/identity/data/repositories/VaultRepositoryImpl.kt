package com.circaso.identity.data.repositories

import com.circaso.identity.data.tables.PinVaultsTable
import com.circaso.identity.domain.models.PinVault
import com.circaso.identity.domain.repositories.VaultRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert

class VaultRepositoryImpl(private val database: Database) : VaultRepository {
    override suspend fun find(userId: UserId): PinVault? =
        database.tx("vaults.find") {
            PinVaultsTable
                .selectAll()
                .where { PinVaultsTable.userId eq userId.value }
                .map { it.toVault() }
                .singleOrNull()
        }

    /**
     * For the store: writes the vault as it is now, inside the open transaction. `created_at` is set by the first
     * write only; a replace keeps it.
     */
    internal suspend fun save(vault: PinVault) {
        database.tx("vaults.save") {
            PinVaultsTable.upsert(
                PinVaultsTable.userId,
                onUpdateExclude = listOf(PinVaultsTable.createdAt),
            ) {
                it[userId] = vault.userId.value
                it[credentialId] = vault.credentialId
                it[salt] = vault.salt
                it[ciphertext] = vault.ciphertext
                it[algorithm] = vault.algorithm
                it[version] = vault.version
                it[createdAt] = vault.updatedAt
                it[updatedAt] = vault.updatedAt
            }
        }
    }

    /** For the store: deletes the account's vault, inside the open transaction. */
    internal suspend fun delete(userId: UserId) {
        database.tx("vaults.delete") {
            PinVaultsTable.deleteWhere { PinVaultsTable.userId eq userId.value }
        }
    }
}

private fun ResultRow.toVault() =
    PinVault(
        userId = UserId(this[PinVaultsTable.userId]),
        credentialId = this[PinVaultsTable.credentialId],
        salt = this[PinVaultsTable.salt],
        ciphertext = this[PinVaultsTable.ciphertext],
        algorithm = this[PinVaultsTable.algorithm],
        version = this[PinVaultsTable.version],
        updatedAt = this[PinVaultsTable.updatedAt],
    )
