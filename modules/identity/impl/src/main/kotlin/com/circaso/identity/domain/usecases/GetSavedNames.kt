package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.models.SavedNames
import com.circaso.identity.domain.repositories.KeyBackupRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** KB6 `GET /key-backup/saved-names`: the sealed saved names blob, or null when none was saved. Logs nothing. */
class GetSavedNames(private val keys: KeyBackupRepository) {
    suspend operator fun invoke(userId: UserId): Resource<SavedNames?> = Resource.success(keys.findSavedNames(userId))
}
