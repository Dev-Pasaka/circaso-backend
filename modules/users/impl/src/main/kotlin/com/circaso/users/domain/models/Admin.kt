package com.circaso.users.domain.models

import com.circaso.shared.UserId

// What staff read about profiles on the ops gateway (decision 2026-11-08).

/** A profile whatever its account's status, with its photo as a short-lived signed URL (privacy doesn't apply to staff). */
data class AdminProfileView(val profile: Profile, val avatarUrl: String?)

/**
 * Where a search page stopped: the last row's [sortKey] (its handle, or its lowercased display name) and its id. Ids
 * are what a search page answers; the ops module asks [AdminProfileView]s for them.
 */
data class ProfileKey(val sortKey: String, val userId: UserId)

data class ProfileIdResults(val userIds: List<UserId>, val nextCursor: String?)
