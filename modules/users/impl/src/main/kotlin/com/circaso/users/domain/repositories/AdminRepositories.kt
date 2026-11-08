package com.circaso.users.domain.repositories

import com.circaso.users.domain.models.ProfileKey

/**
 * Staff search over every profile, hidden ones too (decision 2026-11-08). Each read is a keyset page of an index built
 * for it (V4__admin_search): a prefix is a range of the index, never a scan, whatever the prefix.
 */
interface ProfileSearchRepository {
    /** Profiles whose live handle starts with [prefix] (stored form), after [after] in handle order, at most [limit]. */
    suspend fun byHandlePrefix(
        prefix: String,
        after: ProfileKey?,
        limit: Int,
    ): List<ProfileKey>

    /**
     * Profiles whose lowercased display name starts with [prefix] (already lowercased), after [after] in
     * `(lower(display_name), user_id)` order, at most [limit].
     */
    suspend fun byNamePrefix(
        prefix: String,
        after: ProfileKey?,
        limit: Int,
    ): List<ProfileKey>
}
