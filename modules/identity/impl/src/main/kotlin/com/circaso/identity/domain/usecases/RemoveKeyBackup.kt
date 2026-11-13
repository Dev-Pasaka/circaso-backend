package com.circaso.identity.domain.usecases

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.KeyBackupRepository
import com.circaso.shared.Resource

/**
 * KB3 `DELETE /key-backup`: turns group key backup off, deleting the root, every group key and the saved names, and
 * logs `KEY_BACKUP_OFF`. With nothing backed up there is nothing to delete or log, and the answer is the same.
 */
class RemoveKeyBackup(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val keys: KeyBackupRepository,
    private val store: IdentityStore,
) {
    suspend operator fun invoke(principal: Principal): Resource<Unit> {
        val userId = principal.userId
        transactor.run("key-backup.remove") {
            accounts.lock(userId) ?: return@run
            val nothing =
                keys.findRoot(userId) == null &&
                    keys.countGroupKeys(userId) == 0 &&
                    keys.findSavedNames(userId) == null
            if (nothing) return@run
            val model = devices.find(userId, principal.deviceId)?.model
            val off = SecurityEvent(userId, null, SecurityEventType.KEY_BACKUP_OFF, model, null)
            store.write(Changes(removedKeyBackups = listOf(userId), securityEvents = listOf(off)))
        }
        return Resource.success(Unit)
    }
}
