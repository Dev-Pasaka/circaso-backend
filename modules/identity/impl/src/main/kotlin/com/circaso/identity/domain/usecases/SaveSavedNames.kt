package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.SaveSavedNamesCommand
import com.circaso.identity.domain.models.SavedNames
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.KeyBackupRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.time.Clock

/**
 * KB7 `PUT /key-backup/saved-names` (2026-11-12): replaces the account's one sealed saved names blob, as sent, at
 * `baseVersion + 1`. A compare-and-set under the account's lock: only while key backup is on (a root,
 * `identity.key_backup_off` otherwise), and only when `baseVersion` is the stored version, 0 when there is none
 * (`identity.saved_names_conflict` otherwise), so two phones can't overwrite each other's names unseen. Not logged:
 * names change often and the blob opens nothing.
 */
class SaveSavedNames(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val keys: KeyBackupRepository,
    private val store: IdentityStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: SaveSavedNamesCommand): Resource<SavedNames> {
        val userId = command.principal.userId
        return transactor.attempt("key-backup.save-saved-names") {
            accounts.lock(userId) ?: return@attempt Resource.error(SharedError.NotFound)
            keys.findRoot(userId) ?: return@attempt Resource.error(IdentityError.KeyBackupOff)
            val stored = keys.findSavedNames(userId)
            if (command.baseVersion != (stored?.version ?: 0L)) {
                return@attempt Resource.error(IdentityError.SavedNamesConflict)
            }
            val names = SavedNames(userId, command.ciphertext, command.baseVersion + 1, clock.instant())
            store.write(Changes(savedNames = listOf(names)))
            Resource.success(names)
        }
    }
}
