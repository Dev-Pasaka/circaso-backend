package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.models.KeyBackupRoot
import com.circaso.identity.domain.repositories.KeyBackupRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * KB1 `GET /key-backup/root`: the sealed backup root, or null when backup is off. Unlike opening the PIN Vault this is
 * not sensitive (the root only opens group names, and only with the passkey), so nothing is logged, sent or paused.
 */
class GetKeyBackupRoot(private val keys: KeyBackupRepository) {
    suspend operator fun invoke(userId: UserId): Resource<KeyBackupRoot?> = Resource.success(keys.findRoot(userId))
}
