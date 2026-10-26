package com.circaso.users.api

import com.circaso.shared.UserId

/** Messaging, wallet and calls check this before one person touches another. */
interface ReachPolicy {
    suspend fun canReach(from: UserId, to: UserId): Boolean

    suspend fun reachable(from: UserId, to: Set<UserId>): Set<UserId>

    /**
     * Whether either person has blocked the other, whatever else connects them. Messaging asks it where a shared
     * group stands in for reach, so a group never overrides a block (decision 2026-10-26).
     */
    suspend fun blockedEitherWay(a: UserId, b: UserId): Boolean
}

/** Display data for other modules. */
interface Profiles {
    suspend fun get(ids: Set<UserId>): Map<UserId, PublicProfile>
}

/** The only shape of a person other modules see. It never contains a phone number. */
data class PublicProfile(
    val userId: UserId,
    val displayName: String,
    val handle: String?,
    val avatarUrl: String?,
    val about: String?,
)
