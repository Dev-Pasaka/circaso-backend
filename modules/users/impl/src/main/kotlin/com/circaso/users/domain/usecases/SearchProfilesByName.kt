package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.users.domain.models.ProfileIdResults
import com.circaso.users.domain.repositories.ProfileSearchRepository
import com.circaso.users.domain.support.ProfileCursors
import com.circaso.users.domain.support.pageOf

/**
 * Staff search by display-name prefix, case-insensitively (decision 2026-11-08), in name order, paged by keyset on
 * `(lower(display_name), user_id)`, since many people share a name. A blank prefix finds nothing.
 */
class SearchProfilesByName(private val search: ProfileSearchRepository) {
    suspend operator fun invoke(
        prefix: String,
        cursor: String?,
        limit: Int,
    ): Resource<ProfileIdResults> {
        val after =
            cursor?.let {
                ProfileCursors.decode(it) ?: return Resource.error(SharedError.Malformed("cursor", "invalid"))
            }
        val wanted = prefix.trim().lowercase()
        if (wanted.isEmpty()) return Resource.success(ProfileIdResults(emptyList(), null))
        val page = limit.coerceIn(1, MAX_PAGE)
        return Resource.success(pageOf(search.byNamePrefix(wanted, after, page + 1), page))
    }

    private companion object {
        const val MAX_PAGE = 200
    }
}
