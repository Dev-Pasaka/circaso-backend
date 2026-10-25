package com.circaso.messaging.domain.ports

import com.circaso.shared.UserId
import java.time.Duration

/** Counts key-bundle fetches per user (`msg:bundle-rate:{userId}`), so nobody can drain someone's one-time prekeys. */
interface BundleRateLimit {
    /** Counts one fetch by [userId]; returns how many they made in the current hour, this one included. */
    suspend fun count(userId: UserId): Long

    /** How long until [userId]'s hour is over, or null when no window is open. */
    suspend fun retryAfter(userId: UserId): Duration?
}
