package com.circaso.users.api

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant

// Users' staff-facing contract (decision 2026-11-08), for the ops module behind the ops gateway only.

/**
 * A profile as staff see it, whatever the account's status (a suspended or deleted account's hidden profile too).
 * [avatarUrl] is a short-lived signed URL, or null.
 */
data class AdminProfile(
    val userId: UserId,
    val displayName: String,
    val handle: String?,
    val about: String?,
    val avatarUrl: String?,
    val support: Boolean,
    val createdAt: Instant,
)

/** User ids in the search's order; [nextCursor] continues it, null on the last page. */
data class ProfileIdPage(val userIds: List<UserId>, val nextCursor: String?)

interface ProfileAdmin {
    suspend fun profiles(ids: Set<UserId>): Map<UserId, AdminProfile>

    /**
     * Profiles whose live @handle starts with [prefix] (lowercased, without `@`), in handle order. Error:
     * `request.malformed` for a cursor that isn't one. [limit] 1-200.
     */
    suspend fun searchByHandle(
        prefix: String,
        cursor: String?,
        limit: Int,
    ): Resource<ProfileIdPage>

    /** Profiles whose display name starts with [prefix], case-insensitively, in name order. Same paging. */
    suspend fun searchByName(
        prefix: String,
        cursor: String?,
        limit: Int,
    ): Resource<ProfileIdPage>
}
