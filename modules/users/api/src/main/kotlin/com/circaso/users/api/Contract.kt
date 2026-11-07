package com.circaso.users.api

import com.circaso.shared.UserId
import java.time.Instant

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

/**
 * The Circaso people in someone's address book (their active contacts), for spaces' "Juma and 2 other contacts
 * follow" and its suggestions (decision 2026-11-04). Only ids: nothing about the address book itself leaves users.
 */
interface ContactBook {
    /** At most [limit] of [userId]'s active contacts. */
    suspend fun contactsOf(userId: UserId, limit: Int = 5_000): Set<UserId>
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
    /** Circaso's own support account (decision 2026-10-31), which the app shows as verified support. */
    val support: Boolean = false,
)

/**
 * Invites made on someone's behalf by another module (decision 2026-11-06: an invite share link carries one), the
 * same as U12: whoever connects with the token is connected to [IssuedInvite]'s owner both ways.
 */
interface Invites {
    /** A fresh invite for [owner], valid 7 days and revocable like any other; null when users can't make one now. */
    suspend fun create(owner: UserId): IssuedInvite?
}

/** An invite's secret [token] and when it ends. The token is never logged. */
data class IssuedInvite(val token: String, val expiresAt: Instant) {
    override fun toString(): String = "IssuedInvite(***, $expiresAt)"
}
