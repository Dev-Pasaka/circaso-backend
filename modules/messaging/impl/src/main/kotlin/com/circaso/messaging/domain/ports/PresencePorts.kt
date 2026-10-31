package com.circaso.messaging.domain.ports

import com.circaso.messaging.domain.models.PresenceCheck
import com.circaso.messaging.domain.models.PresenceRecord
import com.circaso.shared.UserId
import java.time.Instant

/**
 * `msg:presence:{userId}`, kept 30 days (the last seen), and `msg:presence-due:{bucket}`, the heartbeats whose
 * 75 seconds run out in a 5-second bucket, so the sweep finds the phones that went quiet without saying `away`.
 */
interface PresenceStore {
    suspend fun read(userIds: Collection<UserId>): Map<UserId, PresenceRecord>

    suspend fun save(userId: UserId, record: PresenceRecord)

    /** Look at [userId] again once [dueAt] has passed, unless a later heartbeat replaced [lastActiveAt]. */
    suspend fun scheduleCheck(
        userId: UserId,
        lastActiveAt: Instant,
        dueAt: Instant,
    )

    /** Takes (once, across nodes) the checks that fell due before [now]. */
    suspend fun takeDue(now: Instant): List<PresenceCheck>
}

/**
 * `msg:presence-watchers:{userId}`: who has a chat with [userId] open, each until their watch lapses (60 seconds,
 * renewed every 45 by the phone).
 */
interface PresenceWatchers {
    suspend fun watch(
        watcher: UserId,
        watched: Collection<UserId>,
        until: Instant,
    )

    /** The watchers of [userId] whose watch hasn't lapsed at [now]. */
    suspend fun watchersOf(userId: UserId, now: Instant): Set<UserId>
}
