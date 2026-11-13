package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.errors.VaultError
import com.circaso.identity.domain.models.PinVault
import com.circaso.identity.domain.models.SaveVaultCommand
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.VaultRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.time.Clock

/**
 * V3 `PUT /vault`: creates the account's PIN Vault at version 1, or replaces it at the next version, and logs
 * `VAULT_SAVED`. Read and written under the account's lock, so of two phones saving at once the second sees the first's
 * version: with `expectedVersion` it is refused (`vault.version_conflict`) instead of silently overwriting it.
 */
class SaveVault(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val vaults: VaultRepository,
    private val store: IdentityStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: SaveVaultCommand): Resource<PinVault> {
        val userId = command.principal.userId
        return transactor.attempt("vaults.save") {
            accounts.lock(userId) ?: return@attempt Resource.error(SharedError.NotFound)
            val stored = vaults.find(userId)
            if (command.expectedVersion != null && command.expectedVersion != stored?.version) {
                return@attempt Resource.error(VaultError.VersionConflict)
            }
            val vault =
                PinVault(
                    userId = userId,
                    credentialId = command.credentialId,
                    salt = command.salt,
                    ciphertext = command.ciphertext,
                    algorithm = command.algorithm,
                    version = (stored?.version ?: 0) + 1,
                    updatedAt = clock.instant(),
                )
            val model = devices.find(userId, command.principal.deviceId)?.model
            val saved = SecurityEvent(userId, null, SecurityEventType.VAULT_SAVED, model, vault.version.toString())
            store.write(Changes(vaults = listOf(vault), securityEvents = listOf(saved)))
            Resource.success(vault)
        }
    }
}
