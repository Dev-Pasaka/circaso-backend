package com.circaso.messaging.domain.ports

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessagingFrame
import com.circaso.shared.UserId
import java.time.Duration

/** Counts key-bundle fetches per user (`msg:bundle-rate:{userId}`), so nobody can drain someone's one-time prekeys. */
interface BundleRateLimit {
    /** Counts one fetch by [userId]; returns how many they made in the current hour, this one included. */
    suspend fun count(userId: UserId): Long

    /** How long until [userId]'s hour is over, or null when no window is open. */
    suspend fun retryAfter(userId: UserId): Duration?
}

/**
 * `msg:members:{chatId}`: who is in a chat, for 10 minutes. A change writes a short marker in its place instead of
 * deleting it, and readers fill it only if nothing is there, so a list read before a change is never put back
 * after it (decision 2026-10-26).
 */
interface MembersCache {
    /** The cached members, or null when nothing is cached or a change was just marked. */
    suspend fun get(chatId: ChatId): Set<UserId>?

    /** Caches [members] unless the key holds anything, a change marker included. */
    suspend fun fillIfAbsent(chatId: ChatId, members: Set<UserId>)

    /** Replaces the entry with a marker for a few seconds, so readers go to the rows and don't refill it. */
    suspend fun markChanged(chatId: ChatId)
}

/**
 * Sends a replayed frame to each user with that user's own `seq`, and keeps it 7 days for replay. The frame's class
 * is its `data` (decision 2026-10-27). Failures never fail the operation that caused them: the phone catches up from
 * the kept frames.
 */
interface RealtimeFrames {
    suspend fun send(userIds: Collection<UserId>, frame: MessagingFrame)
}

/** The frames `messaging` has kept, so a phone that reconnects can catch up. */
interface RealtimeLog {
    /** Frames after [seq], oldest first. */
    suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ): List<LoggedFrame>

    /** The latest `seq` handed out for this user, or null if none is remembered. */
    suspend fun latest(userId: UserId): Long?
}

data class LoggedFrame(val seq: Long, val json: String)
