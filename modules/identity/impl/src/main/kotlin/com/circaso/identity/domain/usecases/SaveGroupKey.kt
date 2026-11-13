package com.circaso.identity.domain.usecases

import com.circaso.identity.api.GroupMembership
import com.circaso.identity.domain.errors.KeyBackupError
import com.circaso.identity.domain.models.GroupKeyBackup
import com.circaso.identity.domain.models.SaveGroupKeyCommand
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.KeyBackupRepository
import com.circaso.identity.domain.repositories.KeyBackupRepository.Companion.MAX_GROUP_KEYS
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.time.Clock

/**
 * KB4 `PUT /key-backup/group-keys/{chatId}/{metaVersion}`: stores one group meta key, encrypted under the backup root,
 * as sent. Only for a chat the caller is in (asked of messaging through [GroupMembership], before the transaction) or
 * already backed up a key of (so a phone that left can still finish backing up a group it was in). Under the account's
 * lock: backup must be on (a root), the account holds at most [MAX_GROUP_KEYS] keys, and the same blob again changes
 * nothing; another blob at the same `metaVersion` replaces it.
 */
class SaveGroupKey(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val keys: KeyBackupRepository,
    private val membership: GroupMembership,
    private val store: IdentityStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: SaveGroupKeyCommand): Resource<Unit> {
        val userId = command.principal.userId
        val allowed = membership.isMember(command.chatId, userId) || keys.hasGroupKeys(userId, command.chatId)
        if (!allowed) return Resource.error(KeyBackupError.ChatNotFound)
        return transactor.attempt("key-backup.save-group-key") {
            accounts.lock(userId) ?: return@attempt Resource.error(SharedError.NotFound)
            keys.findRoot(userId) ?: return@attempt Resource.error(KeyBackupError.NotFound)
            val stored = keys.findGroupKey(userId, command.chatId, command.metaVersion)
            if (stored?.ciphertext == command.ciphertext) return@attempt Resource.success(Unit)
            if (stored == null && keys.countGroupKeys(userId) >= MAX_GROUP_KEYS) {
                return@attempt Resource.error(KeyBackupError.TooMany)
            }
            val key = GroupKeyBackup(userId, command.chatId, command.metaVersion, command.ciphertext, clock.instant())
            store.write(Changes(groupKeys = listOf(key)))
            Resource.success(Unit)
        }
    }
}
