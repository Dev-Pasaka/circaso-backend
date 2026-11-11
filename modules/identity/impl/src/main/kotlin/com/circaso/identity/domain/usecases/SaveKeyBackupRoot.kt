package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.errors.KeyBackupError
import com.circaso.identity.domain.models.KeyBackupRoot
import com.circaso.identity.domain.models.SaveKeyBackupRootCommand
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.KeyBackupRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.time.Clock

/**
 * KB2 `PUT /key-backup/root`: creates the account's backup root at version 1 (`KEY_BACKUP_ON`), or replaces it at the
 * next version (`KEY_BACKUP_CHANGED`). Read and written under the account's lock, so with `expectedVersion` a root
 * another phone saved meanwhile is refused (`key_backup.version_conflict`) instead of silently overwritten.
 */
class SaveKeyBackupRoot(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val keys: KeyBackupRepository,
    private val store: IdentityStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: SaveKeyBackupRootCommand): Resource<KeyBackupRoot> {
        val userId = command.principal.userId
        return transactor.attempt("key-backup.save-root") {
            accounts.lock(userId) ?: return@attempt Resource.error(SharedError.NotFound)
            val stored = keys.findRoot(userId)
            if (command.expectedVersion != null && command.expectedVersion != stored?.version) {
                return@attempt Resource.error(KeyBackupError.VersionConflict)
            }
            val root =
                KeyBackupRoot(
                    userId = userId,
                    credentialId = command.credentialId,
                    salt = command.salt,
                    sealedRoot = command.sealedRoot,
                    algorithm = command.algorithm,
                    version = (stored?.version ?: 0) + 1,
                    updatedAt = clock.instant(),
                )
            val type = if (stored == null) SecurityEventType.KEY_BACKUP_ON else SecurityEventType.KEY_BACKUP_CHANGED
            val model = devices.find(userId, command.principal.deviceId)?.model
            val logged = SecurityEvent(userId, null, type, model, root.version.toString())
            store.write(Changes(keyBackupRoots = listOf(root), securityEvents = listOf(logged)))
            Resource.success(root)
        }
    }
}
