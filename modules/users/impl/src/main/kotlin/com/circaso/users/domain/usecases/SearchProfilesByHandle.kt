package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.users.domain.models.ProfileIdResults
import com.circaso.users.domain.repositories.ProfileSearchRepository
import com.circaso.users.domain.support.ProfileCursors
import com.circaso.users.domain.support.pageOf

/**
 * Staff search by @handle prefix (decision 2026-11-08): the live handle on the profile, lowercased and without `@` as
 * stored, in handle order, paged by keyset on the handle (unique among profiles). A blank prefix finds nothing rather
 * than every profile: the newest-first list is identity's.
 */
class SearchProfilesByHandle(private val search: ProfileSearchRepository) {
    suspend operator fun invoke(
        prefix: String,
        cursor: String?,
        limit: Int,
    ): Resource<ProfileIdResults> {
        val after =
            cursor?.let {
                ProfileCursors.decode(it) ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        val wanted = prefix.trim().removePrefix("@").lowercase()
        if (wanted.isEmpty()) return Resource.success(ProfileIdResults(emptyList(), null))
        val page = limit.coerceIn(1, MAX_PAGE)
        return Resource.success(pageOf(search.byHandlePrefix(wanted, after, page + 1), page))
    }

    private companion object {
        const val MAX_PAGE = 200
    }
}
